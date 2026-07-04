package com.voiceagent.app;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class NotificationListener extends NotificationListenerService {

    private static final String TAG = "VoiceAgentNotif";
    private static final List<StatusBarNotification> activeNotifications = new ArrayList<>();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Log.d(TAG, "Notif posted: " + sbn.getPackageName());
        updateCache();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        Log.d(TAG, "Notif removed: " + sbn.getPackageName());
        updateCache();
    }

    private void updateCache() {
        activeNotifications.clear();
        StatusBarNotification[] active = getActiveNotifications();
        if (active != null) {
            for (StatusBarNotification sbn : active) {
                activeNotifications.add(sbn);
            }
        }
    }

    public static List<StatusBarNotification> getCachedNotifications() {
        return new ArrayList<>(activeNotifications);
    }
}
