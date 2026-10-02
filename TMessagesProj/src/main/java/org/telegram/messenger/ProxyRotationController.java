package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ProxyRotationController implements NotificationCenter.NotificationCenterDelegate {
    private final static ProxyRotationController INSTANCE = new ProxyRotationController();

    public final static int DEFAULT_TIMEOUT_INDEX = 1;
    public final static List<Integer> ROTATION_TIMEOUTS = Arrays.asList(
            5, 10, 15, 30, 60
    );

    private boolean isCurrentlyChecking;
    private boolean isCheckScheduled;
    private Runnable checkProxyAndSwitchRunnable = () -> {
        isCheckScheduled = false;
        if (!shouldRotate()) {
            return;
        }
        isCurrentlyChecking = true;

        int currentAccount = UserConfig.selectedAccount;
        for (int i = 0; i < SharedConfig.proxyList.size(); i++) {
            SharedConfig.ProxyInfo proxyInfo = SharedConfig.proxyList.get(i);
            if (proxyInfo.checking || proxyInfo.availableCheckTime != 0 && SystemClock.elapsedRealtime() - proxyInfo.availableCheckTime < 2 * 60 * 1000) {
                continue;
            }
            proxyInfo.checking = true;
            ConnectionsManager.getInstance(currentAccount).checkProxy(proxyInfo.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                proxyInfo.availableCheckTime = SystemClock.elapsedRealtime();
                proxyInfo.checking = false;
                if (time == -1) {
                    proxyInfo.available = false;
                    proxyInfo.ping = 0;
                } else {
                    proxyInfo.ping = time;
                    proxyInfo.available = true;
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, proxyInfo);
            }));
        }

        switchToAvailable();
    };

    public static void init() {
        INSTANCE.initInternal();
    }

    public static void checkCurrentConnectionState() {
        int account = UserConfig.selectedAccount;
        if (SharedConfig.activeAccounts.contains(account)) {
            INSTANCE.onConnectionStateChanged(account);
        }
    }

    @SuppressWarnings("ComparatorCombinators")
    private void switchToAvailable() {
        if (!shouldRotate()) {
            isCurrentlyChecking = false;
            return;
        }

        List<SharedConfig.ProxyInfo> sortedList = new ArrayList<>(SharedConfig.proxyList);
        Collections.sort(sortedList, (o1, o2) -> Long.compare(o1.ping, o2.ping));
        for (SharedConfig.ProxyInfo info : sortedList) {
            if (info == SharedConfig.currentProxy || info.checking || !info.available) {
                continue;
            }

            isCurrentlyChecking = false;
            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
            editor.putBoolean("proxy_enabled", true);
            info.settings.toSharedPreferences(editor);
            editor.apply();

            SharedConfig.currentProxy = info;
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyChangedByRotation);
            ConnectionsManager.setProxySettings(true, SharedConfig.currentProxy.settings);
            return;
        }

        // A failed probe must not discard successful results that arrive later.
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (info.checking) {
                return;
            }
        }
        isCurrentlyChecking = false;
        onConnectionStateChanged(UserConfig.selectedAccount);
    }

    private void initInternal() {
        for (int i : SharedConfig.activeAccounts) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
    }

    private boolean shouldRotate() {
        return SharedConfig.isProxyEnabled() && SharedConfig.proxyRotationEnabled
                && SharedConfig.proxyList.size() > 1
                && ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState() == ConnectionsManager.ConnectionStateConnectingToProxy;
    }

    private void cancelCheck() {
        AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
        isCheckScheduled = false;
        isCurrentlyChecking = false;
    }

    private void onConnectionStateChanged(int account) {
        if (account != UserConfig.selectedAccount) {
            return;
        }
        if (shouldRotate()) {
            if (!isCurrentlyChecking && !isCheckScheduled) {
                isCheckScheduled = true;
                AndroidUtilities.runOnUIThread(checkProxyAndSwitchRunnable, ROTATION_TIMEOUTS.get(SharedConfig.proxyRotationTimeout) * 1000L);
            }
        } else {
            cancelCheck();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxyCheckDone) {
            if (!SharedConfig.isProxyEnabled() || !SharedConfig.proxyRotationEnabled || SharedConfig.proxyList.size() <= 1 || !isCurrentlyChecking) {
                return;
            }

            switchToAvailable();
        } else if (id == NotificationCenter.proxySettingsChanged) {
            cancelCheck();
            onConnectionStateChanged(UserConfig.selectedAccount);
        } else if (id == NotificationCenter.didUpdateConnectionState && account == UserConfig.selectedAccount) {
            onConnectionStateChanged(account);
        }
    }
}
