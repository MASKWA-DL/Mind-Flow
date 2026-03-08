package com.example.mindflow.service;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.mindflow.MainActivity;
import com.example.mindflow.R;
import com.example.mindflow.network.GlmApiService;
import com.example.mindflow.utils.ScreenCaptureDataHolder;
import com.example.mindflow.utils.ScreenCaptureManager;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 核心专注服务 - 整合计时、屏幕监控、分心检测
 */
public class FocusService extends Service {
    private static final String TAG = "FocusService";

    // 通知相关
    private static final String CHANNEL_ID = "MindFlow_Focus_Channel";
    private static final String CHANNEL_LOCK_ID = "MindFlow_Lock_Channel";
    private static final int NOTIFICATION_ID = 1001;

    // 广播 Action
    public static final String ACTION_TIMER_TICK = "com.example.mindflow.TIMER_TICK";
    public static final String ACTION_FOCUS_STATE_CHANGED = "com.example.mindflow.FOCUS_STATE_CHANGED";
    public static final String ACTION_AI_RESULT = "com.example.mindflow.AI_RESULT";
    public static final String ACTION_WARNING = "com.example.mindflow.WARNING";

    // 状态
    public enum FocusState {
        IDLE, FOCUSING, PAUSED, RESTING
    }

    private FocusState currentState = FocusState.IDLE;
    private long focusDurationMs = 25 * 60 * 1000L;
    private long remainingMs = 0;
    private long focusStartTime = 0;

    // 分心检测
    private DistractionManager distractionManager;
    private String currentAiVision = "未知";
    private String currentForegroundApp = "";
    private String focusGoal = "工作";

    // 白名单
    private Set<String> whitelist = new HashSet<>();

    // 线程调度
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(3);
    private ScheduledFuture<?> timerTask;
    private ScheduledFuture<?> screenAnalysisTask;
    private ScheduledFuture<?> watchdogTask;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 守护机制相关
    private long lastTimerTickTime = 0;
    private long lastAnalysisTime = 0;
    private int watchdogRestartCount = 0;

    // 屏幕捕获
    private MediaProjection mediaProjection;
    private boolean isScreenCaptureActive = false;
    private String screenCaptureStatus = "未启动";

    // === 新增：分心时间统计变量 ===
    private boolean isCurrentlyDistracted = false; // 当前是否处于分心状态
    private int distractionTimeSec = 0;            // 分心总秒数

