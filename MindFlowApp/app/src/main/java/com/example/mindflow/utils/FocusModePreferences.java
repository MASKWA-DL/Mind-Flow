package com.example.mindflow.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class FocusModePreferences {
    private static final String PREFS_NAME = "MindFlowPrefs";
    private static final String KEY_FOCUS_ACTIVE = "focus_mode_active";
    private static final String KEY_NOTIFICATION_ALLOWLIST = "notification_allowlist";
    private static final String KEY_NOTIFICATION_BLOCKED_COUNT = "current_blocked_notifications";
    private static final String KEY_AI_AUDIT_SENSITIVITY = "ai_audit_sensitivity";
    private static final String KEY_AI_DEBUG_MODE = "ai_debug_mode";
    private static final String KEY_LOCK_DURATION_SECONDS = "lock_duration_seconds";

    private FocusModePreferences() {
    }

    public static boolean isFocusModeActive(Context context) {
        return getPrefs(context).getBoolean(KEY_FOCUS_ACTIVE, false);
    }

    public static void setFocusModeActive(Context context, boolean active) {
        getPrefs(context).edit().putBoolean(KEY_FOCUS_ACTIVE, active).apply();
    }

    public static Set<String> getNotificationAllowlist(Context context) {
        Set<String> stored = getPrefs(context).getStringSet(KEY_NOTIFICATION_ALLOWLIST, Collections.emptySet());
        return new HashSet<>(stored);
    }

    public static void setNotificationAllowlist(Context context, Set<String> packages) {
        Set<String> sanitized = new HashSet<>();
        if (packages != null) {
            for (String pkg : packages) {
                if (pkg == null) {
                    continue;
                }
                String trimmed = pkg.trim();
                if (!trimmed.isEmpty()) {
                    sanitized.add(trimmed);
                }
            }
        }
        getPrefs(context).edit().putStringSet(KEY_NOTIFICATION_ALLOWLIST, sanitized).apply();
    }

    public static int getBlockedNotificationCount(Context context) {
        return getPrefs(context).getInt(KEY_NOTIFICATION_BLOCKED_COUNT, 0);
    }

    public static void resetBlockedNotificationCount(Context context) {
        getPrefs(context).edit().putInt(KEY_NOTIFICATION_BLOCKED_COUNT, 0).apply();
    }

    public static void incrementBlockedNotificationCount(Context context) {
        SharedPreferences prefs = getPrefs(context);
        int next = prefs.getInt(KEY_NOTIFICATION_BLOCKED_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_NOTIFICATION_BLOCKED_COUNT, next).apply();
    }

    /**
     * AI 分心审计灵敏度：0=宽松，100=严格，默认 50。
     */
    public static int getAiAuditSensitivity(Context context) {
        return getPrefs(context).getInt(KEY_AI_AUDIT_SENSITIVITY, 50);
    }

    public static void setAiAuditSensitivity(Context context, int sensitivity) {
        int clamped = Math.max(0, Math.min(100, sensitivity));
        getPrefs(context).edit().putInt(KEY_AI_AUDIT_SENSITIVITY, clamped).apply();
    }

    /**
     * AI 调试模式：用于用户反馈“判对/判错”与调试训练入口，默认关闭。
     */
    public static boolean isAiDebugModeEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_AI_DEBUG_MODE, false);
    }

    public static void setAiDebugModeEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_AI_DEBUG_MODE, enabled).apply();
    }

    /**
     * 锁屏时长（秒），默认60秒。
     */
    public static int getLockDurationSeconds(Context context) {
        return getPrefs(context).getInt(KEY_LOCK_DURATION_SECONDS, 60);
    }

    public static void setLockDurationSeconds(Context context, int seconds) {
        int clamped = Math.max(30, Math.min(300, seconds));
        getPrefs(context).edit().putInt(KEY_LOCK_DURATION_SECONDS, clamped).apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
