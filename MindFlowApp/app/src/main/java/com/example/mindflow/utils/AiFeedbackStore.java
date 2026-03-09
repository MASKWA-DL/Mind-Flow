package com.example.mindflow.utils;

import android.content.Context;

import com.example.mindflow.network.GlmApiService;

import org.json.JSONArray;
import org.json.JSONObject;

public final class AiFeedbackStore {
    private static final String PREFS_NAME = "MindFlowPrefs";
    private static final String KEY_PENDING_RESULT = "ai_feedback_pending_result";
    private static final String KEY_PENDING_SUBMITTED = "ai_feedback_pending_submitted";
    private static final String KEY_FEEDBACK_LOG = "ai_feedback_log";
    private static final int MAX_FEEDBACK_LOG = 50;

    private AiFeedbackStore() {
    }

    public static void cacheLatestResult(Context context, String goal, String currentApp, String rawResult,
                                         String activity, boolean isFocused) {
        if (context == null || rawResult == null || rawResult.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("goal", goal == null ? "" : goal);
            payload.put("current_app", currentApp == null ? "" : currentApp);
            payload.put("activity", activity == null ? "" : activity);
            payload.put("raw_result", rawResult);
            payload.put("is_focused", isFocused);
            payload.put("text_model", GlmApiService.getTextModel());
            payload.put("vision_model", GlmApiService.getVisionModel());

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PENDING_RESULT, payload.toString())
                    .putBoolean(KEY_PENDING_SUBMITTED, false)
                    .apply();
        } catch (Exception ignored) {
            // ignore malformed feedback cache
        }
    }

    public static boolean submitLatestFeedback(Context context, boolean isCorrect) {
        if (context == null) {
            return false;
        }
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_PENDING_SUBMITTED, false)) {
            return false;
        }
        String pending = prefs.getString(KEY_PENDING_RESULT, "");
        if (pending == null || pending.isEmpty()) {
            return false;
        }

        try {
            JSONObject feedback = new JSONObject(pending);
            feedback.put("feedback_time", System.currentTimeMillis());
            feedback.put("user_feedback", isCorrect ? "correct" : "incorrect");

            JSONArray history = new JSONArray(prefs.getString(KEY_FEEDBACK_LOG, "[]"));
            history.put(feedback);
            if (history.length() > MAX_FEEDBACK_LOG) {
                JSONArray trimmed = new JSONArray();
                int start = Math.max(0, history.length() - MAX_FEEDBACK_LOG);
                for (int i = start; i < history.length(); i++) {
                    trimmed.put(history.get(i));
                }
                history = trimmed;
            }

            prefs.edit()
                    .putString(KEY_FEEDBACK_LOG, history.toString())
                    .putBoolean(KEY_PENDING_SUBMITTED, true)
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean canSubmitFeedback(Context context) {
        if (context == null) {
            return false;
        }
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String pending = prefs.getString(KEY_PENDING_RESULT, "");
        return pending != null && !pending.isEmpty() && !prefs.getBoolean(KEY_PENDING_SUBMITTED, false);
    }

    public static int getFeedbackCount(Context context) {
        if (context == null) {
            return 0;
        }
        try {
            String history = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_FEEDBACK_LOG, "[]");
            return new JSONArray(history).length();
        } catch (Exception e) {
            return 0;
        }
    }
}