    private final MediaProjection.Callback mediaProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.w(TAG, "⚠️ MediaProjection 已停止！");
            isScreenCaptureActive = false;
            screenCaptureStatus = "已断开";
            mainHandler.post(() -> broadcastScreenCaptureStatus());
        }
    };

    private final IBinder binder = new FocusBinder();

    public class FocusBinder extends Binder {
        public FocusService getService() {
            return FocusService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        distractionManager = new DistractionManager(this);
        loadWhitelist();
        registerScreenContentReceiver();
        registerLockAndScreenReceivers();
        Log.d(TAG, "FocusService 创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("START_FOCUS".equals(action)) {
                long duration = intent.getLongExtra("duration_ms", 25 * 60 * 1000L);
                startFocusSession(duration);
            } else if ("STOP_FOCUS".equals(action)) {
                stopFocusSession();
            } else if ("INIT_SCREEN_CAPTURE".equals(action)) {
                initScreenCapture();
            } else if ("RESTART_FROM_TASK_REMOVED".equals(action)) {
                Log.w(TAG, "🔄 服务被AlarmManager唤醒，尝试恢复状态");
                restoreServiceState();
            }
        } else {
            restoreServiceState();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopFocusSession();
        unregisterScreenContentReceiver();
        unregisterLockAndScreenReceivers();
        executor.shutdownNow();
        Log.d(TAG, "FocusService 销毁");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.w(TAG, "🚨 App被从最近任务移除，尝试自恢复...");
        if (currentState == FocusState.FOCUSING) {
            saveServiceState();
            scheduleServiceRestart();
            AppMonitorService monitorService = AppMonitorService.getInstance();
            if (monitorService != null && monitorService.isLockScreenActive()) {
                monitorService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            }
        }
    }

    private void saveServiceState() {
        SharedPreferences prefs = getSharedPreferences("FocusServiceState", MODE_PRIVATE);
        prefs.edit()
                .putString("state", currentState.name())
                .putLong("remaining_ms", remainingMs)
                .putLong("focus_duration_ms", focusDurationMs)
                .putString("focus_goal", focusGoal)
                .putLong("save_time", System.currentTimeMillis())
                .apply();
    }

    private void restoreServiceState() {
        SharedPreferences prefs = getSharedPreferences("FocusServiceState", MODE_PRIVATE);
        String stateStr = prefs.getString("state", "IDLE");
        long savedTime = prefs.getLong("save_time", 0);

        if (System.currentTimeMillis() - savedTime > 5 * 60 * 1000) {
            clearSavedState();
            return;
        }

        if ("FOCUSING".equals(stateStr)) {
            long remainingMs = prefs.getLong("remaining_ms", 0);
            long elapsed = System.currentTimeMillis() - savedTime;
            remainingMs = Math.max(0, remainingMs - elapsed);

            if (remainingMs > 0) {
                this.focusDurationMs = prefs.getLong("focus_duration_ms", 25 * 60 * 1000L);
                this.focusGoal = prefs.getString("focus_goal", "工作");
                startFocusSession(remainingMs);
            }
        }
        clearSavedState();
    }

    private void clearSavedState() {
        getSharedPreferences("FocusServiceState", MODE_PRIVATE).edit().clear().apply();
    }

    private void scheduleServiceRestart() {
        android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent restartIntent = new Intent(this, FocusService.class);
        restartIntent.setAction("RESTART_FROM_TASK_REMOVED");
        PendingIntent pendingIntent = PendingIntent.getService(
                this, 9999, restartIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
        );
        long triggerTime = System.currentTimeMillis() + 500;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
        } else {
            alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
        }
    }

    public void setFocusGoal(String goal) {
        if (goal != null && !goal.trim().isEmpty()) {
            this.focusGoal = goal.trim();
            GlmApiService.setFocusGoal(this.focusGoal);
        }
    }

    public String getFocusGoal() {
        return focusGoal;
    }

    public void startFocusSession(long durationMs) {
        if (currentState == FocusState.FOCUSING) return;

        GlmApiService.setFocusGoal(focusGoal);
        addSmartWhitelist(focusGoal);

        this.focusDurationMs = durationMs;
        this.remainingMs = durationMs;
        this.focusStartTime = System.currentTimeMillis();
        this.currentState = FocusState.FOCUSING;

        // === 新增：开始专注时，清零分心秒表和状态 ===
        this.distractionTimeSec = 0;
        this.isCurrentlyDistracted = false;

        distractionManager.reset();
        distractionManager.enableMonitoring();
        distractionManager.setSessionId("session_" + System.currentTimeMillis());
        distractionManager.setWhitelist(whitelist);

        GlmApiService.resetCancelState();

        if (com.example.mindflow.utils.PermissionHelper.enableDndMode(this)) {
            Log.i(TAG, "🔕 已自动开启勿扰模式");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createFocusNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, createFocusNotification());
        }

        if (timerTask != null) timerTask.cancel(false);
        if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);
        if (watchdogTask != null) watchdogTask.cancel(false);

        timerTask = executor.scheduleAtFixedRate(this::safeTimerTick, 0, 1, TimeUnit.SECONDS);
        screenAnalysisTask = executor.scheduleAtFixedRate(this::safeAnalyzeScreen, 5, 15, TimeUnit.SECONDS);
        watchdogTask = executor.scheduleAtFixedRate(this::watchdogCheck, 5, 5, TimeUnit.SECONDS);

        broadcastStateChange();
        broadcastInitialAiStatus();
    }

    public void stopFocusSession() {
        if (currentState == FocusState.IDLE) return;

        currentState = FocusState.IDLE;
        GlmApiService.cancelAllRequests();

        if (timerTask != null) { timerTask.cancel(true); timerTask = null; }
        if (screenAnalysisTask != null) { screenAnalysisTask.cancel(true); screenAnalysisTask = null; }
        if (watchdogTask != null) { watchdogTask.cancel(true); watchdogTask = null; }

        hideLockScreen();

        AppMonitorService service = AppMonitorService.getInstance();
        if (service != null) {
            service.deactivateLockScreen();
            service.disableLockMode();
            service.resetDistractionCount();
        }

        if (distractionManager != null) {
            distractionManager.stopAndReset();
            distractionManager.clearLockScreenCache();
        }

        NotificationManagerCompat.from(this).cancelAll();
        com.example.mindflow.utils.PermissionHelper.disableDndMode(this);
        stopScreenCapture();

        SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
        prefs.edit()
                .remove("current_session_minutes")
                .remove("current_distractions")
                .remove("current_distraction_history")
                .apply();

        stopForeground(true);
        broadcastStateChange();
    }

    public void pauseFocusSession() {
        if (currentState != FocusState.FOCUSING) return;
        currentState = FocusState.PAUSED;
        if (timerTask != null) timerTask.cancel(false);
        if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);
        broadcastStateChange();
    }

    public void resumeFocusSession() {
        if (currentState != FocusState.PAUSED) return;
        currentState = FocusState.FOCUSING;
        watchdogRestartCount = 0;
        timerTask = executor.scheduleAtFixedRate(this::safeTimerTick, 0, 1, TimeUnit.SECONDS);
        screenAnalysisTask = executor.scheduleAtFixedRate(this::safeAnalyzeScreen, 5, 15, TimeUnit.SECONDS);
        watchdogTask = executor.scheduleAtFixedRate(this::watchdogCheck, 5, 5, TimeUnit.SECONDS);
        broadcastStateChange();
    }

    private void safeTimerTick() {
        try {
            timerTick();
            lastTimerTickTime = System.currentTimeMillis();
        } catch (Exception e) {
            Log.e(TAG, "计时器任务异常: " + e.getMessage(), e);
        }
    }

    private void safeAnalyzeScreen() {
        if (currentState != FocusState.FOCUSING) return;
        try {
            analyzeScreen();
            lastAnalysisTime = System.currentTimeMillis();
        } catch (Exception e) {
            Log.e(TAG, "屏幕分析任务异常: " + e.getMessage(), e);
        }
    }

    private void watchdogCheck() {
        if (currentState != FocusState.FOCUSING) return;
        long now = System.currentTimeMillis();
        boolean needRestartTimer = false;
        boolean needRestartAnalysis = false;

        if (lastTimerTickTime > 0 && (now - lastTimerTickTime) > 3000) needRestartTimer = true;
        if (lastAnalysisTime > 0 && (now - lastAnalysisTime) > 30000) needRestartAnalysis = true;

        if (needRestartTimer && watchdogRestartCount < 10) {
            watchdogRestartCount++;
            if (timerTask != null) timerTask.cancel(false);
            timerTask = executor.scheduleAtFixedRate(this::safeTimerTick, 0, 1, TimeUnit.SECONDS);
            lastTimerTickTime = now;
        }

        if (needRestartAnalysis && watchdogRestartCount < 10) {
            watchdogRestartCount++;
            if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);
            GlmApiService.resetCancelState();
            screenAnalysisTask = executor.scheduleAtFixedRate(this::safeAnalyzeScreen, 1, 15, TimeUnit.SECONDS);
            lastAnalysisTime = now;
        }

        if (watchdogRestartCount > 0 && (now % 30000) < 3000) {
            watchdogRestartCount = Math.max(0, watchdogRestartCount - 1);
        }
    }

    private void timerTick() {
        if (currentState != FocusState.FOCUSING) return;

        remainingMs -= 1000;

        // === 新增：如果当前处于分心状态，分心秒数 +1 ===
        if (isCurrentlyDistracted) {
            distractionTimeSec++;
        }

        if (remainingMs <= 0) {
            remainingMs = 0;
            mainHandler.post(this::onFocusComplete);
            return;
        }

        long elapsedMs = focusDurationMs - remainingMs;
        if (elapsedMs % 10000 < 1000) {
            updateRealtimeStats();
        }

        mainHandler.post(() -> {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, createFocusNotification());
        });

        Intent intent = new Intent(ACTION_TIMER_TICK);
        intent.putExtra("remaining_ms", remainingMs);
        intent.putExtra("total_ms", focusDurationMs);
        intent.putExtra("warn_count", distractionManager != null ? distractionManager.getWarningCount() : 0);
        intent.putExtra("total_distractions", distractionManager != null ? distractionManager.getTotalDistractionCount() : 0);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void updateRealtimeStats() {
        long elapsedMs = focusDurationMs - remainingMs;
        int elapsedMinutes = (int) (elapsedMs / 60000);
        SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
        prefs.edit()
                .putInt("current_session_minutes", elapsedMinutes)
                .putInt("current_distractions", distractionManager.getTotalDistractionCount())
                .putString("current_distraction_history", distractionManager.getDistractionHistory())
                .apply();
    }

    private void onFocusComplete() {
        stopFocusSession();
    }

    private void broadcastInitialAiStatus() {
        Intent aiIntent = new Intent(ACTION_AI_RESULT);
        aiIntent.putExtra("vision", "AI 监控已启动");
        aiIntent.putExtra("activity", "正在分析中...");
        aiIntent.putExtra("is_focused", true);
        aiIntent.putExtra("goal", focusGoal);
        aiIntent.putExtra("current_app", currentForegroundApp.isEmpty() ? "检测中..." : currentForegroundApp);
        LocalBroadcastManager.getInstance(this).sendBroadcast(aiIntent);
    }

    private void initScreenCapture() {
        int resultCode = ScreenCaptureDataHolder.getResultCode();
        Intent resultData = ScreenCaptureDataHolder.getResultData();

        if (resultData != null && resultCode == Activity.RESULT_OK) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, createScreenCaptureNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                } else {
                    startForeground(NOTIFICATION_ID, createScreenCaptureNotification());
                }

                MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                mediaProjection = mpm.getMediaProjection(resultCode, resultData);

                if (mediaProjection != null) {
                    mediaProjection.registerCallback(mediaProjectionCallback, mainHandler);
                    ScreenCaptureManager.getInstance().setAiListener(this::onScreenCaptured);
                    ScreenCaptureManager.getInstance().start(this, mediaProjection);
                    isScreenCaptureActive = true;
                    screenCaptureStatus = "运行中";
                    broadcastScreenCaptureStatus();
                } else {
                    screenCaptureStatus = "获取失败";
                }
            } catch (Exception e) {
                screenCaptureStatus = "初始化失败: " + e.getMessage();
            }
        } else {
            screenCaptureStatus = "权限无效";
        }
    }

    private Notification createScreenCaptureNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🎯 MindFlow 屏幕监控中")
                .setContentText("AI 正在分析您的屏幕内容")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void stopScreenCapture() {
        if (isScreenCaptureActive) {
            ScreenCaptureManager.getInstance().stop();
            isScreenCaptureActive = false;
        }
        ScreenCaptureDataHolder.clear();
    }

    private void analyzeScreen() {
        if (currentState != FocusState.FOCUSING) return;
        if (isMonitoringPaused) return;

        if (AppMonitorService.isRunning()) {
            String screenContent = AppMonitorService.getInstance().getScreenContent();
            if (screenContent != null && screenContent.length() > 10) {
                analyzeScreenContent(screenContent, currentForegroundApp);
                return;
            }
        }

        if (isScreenCaptureActive) return;

        analyzeByPackageName(currentForegroundApp);
    }

    private void analyzeByPackageName(String packageName) {
        final String pkg = (packageName == null || packageName.isEmpty()) ? "未知应用" : packageName;
        boolean focused = true;
        final String activity = "使用 " + getAppNameFromPackage(pkg);

        String[] distractionApps = {
                "douyin", "tiktok", "bilibili", "weibo", "zhihu",
                "kuaishou", "xiaohongshu", "taobao", "jd.com", "pinduoduo",
                "tencent.mm", "qq.com", "game", "video", "music"
        };

        for (String app : distractionApps) {
            if (pkg.toLowerCase().contains(app)) {
                focused = false;
                break;
            }
        }
        if (whitelist.contains(pkg)) {
            focused = true;
        }

        final boolean isFocused = focused;
        // === 新增：同步分心状态 ===
        this.isCurrentlyDistracted = !isFocused;

        final String result = activity + " | " + (isFocused ? "是" : "否");

        mainHandler.post(() -> {
            distractionManager.analyzeAndCheck(result, pkg, whitelist);
            Intent aiIntent = new Intent(ACTION_AI_RESULT);
            aiIntent.putExtra("vision", result);
            aiIntent.putExtra("activity", activity);
            aiIntent.putExtra("is_focused", isFocused);
            aiIntent.putExtra("goal", focusGoal);
            aiIntent.putExtra("current_app", pkg);
            LocalBroadcastManager.getInstance(this).sendBroadcast(aiIntent);

            if (!isFocused) handleDistraction();
        });
    }

    private String getAppNameFromPackage(String packageName) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName.substring(packageName.lastIndexOf('.') + 1);
        }
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private void onScreenCaptured(Bitmap bitmap) {
        if (currentState != FocusState.FOCUSING || bitmap == null) return;
        if (isMonitoringPaused) return;

        String appName = getAppNameFromPackage(currentForegroundApp);
        GlmApiService.setCurrentAppName(appName);

        GlmApiService.analyzeImage(bitmap, new GlmApiService.AiCallback() {
            @Override
            public void onSuccess(String result) {
                if (currentState != FocusState.FOCUSING) return;
                currentAiVision = result;

                mainHandler.post(() -> {
                    if (currentState != FocusState.FOCUSING) return;

                    boolean isDistracted = distractionManager.analyzeAndCheck(result, currentForegroundApp, whitelist);
                    // === 新增：同步分心状态 ===
                    isCurrentlyDistracted = isDistracted;

                    Intent aiIntent = new Intent(ACTION_AI_RESULT);
                    aiIntent.putExtra("vision", result);
                    aiIntent.putExtra("activity", distractionManager.getLastAiActivity());
                    aiIntent.putExtra("is_focused", distractionManager.isLastAiFocused());
                    aiIntent.putExtra("goal", focusGoal);
                    aiIntent.putExtra("current_app", currentForegroundApp);
                    LocalBroadcastManager.getInstance(FocusService.this).sendBroadcast(aiIntent);

                    if (isDistracted) handleDistraction();
                });
            }

            @Override
            public void onFailure(String error) {
                if (currentState != FocusState.FOCUSING) return;
                mainHandler.post(() -> {
                    if (currentState != FocusState.FOCUSING) return;
                    Intent aiIntent = new Intent(ACTION_AI_RESULT);
                    aiIntent.putExtra("vision", "AI 分析失败: " + error);
                    aiIntent.putExtra("activity", "分析失败");
                    aiIntent.putExtra("is_focused", true);
                    aiIntent.putExtra("current_app", currentForegroundApp);
                    LocalBroadcastManager.getInstance(FocusService.this).sendBroadcast(aiIntent);
                });
            }
        });
    }

    private void handleDistraction() {
        if (currentState != FocusState.FOCUSING || isMonitoringPaused) return;
        DistractionManager.WarningLevel level = distractionManager.getWarningLevel();

        Intent intent = new Intent(ACTION_WARNING);
        intent.putExtra("level", level.ordinal());
        intent.putExtra("message", distractionManager.getWarningMessage());
        intent.putExtra("count", distractionManager.getWarningCount());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        if (level == DistractionManager.WarningLevel.LOCK) {
            triggerAppLock();
        }
    }

    private void triggerAppLock() {
        if (currentState != FocusState.FOCUSING) return;
        String distractionRecords = distractionManager.getLockScreenDistractionRecords();
        String reason = "分心次数过多，需要冷却\n深呼吸，离开屏幕休息一下。\n冷却后继续专注于: " + focusGoal;

        AppMonitorService monitorService = AppMonitorService.getInstance();
        if (monitorService != null) {
            monitorService.enableLockMode(whitelist, 60000L, reason, "", distractionManager.getWarningCount(), distractionRecords);
        }

        launchLockUI(60000L, reason);

        if (timerTask != null) timerTask.cancel(false);
        if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);
        isMonitoringPaused = true;
        // === 锁屏期间依然算作分心（或者你可以决定不算，这里我让你算作分心状态） ===
        isCurrentlyDistracted = true;

        distractionManager.onLockTriggered();
    }

    public void showLockOverlayNow() {
        if (isLockScreenShowing()) return;
        AppMonitorService service = AppMonitorService.getInstance();
        if (service != null && service.isLockScreenActive()) {
            launchLockUI(60000L, "检测到离开白名单应用\n请返回专注");
        }
    }

    private boolean isLockScreenShowing() {
        return LockWindowService.isActive() || com.example.mindflow.ui.lock.LockScreenActivity.isActive();
    }

    private void hideLockScreen() {
        try { stopService(new Intent(this, LockWindowService.class)); } catch (Exception e) {}
        com.example.mindflow.ui.lock.LockScreenActivity instance = com.example.mindflow.ui.lock.LockScreenActivity.getInstance();
        if (instance != null) instance.forceEnd();
    }

    private void launchLockUI(long durationMs, String reason) {
        try {
            boolean canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
            if (canDraw) {
                Intent intent = new Intent(this, LockWindowService.class);
                intent.putExtra("duration", durationMs);
                intent.putExtra("reason", reason);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
                else startService(intent);
                return;
            }
        } catch (Exception e) {}
        launchLockScreenActivity(durationMs, reason);
    }

    private void launchLockScreenActivity(long durationMs, String reason) {
        try {
            Intent intent = new Intent(this, com.example.mindflow.ui.lock.LockScreenActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("duration", durationMs);
            intent.putExtra("reason", reason);
            startActivity(intent);
        } catch (Exception e) {}
    }

    private void onLockScreenEnd() {
        AppMonitorService monitorService = AppMonitorService.getInstance();
        if (monitorService != null) monitorService.deactivateLockScreen();

        if (distractionManager != null) {
            distractionManager.resetDistractionCount();
            distractionManager.setLocked(false);
            distractionManager.clearLockScreenCache();
        }

        isMonitoringPaused = false;
        // === 解锁后，默认不再分心 ===
        isCurrentlyDistracted = false;

        if (currentState == FocusState.FOCUSING) {
            if (timerTask != null) timerTask.cancel(false);
            if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);
            timerTask = executor.scheduleAtFixedRate(this::safeTimerTick, 0, 1, TimeUnit.SECONDS);
            screenAnalysisTask = executor.scheduleAtFixedRate(this::safeAnalyzeScreen, 5, 15, TimeUnit.SECONDS);
        }
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID + 100);
        broadcastStateChange();
    }

    private BroadcastReceiver screenContentReceiver;

    private void registerScreenContentReceiver() {
        screenContentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (currentState != FocusState.FOCUSING || isMonitoringPaused) return;
                String content = intent.getStringExtra(AppMonitorService.EXTRA_CONTENT);
                String packageName = intent.getStringExtra(AppMonitorService.EXTRA_PACKAGE);
                if (content != null && !content.isEmpty()) {
                    analyzeScreenContent(content, packageName);
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(screenContentReceiver, new IntentFilter(AppMonitorService.ACTION_SCREEN_CONTENT));
    }

    private void unregisterScreenContentReceiver() {
        if (screenContentReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(screenContentReceiver);
            screenContentReceiver = null;
        }
    }

    private BroadcastReceiver lockStateReceiver;
    private BroadcastReceiver unlockContinueReceiver;
    private BroadcastReceiver screenStateReceiver;
    private BroadcastReceiver showLockOverlayReceiver;
    private BroadcastReceiver triggerLockReceiver;
    private BroadcastReceiver lockEndedReceiver;
    private boolean isMonitoringPaused = false;

    private void registerLockAndScreenReceivers() {
        showLockOverlayReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                AppMonitorService service = AppMonitorService.getInstance();
                if (!isLockScreenShowing() && service != null && service.isLockScreenActive()) {
                    launchLockScreenActivity(60000L, "检测到离开白名单应用\n请返回专注");
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(showLockOverlayReceiver, new IntentFilter("com.example.mindflow.SHOW_LOCK_OVERLAY"));

        triggerLockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String reason = intent.getStringExtra("reason");
                String advice = intent.getStringExtra("advice");
                String fullReason = (reason != null ? reason : "分心次数过多") + "\n" + (advice != null ? advice : "请休息一下");
                AppMonitorService monitorService = AppMonitorService.getInstance();
                if (monitorService != null) monitorService.enableLockMode(whitelist, 60000L, fullReason, "", 0, "");
                launchLockUI(60000L, fullReason);
                if (timerTask != null) timerTask.cancel(false);
                if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);
                isMonitoringPaused = true;
                // 触发锁机，算作分心
                isCurrentlyDistracted = true;
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(triggerLockReceiver, new IntentFilter("com.example.mindflow.TRIGGER_LOCK"));

        lockEndedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) { onLockScreenEnd(); }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(lockEndedReceiver, new IntentFilter("com.example.mindflow.LOCK_ENDED"));

        lockStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean isLocked = intent.getBooleanExtra("is_locked", false);
                isMonitoringPaused = isLocked;
                if (isLocked) {
                    if (timerTask != null) timerTask.cancel(false);
                    if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);
                    isCurrentlyDistracted = true; // 锁定期间算分心
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(lockStateReceiver, new IntentFilter("com.example.mindflow.LOCK_SCREEN_STATE"));

        unlockContinueReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                distractionManager.resetDistractionCount();
                distractionManager.setLocked(false);
                isMonitoringPaused = false;
                isCurrentlyDistracted = false; // 恢复专注
                if (currentState == FocusState.FOCUSING) {
                    if (timerTask != null) timerTask.cancel(false);
                    if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);
                    timerTask = executor.scheduleAtFixedRate(FocusService.this::safeTimerTick, 0, 1, TimeUnit.SECONDS);
                    screenAnalysisTask = executor.scheduleAtFixedRate(FocusService.this::safeAnalyzeScreen, 5, 15, TimeUnit.SECONDS);
                }
                broadcastStateChange();
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(unlockContinueReceiver, new IntentFilter("com.example.mindflow.UNLOCK_AND_CONTINUE"));

        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    isMonitoringPaused = true;
                    // 息屏期间算作分心（可以根据产品需求调整，目前设为true会在息屏时增加分心秒数）
                    isCurrentlyDistracted = true;
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    isMonitoringPaused = false;
                    isCurrentlyDistracted = false;
                }
            }
        };
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenStateReceiver, screenFilter);
    }

    private void unregisterLockAndScreenReceivers() {
        if (showLockOverlayReceiver != null) LocalBroadcastManager.getInstance(this).unregisterReceiver(showLockOverlayReceiver);
        if (triggerLockReceiver != null) LocalBroadcastManager.getInstance(this).unregisterReceiver(triggerLockReceiver);
        if (lockEndedReceiver != null) LocalBroadcastManager.getInstance(this).unregisterReceiver(lockEndedReceiver);
        if (lockStateReceiver != null) LocalBroadcastManager.getInstance(this).unregisterReceiver(lockStateReceiver);
        if (unlockContinueReceiver != null) LocalBroadcastManager.getInstance(this).unregisterReceiver(unlockContinueReceiver);
        if (screenStateReceiver != null) { try { unregisterReceiver(screenStateReceiver); } catch (Exception e) {} }
    }

    private void analyzeScreenContent(String screenText, String packageName) {
        String appName = getAppNameFromPackage(packageName);
        boolean isInWhitelist = whitelist.contains(packageName);
        if (isInWhitelist) {
            String result = "{\"conclusion\":\"YES\",\"reason\":\"应用在白名单中\",\"behavior\":\"使用白名单应用\",\"confidence\":100}";
            mainHandler.post(() -> {
                distractionManager.analyzeAndCheck(result, packageName, whitelist);
                isCurrentlyDistracted = false; // 白名单，没分心
                Intent aiIntent = new Intent(ACTION_AI_RESULT);
                aiIntent.putExtra("vision", result);
                aiIntent.putExtra("activity", "使用白名单应用: " + appName);
                aiIntent.putExtra("is_focused", true);
                aiIntent.putExtra("goal", focusGoal);
                sendBroadcast(aiIntent);
            });
            return;
        }

        boolean isLauncher = packageName.contains("launcher") || packageName.contains("home") || packageName.contains("桌面") || appName.contains("桌面");
        boolean isSystemUi = packageName.contains("systemui") || packageName.contains("settings") || packageName.contains("inputmethod") || packageName.contains("keyboard");
        String truncatedScreen = screenText.length() > 300 ? screenText.substring(0, 300) : screenText;
        boolean hasCode = screenText.contains("public") || screenText.contains("private") || screenText.contains("function") || screenText.contains("class") || screenText.contains("import") || screenText.contains("return");
        boolean hasChat = screenText.contains("发送") || screenText.contains("消息") || screenText.contains("聊天") || screenText.contains("评论");

        String inputJson = "{\n" +
                "  \"task_goal\": \"" + escapeJson(focusGoal) + "\",\n" +
                "  \"current_app\": {\n" +
                "    \"name\": \"" + escapeJson(appName) + "\",\n" +
                "    \"package\": \"" + packageName + "\",\n" +
                "    \"is_launcher\": " + isLauncher + ",\n" +
                "    \"is_system_ui\": " + isSystemUi + ",\n" +
                "    \"is_in_whitelist\": " + isInWhitelist + "\n" +
                "  },\n" +
                "  \"screen_features\": {\n" +
                "    \"has_code\": " + hasCode + ",\n" +
                "    \"has_chat\": " + hasChat + ",\n" +
                "    \"text_preview\": \"" + escapeJson(truncatedScreen) + "\"\n" +
                "  }\n" +
                "}";

        String prompt = "你是专注力判断助手。根据以下JSON输入判断用户是否在专注任务。\n\n" +
                "【输入】\n" + inputJson + "\n\n" +
                "【判断规则（按优先级，宽松判断）】\n" +
                "1. is_in_whitelist=true → YES\n" +
                "2. is_system_ui=true → YES\n" +
                "3. is_launcher=true → YES（用户可能在切换应用）\n" +
                "4. 应用或内容与任务目标有任何关联 → YES\n" +
                "5. 只有完全无关且明显是娱乐（如游戏、短视频）→ NO\n\n" +
                "【重要】conclusion和reason必须一致！\n" +
                "- 如果conclusion=YES，reason必须说\"符合目标\"\n" +
                "- 如果conclusion=NO，reason必须说\"不符合目标\"\n\n" +
                "【输出格式】只返回JSON，不要其他文字：\n" +
                "{\"conclusion\":\"YES\",\"reason\":\"符合目标：用户任务是XX，当前在XX\",\"behavior\":\"当前行为\",\"confidence\":85}\n" +
                "或\n" +
                "{\"conclusion\":\"NO\",\"reason\":\"不符合目标：用户任务是XX，但当前在XX\",\"behavior\":\"当前行为\",\"confidence\":75}";

        GlmApiService.analyzeText(prompt, new GlmApiService.AiCallback() {
            @Override
            public void onSuccess(String result) {
                if (currentState != FocusState.FOCUSING) return;
                mainHandler.post(() -> {
                    if (currentState != FocusState.FOCUSING) return;
                    boolean isDistracted = distractionManager.analyzeAndCheck(result, packageName, whitelist);
                    // === 新增：同步分心状态 ===
                    isCurrentlyDistracted = isDistracted;

                    Intent aiIntent = new Intent(ACTION_AI_RESULT);
                    aiIntent.putExtra("vision", result);
                    aiIntent.putExtra("activity", distractionManager.getLastAiActivity());
                    aiIntent.putExtra("is_focused", distractionManager.isLastAiFocused());
                    aiIntent.putExtra("goal", focusGoal);
                    aiIntent.putExtra("current_app", packageName);
                    LocalBroadcastManager.getInstance(FocusService.this).sendBroadcast(aiIntent);
                    if (isDistracted) handleDistraction();
                });
            }

            @Override
            public void onFailure(String error) {
                if (currentState != FocusState.FOCUSING) return;
                mainHandler.post(() -> {
                    if (currentState != FocusState.FOCUSING) return;
                    Intent aiIntent = new Intent(ACTION_AI_RESULT);
                    aiIntent.putExtra("vision", "AI 分析失败: " + error);
                    aiIntent.putExtra("activity", "分析失败");
                    aiIntent.putExtra("is_focused", true);
                    aiIntent.putExtra("current_app", packageName);
                    LocalBroadcastManager.getInstance(FocusService.this).sendBroadcast(aiIntent);
                });
            }
        });
    }

    public void onForegroundAppChanged(String packageName) {
        this.currentForegroundApp = packageName;
    }

    private void loadWhitelist() {
        SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
        whitelist = new HashSet<>(prefs.getStringSet("whitelist", new HashSet<>()));
        whitelist = sanitizeWhitelist(whitelist);

        whitelist.add(getPackageName());
        whitelist.add("com.android.settings");
        whitelist.add("com.android.contacts");
        whitelist.add("com.android.mms");
        whitelist.add("com.android.messaging");
        whitelist.add("com.android.calendar");
        whitelist.add("com.android.deskclock");
        whitelist.add("com.google.android.deskclock");
        whitelist.add("com.huawei.contacts");
        whitelist.add("com.huawei.message");
        whitelist.add("com.huawei.calendar");
        whitelist.add("com.huawei.deskclock");
        whitelist.add("com.miui.contacts");
        whitelist.add("com.xiaomi.calendar");
        whitelist.add("com.android.deskclock");

        whitelist = sanitizeWhitelist(whitelist);
        prefs.edit().putStringSet("whitelist", new HashSet<>(whitelist)).apply();
    }

    public void updateWhitelist(Set<String> newWhitelist) {
        this.whitelist = sanitizeWhitelist(newWhitelist);
        whitelist.add(getPackageName());
        whitelist.add("com.android.settings");
        SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
        prefs.edit().putStringSet("whitelist", whitelist).apply();
    }

    private Set<String> sanitizeWhitelist(Set<String> input) {
        Set<String> out = new HashSet<>();
        if (input == null) return out;
        for (String pkg : input) {
            if (pkg == null) continue;
            String p = pkg.trim();
            if (p.isEmpty() || p.contains(":")) continue;
            if (p.equals(getPackageName())) {
                out.add(p);
                continue;
            }
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(p);
                if (launch != null) out.add(p);
            } catch (Exception ignored) {}
        }
        return out;
    }

    public Set<String> getWhitelist() { return new HashSet<>(whitelist); }

    private void addSmartWhitelist(String goal) {
        if (goal == null || goal.isEmpty()) return;
        String lowerGoal = goal.toLowerCase();

        if (lowerGoal.contains("微信") || lowerGoal.contains("wechat")) whitelist.add("com.tencent.mm");
        if (lowerGoal.contains("qq")) whitelist.add("com.tencent.mobileqq");
        if (lowerGoal.contains("钉钉") || lowerGoal.contains("dingtalk")) whitelist.add("com.alibaba.android.rimet");
        if (lowerGoal.contains("飞书") || lowerGoal.contains("lark")) whitelist.add("com.ss.android.lark");
        if (lowerGoal.contains("企业微信") || lowerGoal.contains("企微")) whitelist.add("com.tencent.wework");
        if (lowerGoal.contains("浏览器") || lowerGoal.contains("网页") || lowerGoal.contains("查资料")) {
            whitelist.add("com.android.chrome"); whitelist.add("com.huawei.browser");
            whitelist.add("com.miui.browser"); whitelist.add("com.vivo.browser"); whitelist.add("com.oppo.browser");
        }
        if (lowerGoal.contains("笔记") || lowerGoal.contains("notion") || lowerGoal.contains("备忘")) {
            whitelist.add("notion.id"); whitelist.add("com.evernote"); whitelist.add("com.miui.notes");
        }
        if (lowerGoal.contains("邮件") || lowerGoal.contains("邮箱") || lowerGoal.contains("email")) {
            whitelist.add("com.google.android.gm"); whitelist.add("com.netease.mail"); whitelist.add("com.tencent.androidqqmail");
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel focusChannel = new NotificationChannel(CHANNEL_ID, "专注模式", NotificationManager.IMPORTANCE_LOW);
            focusChannel.setDescription("显示专注计时状态");
            nm.createNotificationChannel(focusChannel);

            NotificationChannel lockChannel = new NotificationChannel(CHANNEL_LOCK_ID, "锁定提醒", NotificationManager.IMPORTANCE_HIGH);
            lockChannel.setDescription("分心锁定提醒");
            nm.createNotificationChannel(lockChannel);
        }
    }

    private Notification createFocusNotification() {
        int minutes = (int) (remainingMs / 60000);
        int seconds = (int) ((remainingMs % 60000) / 1000);
        String timeText = String.format("%02d:%02d", minutes, seconds);
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("专注中 - " + timeText)
                .setContentText("AI 正在守护您的专注状态")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void broadcastStateChange() {
        Intent intent = new Intent(ACTION_FOCUS_STATE_CHANGED);
        intent.putExtra("state", currentState.ordinal());
        intent.putExtra("remaining_ms", remainingMs);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastScreenCaptureStatus() {
        Intent intent = new Intent(ACTION_AI_RESULT);
        intent.putExtra("screen_capture_status", screenCaptureStatus);
        intent.putExtra("vision", "屏幕捕获: " + screenCaptureStatus);
        intent.putExtra("activity", isScreenCaptureActive ? "监控中" : "已停止");
        intent.putExtra("is_focused", true);
        intent.putExtra("current_app", currentForegroundApp);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public String getScreenCaptureStatus() { return screenCaptureStatus; }
    public FocusState getCurrentState() { return currentState; }
    public long getRemainingMs() { return remainingMs; }
    public long getFocusDurationMs() { return focusDurationMs; }
    public String getCurrentAiVision() { return currentAiVision; }
    public int getWarningCount() { return distractionManager.getWarningCount(); }

    // === 新增：提供分心秒数供外部调用 ===
    public int getDistractionTimeSec() { return distractionTimeSec; }
}