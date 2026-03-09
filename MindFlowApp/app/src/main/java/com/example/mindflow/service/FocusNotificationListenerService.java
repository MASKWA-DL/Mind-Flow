package com.example.mindflow.service;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.example.mindflow.utils.FocusModePreferences;

import java.util.Set;

public class FocusNotificationListenerService extends NotificationListenerService {
    private static final String TAG = "FocusNotifListener";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        if (!FocusModePreferences.isFocusModeActive(this)) {
            return;
        }
        if (shouldAllowNotification(sbn)) {
            return;
        }

        try {
            cancelNotification(sbn.getKey());
            FocusModePreferences.incrementBlockedNotificationCount(this);
            Log.d(TAG, "Blocked notification from " + sbn.getPackageName());
        } catch (Exception e) {
            Log.w(TAG, "Failed to block notification from " + sbn.getPackageName(), e);
        }
    }

    private boolean shouldAllowNotification(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.isEmpty()) {
            return true;
        }
        if (pkg.equals(getPackageName())) {
            return true;
        }

        Notification notification = sbn.getNotification();
        if (notification == null) {
            return true;
        }
        if (!sbn.isClearable()) {
            return true;
        }
        if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return true;
        }

        String category = notification.category;
        if (Notification.CATEGORY_CALL.equals(category)
                || Notification.CATEGORY_ALARM.equals(category)
                || Notification.CATEGORY_NAVIGATION.equals(category)
                || Notification.CATEGORY_TRANSPORT.equals(category)
                || Notification.CATEGORY_SYSTEM.equals(category)) {
            return true;
        }

        if ("android".equals(pkg) || "com.android.systemui".equals(pkg)) {
            return true;
        }

        Set<String> allowlist = FocusModePreferences.getNotificationAllowlist(this);
        return allowlist.contains(pkg);
    }
}
