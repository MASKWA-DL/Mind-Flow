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
import com.example.mindflow.utils.FocusModePreferences;
import com.example.mindflow.utils.FocusGoalInterpreter;
import com.example.mindflow.utils.FocusLocalRuleEngine;
import com.example.mindflow.utils.PermissionHelper;
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
 *
 * 功能：
 * 1. 前台服务保活
 * 2. 番茄钟计时
 * 3. 实时屏幕理解（调用 AI 分析）
 * 4. 分心检测与渐进式警告
 * 5. 应用锁定触发
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
    private static final String PREFS_NAME = "MindFlowPrefs";
    private static final String KEY_WHITELIST = "whitelist";

    // 状态
    public enum FocusState {
        IDLE, FOCUSING, PAUSED, RESTING
    }

    private FocusState currentState = FocusState.IDLE;
    private long focusDurationMs = 25 * 60 * 1000L; // 默认25分钟
    private long remainingMs = 0;
    private long focusStartTime = 0;

    // 分心检测
    private DistractionManager distractionManager;
    private String currentAiVision = "未知";
    private String currentForegroundApp = "";
    private String focusGoal = "工作"; // 用户的专注目标
    private String lastPageUrl = "";
    private String lastPageDomain = "";
    private String lastPageTitle = "";
    private String lastSearchQuery = "";

    // userAllowedApps 只用于“允许使用/免锁机干预”；systemExemptApps 是系统必要应用；goalRelevantApps 仅作为 AI 上下文
    private final Set<String> userAllowedApps = new HashSet<>();
    private final Set<String> systemExemptApps = new HashSet<>();
    private final Set<String> goalRelevantApps = new HashSet<>();

    private static final long APP_SWITCH_GRACE_MS = 2500L;
    private static final long MIN_TEXT_ANALYSIS_INTERVAL_MS = 8000L;
    private static final long SAME_TEXT_ANALYSIS_COOLDOWN_MS = 20000L;
    private static final int MIN_SCREEN_TEXT_LENGTH = 12;
    private long lastForegroundSwitchTime = 0;
    private long lastTextAnalysisAt = 0;
    private String lastTextAnalysisKey = "";
    private boolean isTextAnalysisInFlight = false;
    private boolean isImageAnalysisInFlight = false;

    // 锁机使用LockScreenActivity实现

    // 线程调度
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(3);
    private ScheduledFuture<?> timerTask;
    private ScheduledFuture<?> screenAnalysisTask;
    private ScheduledFuture<?> watchdogTask;  // 守护任务
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 守护机制相关
    private long lastTimerTickTime = 0;  // 上次计时器心跳时间
    private long lastAnalysisTime = 0;   // 上次分析时间
    private int watchdogRestartCount = 0; // 重启次数

    // 屏幕捕获
    private MediaProjection mediaProjection;
    private boolean isScreenCaptureActive = false;
    private String screenCaptureStatus = "未启动";

    // MediaProjection 回调 - 监听断开
    private final MediaProjection.Callback mediaProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.w(TAG, "⚠️ MediaProjection 已停止！");
            isScreenCaptureActive = false;
            screenCaptureStatus = "已断开";
            mainHandler.post(() -> broadcastScreenCaptureStatus());
        }
    };

    // Binder
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
        // 锁机Activity无需初始化，直接通过Intent启动
        // 注册屏幕内容接收器
        registerScreenContentReceiver();
        // 注册锁屏/息屏接收器
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
                // 从最近任务移除后自恢复
                Log.w(TAG, "🔄 服务被AlarmManager唤醒，尝试恢复状态");
                restoreServiceState();
            }
        } else {
            // intent为null时也尝试恢复（系统重启服务的情况）
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

    /**
     * 当用户从最近任务划掉App时触发 - 自恢复机制
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.w(TAG, "🚨 App被从最近任务移除，尝试自恢复...");

        // 如果正在专注中，需要自恢复
        if (currentState == FocusState.FOCUSING) {
            // 保存当前状态到SharedPreferences
            saveServiceState();

            // 使用AlarmManager在短延时后重启服务
            scheduleServiceRestart();

            // 立即尝试拉回锁机界面（如果锁机激活中）
            AppMonitorService monitorService = AppMonitorService.getInstance();
            if (monitorService != null && monitorService.isLockScreenActive()) {
                // 通过AccessibilityService执行全局返回，关闭最近任务
                monitorService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            }
        }
    }

    /**
     * 保存服务状态用于恢复
     */
    private void saveServiceState() {
        SharedPreferences prefs = getSharedPreferences("FocusServiceState", MODE_PRIVATE);
        prefs.edit()
            .putString("state", currentState.name())
            .putLong("remaining_ms", remainingMs)
            .putLong("focus_duration_ms", focusDurationMs)
            .putString("focus_goal", focusGoal)
            .putLong("save_time", System.currentTimeMillis())
            .apply();
        Log.d(TAG, "服务状态已保存: state=" + currentState + ", remaining=" + remainingMs);
    }

    /**
     * 恢复服务状态
     */
    private void restoreServiceState() {
        SharedPreferences prefs = getSharedPreferences("FocusServiceState", MODE_PRIVATE);
        String stateStr = prefs.getString("state", "IDLE");
        long savedTime = prefs.getLong("save_time", 0);

        // 只有在5分钟内保存的状态才恢复
        if (System.currentTimeMillis() - savedTime > 5 * 60 * 1000) {
            Log.d(TAG, "保存的状态已过期，不恢复");
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

                Log.w(TAG, "🔄 恢复专注会话: remaining=" + remainingMs + "ms");
                startFocusSession(remainingMs);
            }
        }
        clearSavedState();
    }

    private void clearSavedState() {
        getSharedPreferences("FocusServiceState", MODE_PRIVATE)
            .edit().clear().apply();
    }

    /**
     * 使用AlarmManager调度服务重启
     */
    private void scheduleServiceRestart() {
        android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent restartIntent = new Intent(this, FocusService.class);
        restartIntent.setAction("RESTART_FROM_TASK_REMOVED");

        PendingIntent pendingIntent = PendingIntent.getService(
            this,
            9999,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
        );

        // 500ms后重启
        long triggerTime = System.currentTimeMillis() + 500;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
        } else {
            alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
        }
        Log.d(TAG, "已调度服务重启");
    }

    // ==================== 专注会话控制 ====================

    /**
     * 设置专注目标
     */
    public void setFocusGoal(String goal) {
        if (goal != null && !goal.trim().isEmpty()) {
            this.focusGoal = goal.trim();
            GlmApiService.setFocusGoal(this.focusGoal);
            rebuildGoalRelevantApps();
            if (distractionManager != null) {
                distractionManager.setInterventionExemptApps(getInterventionExemptApps());
            }
            Log.d(TAG, "专注目标设置为: " + this.focusGoal);
        }
    }

    public String getFocusGoal() {
        return focusGoal;
    }

    public void startFocusSession(long durationMs) {
        if (currentState == FocusState.FOCUSING) {
            Log.w(TAG, "已在专注中，忽略重复启动");
            return;
        }

        // 确保 AI 服务知道当前目标
        GlmApiService.setFocusGoal(focusGoal);

        rebuildGoalRelevantApps();
        resetAnalysisState();

        this.focusDurationMs = durationMs;
        this.remainingMs = durationMs;
        this.focusStartTime = System.currentTimeMillis();
        this.currentState = FocusState.FOCUSING;

        // 重置分心管理器并设置会话ID
        distractionManager.reset();
        distractionManager.enableMonitoring();  // 启用监控
        String sessionId = "session_" + System.currentTimeMillis();
        distractionManager.setSessionId(sessionId);

        // 传递“允许使用应用”给 DistractionManager（只影响是否干预，不代表算命中目标）
        distractionManager.setInterventionExemptApps(getInterventionExemptApps());

        // 【重要】重置AI请求取消状态
        GlmApiService.resetCancelState();

        FocusModePreferences.setFocusModeActive(this, true);
        FocusModePreferences.resetBlockedNotificationCount(this);

        // 没有通知访问权限时，退化到勿扰模式兜底。
        if (!PermissionHelper.hasNotificationListenerPermission(this)
                && PermissionHelper.enableDndMode(this)) {
            Log.i(TAG, "🔕 已自动开启勿扰模式");
        }

        // 启动前台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createFocusNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, createFocusNotification());
        }

        // 先取消之前可能存在的任务（防止2倍速）
        if (timerTask != null) timerTask.cancel(false);
        if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);
        if (watchdogTask != null) watchdogTask.cancel(false);

        // 启动计时器（每秒更新）
        timerTask = executor.scheduleAtFixedRate(this::safeTimerTick, 0, 1, TimeUnit.SECONDS);

        // 启动屏幕分析（延迟5秒后开始，每15秒分析一次）
        // 与分心提醒频率同步
        screenAnalysisTask = executor.scheduleAtFixedRate(this::safeAnalyzeScreen, 5, 15, TimeUnit.SECONDS);

        // 启动守护任务（每5秒检查一次）
        watchdogTask = executor.scheduleAtFixedRate(this::watchdogCheck, 5, 5, TimeUnit.SECONDS);

        broadcastStateChange();

        // 立即广播初始 AI 状态
        broadcastInitialAiStatus();

        Log.i(TAG, "专注会话开始，时长: " + (durationMs / 60000) + " 分钟，目标: " + focusGoal);
    }

    public void stopFocusSession() {
        if (currentState == FocusState.IDLE) return;

        // 【重要】立即设置状态为IDLE，阻止所有后续处理
        currentState = FocusState.IDLE;
        Log.i(TAG, "🛑 开始停止专注会话...");

        // 【重要】取消所有正在进行的AI请求
        GlmApiService.cancelAllRequests();
        Log.i(TAG, "🛑 已取消所有AI请求");

        // 停止任务（优先停止，防止继续触发AI分析）
        if (timerTask != null) {
            timerTask.cancel(true);  // 使用true强制中断
            timerTask = null;
        }
        if (screenAnalysisTask != null) {
            screenAnalysisTask.cancel(true);
            screenAnalysisTask = null;
        }
        if (watchdogTask != null) {
            watchdogTask.cancel(true);
            watchdogTask = null;
        }

        // 结束锁机（悬浮窗和Activity都关闭）
        hideLockScreen();
        Log.i(TAG, "🔓 锁机已结束");

        // 关闭锁机
        AppMonitorService service = AppMonitorService.getInstance();
        if (service != null) {
            service.deactivateLockScreen();
            service.disableLockMode();
            service.resetDistractionCount();
            Log.i(TAG, "� 锁机已关闭");
        }

        // 完全停止并重置DistractionManager（防止继续触发锁机）
        if (distractionManager != null) {
            distractionManager.stopAndReset();
            distractionManager.clearLockScreenCache();  // 清空锁机分心缓存
            Log.i(TAG, "🛑 DistractionManager已完全停止，分心缓存已清空");
        }

        FocusModePreferences.setFocusModeActive(this, false);

        // 取消所有通知（包括锁机警告通知）
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.cancelAll();
        Log.i(TAG, "🔕 所有通知已取消");

        // 关闭勿扰模式
        if (PermissionHelper.disableDndMode(this)) {
            Log.i(TAG, "🔔 已关闭勿扰模式");
        }

        // 停止屏幕捕获
        stopScreenCapture();

        // 清除当前会话的实时数据
        SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
        prefs.edit()
            .remove("current_session_minutes")
            .remove("current_distractions")
            .remove("current_blocked_notifications")
            .remove("current_distraction_history")
            .apply();

        // 停止前台服务
        stopForeground(true);

        goalRelevantApps.clear();
        resetAnalysisState();

        broadcastStateChange();
        Log.i(TAG, "✅ 专注会话已完全停止");
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
        watchdogRestartCount = 0;  // 重置守护重启计数
        timerTask = executor.scheduleAtFixedRate(this::safeTimerTick, 0, 1, TimeUnit.SECONDS);
        screenAnalysisTask = executor.scheduleAtFixedRate(this::safeAnalyzeScreen, 5, 15, TimeUnit.SECONDS);
        watchdogTask = executor.scheduleAtFixedRate(this::watchdogCheck, 5, 5, TimeUnit.SECONDS);
        broadcastStateChange();
    }

    // ==================== 守护机制 ====================

    /**
     * 安全包装的计时器任务
     */
    private void safeTimerTick() {
        try {
            timerTick();
            lastTimerTickTime = System.currentTimeMillis();
        } catch (Exception e) {
            Log.e(TAG, "计时器任务异常: " + e.getMessage(), e);
        }
    }

    /**
     * 安全包装的屏幕分析任务
     */
    private void safeAnalyzeScreen() {
        // 停止专注后立即停止分析
        if (currentState != FocusState.FOCUSING) {
            Log.d(TAG, "⏹️ 非专注状态，跳过屏幕分析");
            return;
        }
        try {
            analyzeScreen();
            lastAnalysisTime = System.currentTimeMillis();
        } catch (Exception e) {
            Log.e(TAG, "屏幕分析任务异常: " + e.getMessage(), e);
        }
    }

    /**
     * 守护检查 - 检测任务是否异常停止并自动恢复
     * 更积极的保活策略
     */
    private void watchdogCheck() {
        if (currentState != FocusState.FOCUSING) return;

        long now = System.currentTimeMillis();
        boolean needRestartTimer = false;
        boolean needRestartAnalysis = false;

        // 检查计时器任务（超过3秒没有心跳则认为异常）
        if (lastTimerTickTime > 0 && (now - lastTimerTickTime) > 3000) {
            Log.w(TAG, "⚠️ 守护检测: 计时器任务停止，尝试重启...");
            needRestartTimer = true;
        }

        // 检查分析任务（超过30秒没有心跳则认为异常，考虑AI响应时间和15秒间隔）
        if (lastAnalysisTime > 0 && (now - lastAnalysisTime) > 30000) {
            Log.w(TAG, "⚠️ 守护检测: 屏幕分析任务停止，尝试重启...");
            needRestartAnalysis = true;
        }

        // 分别重启各个任务（不要因为一个任务问题影响另一个）
        if (needRestartTimer && watchdogRestartCount < 10) {
            watchdogRestartCount++;
            Log.w(TAG, "🔄 守护机制恢复计时器 (第" + watchdogRestartCount + "次)");
            if (timerTask != null) timerTask.cancel(false);
            timerTask = executor.scheduleAtFixedRate(this::safeTimerTick, 0, 1, TimeUnit.SECONDS);
            lastTimerTickTime = now;
        }

        if (needRestartAnalysis && watchdogRestartCount < 10) {
            watchdogRestartCount++;
            Log.w(TAG, "🔄 守护机制恢复屏幕分析 (第" + watchdogRestartCount + "次)");
            if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);
            // 重置AI取消状态，确保能继续发送请求
            GlmApiService.resetCancelState();
            // 使用15秒间隔，与主任务一致
            screenAnalysisTask = executor.scheduleAtFixedRate(this::safeAnalyzeScreen, 1, 15, TimeUnit.SECONDS);
            lastAnalysisTime = now;
        }

        // 每30秒重置一次重启计数，避免长时间运行后无法重启
        if (watchdogRestartCount > 0 && (now % 30000) < 3000) {
            watchdogRestartCount = Math.max(0, watchdogRestartCount - 1);
        }
    }

    // ==================== 计时器 ====================

    private void timerTick() {
        if (currentState != FocusState.FOCUSING) return;

        remainingMs -= 1000;
        if (remainingMs <= 0) {
            remainingMs = 0;
            mainHandler.post(this::onFocusComplete);
            return;
        }

        // 每10秒更新一次实时数据到 SharedPreferences（供报告页面读取）
        long elapsedMs = focusDurationMs - remainingMs;
        if (elapsedMs % 10000 < 1000) {
            updateRealtimeStats();
        }

        // 更新通知
        mainHandler.post(() -> {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, createFocusNotification());
            }
        });

        // 广播计时更新
        Intent intent = new Intent(ACTION_TIMER_TICK);
        intent.putExtra("remaining_ms", remainingMs);
        intent.putExtra("total_ms", focusDurationMs);
        intent.putExtra("warn_count", distractionManager != null ? distractionManager.getWarningCount() : 0);
        intent.putExtra("total_distractions", distractionManager != null ? distractionManager.getTotalDistractionCount() : 0);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    /**
     * 更新实时统计数据到 SharedPreferences（供报告页面读取）
     */
    private void updateRealtimeStats() {
        long elapsedMs = focusDurationMs - remainingMs;
        int elapsedMinutes = (int) (elapsedMs / 60000);

        SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
        prefs.edit()
            .putInt("current_session_minutes", elapsedMinutes)
            .putInt("current_distractions", distractionManager.getTotalDistractionCount())
            .putInt("current_blocked_notifications", FocusModePreferences.getBlockedNotificationCount(this))
            .putString("current_distraction_history", distractionManager.getDistractionHistory())
            .apply();
    }

    private void onFocusComplete() {
        Log.i(TAG, "专注完成！");
        stopFocusSession();
    }

    /**
     * 广播初始 AI 状态，让 UI 立即更新
     */
    private void broadcastInitialAiStatus() {
        Intent aiIntent = new Intent(ACTION_AI_RESULT);
        aiIntent.putExtra("vision", "AI 监控已启动");
        aiIntent.putExtra("activity", "正在分析中...");
        aiIntent.putExtra("is_focused", true);
        aiIntent.putExtra("goal", focusGoal);
        aiIntent.putExtra("current_app", currentForegroundApp.isEmpty() ? "检测中..." : currentForegroundApp);
        LocalBroadcastManager.getInstance(this).sendBroadcast(aiIntent);
    }

    // ==================== 屏幕分析与分心检测 ====================

    private void initScreenCapture() {
        int resultCode = ScreenCaptureDataHolder.getResultCode();
        Intent resultData = ScreenCaptureDataHolder.getResultData();

        if (resultData != null && resultCode == Activity.RESULT_OK) {
            try {
                // ⚠️ 关键：必须先启动前台服务，才能获取 MediaProjection！
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, createScreenCaptureNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                } else {
                    startForeground(NOTIFICATION_ID, createScreenCaptureNotification());
                }
                Log.d(TAG, "✅ 前台服务已启动（屏幕捕获模式）");

                MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                mediaProjection = mpm.getMediaProjection(resultCode, resultData);

                if (mediaProjection != null) {
                    // 注册回调监听断开
                    mediaProjection.registerCallback(mediaProjectionCallback, mainHandler);

                    // 先设置监听器，再启动捕获
                    ScreenCaptureManager.getInstance().setAiListener(this::onScreenCaptured);
                    ScreenCaptureManager.getInstance().start(this, mediaProjection);
                    isScreenCaptureActive = true;
                    screenCaptureStatus = "运行中";
                    Log.d(TAG, "✅ 屏幕捕获初始化成功，系统图标应该显示");
                    broadcastScreenCaptureStatus();
                } else {
                    Log.e(TAG, "MediaProjection 为 null");
                    screenCaptureStatus = "获取失败";
                }
            } catch (Exception e) {
                Log.e(TAG, "屏幕捕获初始化失败", e);
                screenCaptureStatus = "初始化失败: " + e.getMessage();
            }
        } else {
            Log.e(TAG, "屏幕捕获权限数据无效: resultCode=" + resultCode);
            screenCaptureStatus = "权限无效";
        }
    }

    /**
     * 创建屏幕捕获专用通知
     */
    private Notification createScreenCaptureNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
            notificationIntent, PendingIntent.FLAG_IMMUTABLE);

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

        // 锁定页面或息屏时暂停监控
        if (isMonitoringPaused) {
            Log.d(TAG, "监控已暂停（锁定页面/息屏）");
            return;
        }

        // 方案1: 尝试从 AccessibilityService 获取屏幕内容
        if (AppMonitorService.isRunning()) {
            AppMonitorService monitorService = AppMonitorService.getInstance();
            String screenContent = monitorService.getScreenContent();
            if (screenContent != null && screenContent.length() > 10) {
                Log.d(TAG, "使用 AccessibilityService 获取屏幕内容");
                analyzeScreenContent(
                    screenContent,
                    currentForegroundApp,
                    monitorService.getLastPageUrl(),
                    monitorService.getLastPageDomain(),
                    monitorService.getLastPageTitle(),
                    monitorService.getLastSearchQuery()
                );
                return;
            }
        }

        // 方案2: 如果有截图，使用图片分析（由回调触发）
        if (isScreenCaptureActive) {
            Log.d(TAG, "等待屏幕截图...");
            return;
        }

        // 方案3: 只根据包名简单判断
        Log.d(TAG, "使用包名简单判断: " + currentForegroundApp);
        analyzeByPackageName(currentForegroundApp);
    }

    /**
     * 根据包名简单判断是否分心（备用方案）
     */
    private void analyzeByPackageName(String packageName) {
        final String pkg = (packageName == null || packageName.isEmpty()) ? "未知应用" : packageName;
        final Set<String> interventionExemptApps = getInterventionExemptApps();
        final String appName = getAppNameFromPackage(pkg);
        final String appCategory = FocusGoalInterpreter.classifyApp(pkg, appName);
        final boolean isSystemExemptApp = isSystemExemptApp(pkg);
        final boolean isUserAllowedApp = isUserAllowedApp(pkg);
        final boolean shouldFallbackToUnsure = FocusGoalInterpreter.shouldFallbackToUnsure(focusGoal, pkg, appName);

        boolean focused = isSystemExemptApp || FocusGoalInterpreter.isPackageLikelyRelevant(focusGoal, pkg, appName);
        final String activity = "使用 " + appName;

        final boolean isFocused = focused;
        final String reasonSuffix = !isFocused && isUserAllowedApp
            ? " 该应用被配置为“专注期间允许使用”，因此系统不会仅因打开它就直接锁机，但它仍不算命中当前目标。"
            : "";
        final String result;
        if (shouldFallbackToUnsure && !isSystemExemptApp) {
            result = "{\"conclusion\":\"UNSURE\",\"behavior\":\"" + escapeJson(activity) + "\",\"reason\":\"信息不足：当前只能确认应用类别为 " + escapeJson(appCategory) + "，但浏览器/未知应用需要结合页面内容才能稳定判断是否符合目标。\",\"evidence\":[\"仅有应用级信息，缺少页面文字或截图证据\",\"当前应用类别属于高歧义场景\"],\"confidence\":45,\"suggestion\":\"等待页面稳定后再判断，或切换到更明确的任务页面。\"}";
        } else if (isFocused) {
            result = "{\"conclusion\":\"YES\",\"behavior\":\"" + escapeJson(activity) + "\",\"reason\":\"" + escapeJson(FocusGoalInterpreter.buildFallbackReason(focusGoal, pkg, appName, true)) + "\",\"evidence\":[\"应用类别为 " + escapeJson(appCategory) + "\",\"当前无法读取更多页面文本，因此先按目标语义做保守匹配\"],\"confidence\":60,\"suggestion\":\"继续当前任务，若页面内容稳定后会进行更细的文字或截图分析。\"}";
        } else {
            result = "{\"conclusion\":\"NO\",\"behavior\":\"" + escapeJson(activity) + "\",\"reason\":\"" + escapeJson(FocusGoalInterpreter.buildFallbackReason(focusGoal, pkg, appName, false) + reasonSuffix) + "\",\"evidence\":[\"应用类别为 " + escapeJson(appCategory) + "\",\"该类别与当前目标语义不匹配\"],\"confidence\":70,\"suggestion\":\"切回与目标更相关的页面，或把目标写得更具体。\"}";
        }

        // 更新状态并广播
        mainHandler.post(() -> {
            boolean shouldIntervene = distractionManager.analyzeAndCheck(result, pkg, interventionExemptApps);

            Intent aiIntent = new Intent(ACTION_AI_RESULT);
            aiIntent.putExtra("vision", result);
            aiIntent.putExtra("activity", distractionManager.getLastAiActivity());
            aiIntent.putExtra("is_focused", distractionManager.isLastAiFocused());
            aiIntent.putExtra("goal", focusGoal);
            aiIntent.putExtra("current_app", pkg);
            LocalBroadcastManager.getInstance(this).sendBroadcast(aiIntent);

            if (shouldIntervene) {
                handleDistraction();
            }
        });
    }

    private String getAppNameFromPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "未知应用";
        }
        try {
            return getPackageManager().getApplicationLabel(
                getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName.substring(packageName.lastIndexOf('.') + 1);
        }
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    private String safeExtra(String value) {
        return value == null ? "" : value.trim();
    }

    private void onScreenCaptured(Bitmap bitmap) {
        if (currentState != FocusState.FOCUSING || bitmap == null) return;
        if (isMonitoringPaused) {
            Log.d(TAG, "监控已暂停（锁定页面/息屏），跳过截图分析");
            return;
        }
        if (System.currentTimeMillis() - lastForegroundSwitchTime < APP_SWITCH_GRACE_MS) {
            Log.d(TAG, "应用刚切换，跳过截图分析");
            return;
        }
        if (!tryAcquireImageAnalysisSlot()) {
            Log.d(TAG, "已有截图分析在进行中，跳过本次请求");
            return;
        }

        Log.d(TAG, "截图成功，正在发送给 AI 分析...");
        Set<String> interventionExemptApps = getInterventionExemptApps();

        // 将当前App名称传给AI，让AI结合App+屏幕内容综合判断
        String appName = getAppNameFromPackage(currentForegroundApp);
        GlmApiService.setCurrentAppName(appName);

        GlmApiService.analyzeImage(bitmap, new GlmApiService.AiCallback() {
            @Override
            public void onSuccess(String result) {
                releaseImageAnalysisSlot();
                if (currentState != FocusState.FOCUSING) {
                    Log.d(TAG, "⏹️ 专注已停止，忽略截图AI结果");
                    return;
                }
                currentAiVision = result;
                Log.d(TAG, "AI 分析结果: " + result);

                // 先进行分心检测（更新 DistractionManager 状态）
                mainHandler.post(() -> {
                    if (currentState != FocusState.FOCUSING) {
                        Log.d(TAG, "⏹️ 专注已停止，忽略截图AI结果（post阶段）");
                        return;
                    }
                    // 分析并检测分心
                    boolean isDistracted = distractionManager.analyzeAndCheck(result, currentForegroundApp, interventionExemptApps);

                    // 广播 AI 结果（包含详细信息）
                    Intent aiIntent = new Intent(ACTION_AI_RESULT);
                    aiIntent.putExtra("vision", result);
                    aiIntent.putExtra("activity", distractionManager.getLastAiActivity());
                    aiIntent.putExtra("is_focused", distractionManager.isLastAiFocused());
                    aiIntent.putExtra("goal", focusGoal);
                    aiIntent.putExtra("current_app", currentForegroundApp);
                    LocalBroadcastManager.getInstance(FocusService.this).sendBroadcast(aiIntent);

                    // 处理分心警告
                    if (isDistracted) {
                        handleDistraction();
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                releaseImageAnalysisSlot();
                if (currentState != FocusState.FOCUSING) {
                    Log.d(TAG, "⏹️ 专注已停止，忽略截图AI失败回调");
                    return;
                }
                Log.w(TAG, "AI 分析失败: " + error);
                // 广播失败状态
                mainHandler.post(() -> {
                    if (currentState != FocusState.FOCUSING) {
                        Log.d(TAG, "⏹️ 专注已停止，忽略截图AI失败回调（post阶段）");
                        return;
                    }
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
        if (currentState != FocusState.FOCUSING) {
            Log.d(TAG, "专注未进行中，忽略分心处理");
            return;
        }
        // 锁定页面期间不再触发警告
        if (isMonitoringPaused) {
            Log.d(TAG, "锁定中，跳过警告");
            return;
        }

        DistractionManager.WarningLevel level = distractionManager.getWarningLevel();

        Intent intent = new Intent(ACTION_WARNING);
        intent.putExtra("level", level.ordinal());
        intent.putExtra("message", distractionManager.getWarningMessage());
        intent.putExtra("count", distractionManager.getWarningCount());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        // 只处理锁定逻辑，警告通知由 DistractionManager 统一发送（避免重复通知）
        if (level == DistractionManager.WarningLevel.LOCK) {
            triggerAppLock();
        }
        // WARNING 级别的通知已由 DistractionManager.executeWarning() 处理
    }

    private void triggerAppLock() {
        if (currentState != FocusState.FOCUSING) {
            Log.d(TAG, "专注未进行中，禁止触发锁机");
            return;
        }
        Log.w(TAG, "触发应用锁定！");

        // 获取分心记录
        String distractionRecords = distractionManager.getLockScreenDistractionRecords();
        String reason = "分心次数过多，需要冷却\n深呼吸，离开屏幕休息一下。\n冷却后继续专注于: " + focusGoal;

        // 【关键】通知 AppMonitorService 激活锁机状态，并传递“允许使用应用”
        AppMonitorService monitorService = AppMonitorService.getInstance();
        if (monitorService != null) {
            monitorService.enableLockMode(getInterventionExemptApps(), 60000L, reason, "",
                distractionManager.getWarningCount(), distractionRecords);
        }

        // 启动锁机界面（优先悬浮窗，失败兜底 Activity）
        launchLockUI(60000L, reason);

        // 暂停计时器和屏幕分析
        if (timerTask != null) timerTask.cancel(false);
        if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);
        isMonitoringPaused = true;

        // 重置分心计数（但不清空历史，报告需要用）
        distractionManager.onLockTriggered();
    }

    /**
     * 直接显示锁机（由AppMonitorService调用）
     */
    public void showLockOverlayNow() {
        Log.w(TAG, "📢 showLockOverlayNow() 被调用！");

        // 检查锁机是否已显示（悬浮窗或Activity）
        if (isLockScreenShowing()) {
            Log.d(TAG, "🔒 锁机已显示，跳过");
            return;
        }

        // 检查AppMonitorService的锁机状态
        AppMonitorService service = AppMonitorService.getInstance();
        boolean serviceLockActive = service != null && service.isLockScreenActive();

        if (serviceLockActive) {
            String reason = "检测到离开允许使用应用\n请返回专注";
            launchLockUI(60000L, reason);
            Log.w(TAG, "🔒 重新启动锁机");
        } else {
            Log.w(TAG, "⚠️ 锁机未激活，跳过");
        }
    }

    /**
     * 检查锁机是否正在显示
     */
    private boolean isLockScreenShowing() {
        return LockWindowService.isActive() || com.example.mindflow.ui.lock.LockScreenActivity.isActive();
    }

    /**
     * 关闭锁机（通过广播通知LockScreenActivity结束）
     */
    private void hideLockScreen() {
        try {
            stopService(new Intent(this, LockWindowService.class));
        } catch (Exception e) {
            Log.e(TAG, "停止LockWindowService失败: " + e.getMessage());
        }

        com.example.mindflow.ui.lock.LockScreenActivity instance =
            com.example.mindflow.ui.lock.LockScreenActivity.getInstance();
        if (instance != null) {
            instance.forceEnd();
        }
    }

    private void launchLockUI(long durationMs, String reason) {
        try {
            boolean canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
            if (canDraw) {
                Intent intent = new Intent(this, LockWindowService.class);
                intent.putExtra("duration", durationMs);
                intent.putExtra("reason", reason);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
                Log.w(TAG, "✅ 已启动LockWindowService, 时长: " + durationMs + "ms");
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "启动LockWindowService失败: " + e.getMessage());
        }
        launchLockScreenActivity(durationMs, reason);
    }

    /**
     * 启动锁机Activity（纯粹启动UI，不设置状态）
     * 注意：调用此方法前必须先调用 enableLockMode() 设置允许使用应用
     */
    private void launchLockScreenActivity(long durationMs, String reason) {
        try {
            // 启动LockScreenActivity
            Intent intent = new Intent(this, com.example.mindflow.ui.lock.LockScreenActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("duration", durationMs);
            intent.putExtra("reason", reason);
            startActivity(intent);

            Log.w(TAG, "✅ 已启动LockScreenActivity, 时长: " + durationMs + "ms");
        } catch (Exception e) {
            Log.e(TAG, "启动LockScreenActivity失败: " + e.getMessage());
        }
    }


    /**
     * 锁机结束回调 - 清理所有后台进程和通知
     */
    private void onLockScreenEnd() {
        Log.i(TAG, "🔓 锁机结束，清理后台");

        // 停用锁机状态
        AppMonitorService monitorService = AppMonitorService.getInstance();
        if (monitorService != null) {
            monitorService.deactivateLockScreen();
        }

        // 【关键】重置分心次数（恢复3/3机会）
        if (distractionManager != null) {
            distractionManager.resetDistractionCount();
            distractionManager.setLocked(false);
            distractionManager.clearLockScreenCache();
            Log.i(TAG, "🔄 分心次数已重置为0");
        }

        // 恢复监控和计时
        isMonitoringPaused = false;
        if (currentState == FocusState.FOCUSING) {
            // 先取消之前的任务（防止2倍速bug）
            if (timerTask != null) timerTask.cancel(false);
            if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);

            timerTask = executor.scheduleAtFixedRate(this::safeTimerTick, 0, 1, TimeUnit.SECONDS);
            screenAnalysisTask = executor.scheduleAtFixedRate(this::safeAnalyzeScreen, 5, 15, TimeUnit.SECONDS);
            Log.i(TAG, "▶️ 锁机结束，计时器和监控已恢复");
        }

        // 取消所有锁机相关通知
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.cancel(NOTIFICATION_ID + 100); // 分心警告通知

        broadcastStateChange();
        Log.i(TAG, "✅ 锁机清理完成，恢复正常监控");
    }

    // ==================== 屏幕内容监听（AccessibilityService方式） ====================

    private BroadcastReceiver screenContentReceiver;

    private void registerScreenContentReceiver() {
        screenContentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (currentState != FocusState.FOCUSING) return;
                if (isMonitoringPaused) {
                    Log.d(TAG, "监控已暂停（锁定页面/息屏），跳过屏幕内容分析");
                    return;
                }

                String content = intent.getStringExtra(AppMonitorService.EXTRA_CONTENT);
                String packageName = intent.getStringExtra(AppMonitorService.EXTRA_PACKAGE);
                lastPageUrl = safeExtra(intent.getStringExtra(AppMonitorService.EXTRA_PAGE_URL));
                lastPageDomain = safeExtra(intent.getStringExtra(AppMonitorService.EXTRA_PAGE_DOMAIN));
                lastPageTitle = safeExtra(intent.getStringExtra(AppMonitorService.EXTRA_PAGE_TITLE));
                lastSearchQuery = safeExtra(intent.getStringExtra(AppMonitorService.EXTRA_SEARCH_QUERY));

                if (content != null && !content.isEmpty()) {
                    Log.d(TAG, "收到屏幕内容，长度: " + content.length());
                    // 使用屏幕文字内容进行 AI 分析
                    analyzeScreenContent(content, packageName, lastPageUrl, lastPageDomain, lastPageTitle, lastSearchQuery);
                }
            }
        };

        IntentFilter filter = new IntentFilter(AppMonitorService.ACTION_SCREEN_CONTENT);
        LocalBroadcastManager.getInstance(this).registerReceiver(screenContentReceiver, filter);
    }

    private void unregisterScreenContentReceiver() {
        if (screenContentReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(screenContentReceiver);
            screenContentReceiver = null;
        }
    }

    // ==================== 锁屏/息屏状态监听 ====================

    private BroadcastReceiver lockStateReceiver;
    private BroadcastReceiver unlockContinueReceiver;
    private BroadcastReceiver screenStateReceiver;
    private BroadcastReceiver showLockOverlayReceiver;  // 显示锁机遮罩的接收器
    private BroadcastReceiver triggerLockReceiver;      // 触发锁机的接收器
    private BroadcastReceiver lockEndedReceiver;        // 锁机结束接收器
    private boolean isMonitoringPaused = false;

    private void registerLockAndScreenReceivers() {
        // 显示锁机接收器（当检测到离开允许使用应用时由 AppMonitorService 发送）
        showLockOverlayReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.w(TAG, "📩 收到显示锁机广播！");
                AppMonitorService service = AppMonitorService.getInstance();
                boolean lockShowing = isLockScreenShowing();  // 检查悬浮窗和Activity
                boolean serviceLockActive = service != null && service.isLockScreenActive();

                Log.w(TAG, "📊 状态: lockShowing=" + lockShowing + ", serviceLockActive=" + serviceLockActive);

                // 只要锁机仍在进行中且未显示，就重新启动
                if (!lockShowing && serviceLockActive) {
                    String reason = "检测到离开允许使用应用\n请返回专注";
                    launchLockScreenActivity(60000L, reason);
                    Log.w(TAG, "🔒 重新启动锁机");
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(
            showLockOverlayReceiver, new IntentFilter("com.example.mindflow.SHOW_LOCK_OVERLAY"));

        // 触发锁机广播接收器（来自MainActivity等外部触发）
        triggerLockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String reason = intent.getStringExtra("reason");
                String advice = intent.getStringExtra("advice");
                Log.d(TAG, "📩 收到触发锁机广播: " + reason);

                // 直接触发锁机
                String fullReason = (reason != null ? reason : "分心次数过多") +
                    "\n" + (advice != null ? advice : "请休息一下");

                // 【关键】激活锁机状态并传递“允许使用应用”
                AppMonitorService monitorService = AppMonitorService.getInstance();
                if (monitorService != null) {
                    monitorService.enableLockMode(getInterventionExemptApps(), 60000L, fullReason, "", 0, "");
                }

                // 启动锁机界面（优先悬浮窗，失败兜底 Activity）
                launchLockUI(60000L, fullReason);

                // 暂停计时器和屏幕分析
                if (timerTask != null) timerTask.cancel(false);
                if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);
                isMonitoringPaused = true;
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(
            triggerLockReceiver, new IntentFilter("com.example.mindflow.TRIGGER_LOCK"));

        // 【新增】锁机结束接收器（从LockScreenActivity发送）
        lockEndedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "📩 收到锁机结束广播！");
                onLockScreenEnd();
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(
            lockEndedReceiver, new IntentFilter("com.example.mindflow.LOCK_ENDED"));

        // 锁定页面状态接收器
        lockStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean isLocked = intent.getBooleanExtra("is_locked", false);
                isMonitoringPaused = isLocked;
                if (isLocked) {
                    // 暂停计时器和屏幕分析
                    if (timerTask != null) timerTask.cancel(false);
                    if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);
                    Log.d(TAG, "⏸️ 锁定页面，暂停监控和计时");
                } else {
                    Log.d(TAG, "▶️ 退出锁定（等待用户点击恢复）");
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(
            lockStateReceiver, new IntentFilter("com.example.mindflow.LOCK_SCREEN_STATE"));

        // 解锁后继续监控接收器（用户点击返回后才触发）
        unlockContinueReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "🔓 锁机结束，恢复监控和计时");
                // 重置分心计数为0（恢复3/3机会）
                distractionManager.resetDistractionCount();
                distractionManager.setLocked(false);
                isMonitoringPaused = false;

                // 恢复计时器和屏幕分析（如果当前仍在专注状态）
                if (currentState == FocusState.FOCUSING) {
                    // 先取消之前的任务（防止2倍速bug）
                    if (timerTask != null) timerTask.cancel(false);
                    if (screenAnalysisTask != null) screenAnalysisTask.cancel(false);

                    timerTask = executor.scheduleAtFixedRate(FocusService.this::safeTimerTick, 0, 1, TimeUnit.SECONDS);
                    screenAnalysisTask = executor.scheduleAtFixedRate(FocusService.this::safeAnalyzeScreen, 5, 15, TimeUnit.SECONDS);
                    Log.i(TAG, "▶️ 计时器和监控已恢复(15秒间隔)");
                }

                broadcastStateChange();
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(
            unlockContinueReceiver, new IntentFilter("com.example.mindflow.UNLOCK_AND_CONTINUE"));

        // 系统息屏/亮屏接收器
        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    isMonitoringPaused = true;
                    Log.d(TAG, "📴 屏幕关闭，暂停监控");
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    Log.d(TAG, "📱 屏幕亮起，等待用户解锁后再恢复监控");
                } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    AppMonitorService monitorService = AppMonitorService.getInstance();
                    boolean lockActive = monitorService != null && monitorService.isLockScreenActive();
                    if (lockActive) {
                        Log.d(TAG, "🔒 用户已解锁，锁机仍有效，准备恢复锁机界面");
                        mainHandler.postDelayed(() -> {
                            AppMonitorService latestService = AppMonitorService.getInstance();
                            if (latestService == null || !latestService.isLockScreenActive()) {
                                isMonitoringPaused = false;
                                return;
                            }
                            showLockOverlayNow();
                        }, 300);
                    } else {
                        isMonitoringPaused = false;
                        Log.d(TAG, "🔓 用户已解锁，恢复监控");
                    }
                }
            }
        };
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenStateReceiver, screenFilter);
    }

    private void unregisterLockAndScreenReceivers() {
        if (showLockOverlayReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(showLockOverlayReceiver);
            showLockOverlayReceiver = null;
        }
        if (triggerLockReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(triggerLockReceiver);
            triggerLockReceiver = null;
        }
        if (lockEndedReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(lockEndedReceiver);
            lockEndedReceiver = null;
        }
        if (lockStateReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(lockStateReceiver);
            lockStateReceiver = null;
        }
        if (unlockContinueReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(unlockContinueReceiver);
            unlockContinueReceiver = null;
        }
        if (screenStateReceiver != null) {
            try {
                unregisterReceiver(screenStateReceiver);
            } catch (Exception e) {
                // 忽略
            }
            screenStateReceiver = null;
        }
    }

    /**
     * 使用屏幕文字内容进行 AI 分析
     *
     * AI判断输入来源：
     * 1. 用户设置的专注目标（focusGoal）
     * 2. AccessibilityService获取的当前应用包名（packageName）
     * 3. AccessibilityService获取的屏幕文字内容（screenText）
     */
    private void analyzeScreenContent(String screenText, String packageName,
                                      String pageUrl, String pageDomain,
                                      String pageTitle, String searchQuery) {
        if (!tryAcquireTextAnalysisSlot(screenText, packageName)) {
            return;
        }

        Set<String> interventionExemptApps = getInterventionExemptApps();
        String safePackageName = packageName == null ? "" : packageName;
        // 获取应用名称
        String appName = getAppNameFromPackage(safePackageName);
        FocusGoalInterpreter.GoalProfile goalProfile = FocusGoalInterpreter.analyzeGoal(focusGoal);
        String appCategory = FocusGoalInterpreter.classifyApp(safePackageName, appName);
        boolean isSystemExempt = isSystemExemptApp(safePackageName);
        boolean isUserAllowed = isUserAllowedApp(safePackageName);
        String safePageUrl = safeExtra(pageUrl);
        String safePageDomain = safeExtra(pageDomain);
        String safePageTitle = safeExtra(pageTitle);
        String safeSearchQuery = safeExtra(searchQuery);

        // 只有系统必要应用直接跳过 AI；用户显式允许应用仍需看内容，只是在干预时放行
        boolean isInHardWhitelist = isSystemExempt;
        boolean isGoalRelevantApp = goalRelevantApps.contains(safePackageName);
        if (isInHardWhitelist) {
            Log.d(TAG, "✅ 系统必要应用，跳过AI分析: " + appName);
            // 直接广播结果，不调用AI
            String result = "{\"conclusion\":\"YES\",\"behavior\":\"使用系统必要应用\",\"reason\":\"符合目标：该应用属于系统必要流程（如设置、时钟、日历等），本次不作为分心处理。\",\"evidence\":[\"应用命中系统硬豁免名单\"],\"confidence\":100,\"suggestion\":\"完成必要操作后继续当前任务。\"}";
            mainHandler.post(() -> {
                releaseTextAnalysisSlot();
                distractionManager.analyzeAndCheck(result, safePackageName, interventionExemptApps);
                Intent aiIntent = new Intent(ACTION_AI_RESULT);
                aiIntent.putExtra("vision", result);
                aiIntent.putExtra("activity", "使用系统必要应用: " + appName);
                aiIntent.putExtra("is_focused", true);
                aiIntent.putExtra("goal", focusGoal);
                aiIntent.putExtra("current_app", safePackageName);
                LocalBroadcastManager.getInstance(FocusService.this).sendBroadcast(aiIntent);
            });
            return;
        }

        // 判断是否为系统应用/桌面
        boolean isLauncher = safePackageName.contains("launcher") || safePackageName.contains("home") ||
                            safePackageName.contains("桌面") || appName.contains("桌面");
        boolean isSystemUi = safePackageName.contains("systemui") || safePackageName.contains("settings") ||
                            safePackageName.contains("inputmethod") || safePackageName.contains("keyboard");
        boolean appJustSwitched = System.currentTimeMillis() - lastForegroundSwitchTime < APP_SWITCH_GRACE_MS;

        // 提取屏幕特征
        String truncatedScreen = screenText.length() > 300 ?
            screenText.substring(0, 300) : screenText;
        boolean hasCode = screenText.contains("public") || screenText.contains("private") ||
                         screenText.contains("function") || screenText.contains("class") ||
                         screenText.contains("import") || screenText.contains("return");
        boolean hasChat = screenText.contains("发送") || screenText.contains("消息") ||
                         screenText.contains("聊天") || screenText.contains("评论");
        boolean hasMath = containsAny(screenText, "计算器", "总计", "结果", "函数", "平方", "根号", "sin", "cos", "tan");
        boolean hasDocument = containsAny(screenText, "文档", "word", "excel", "ppt", "markdown", "notion", "会议纪要");
        boolean hasLearning = containsAny(screenText, "课程", "论文", "题目", "背单词", "lecture", "readme", "stack overflow");
        boolean hasEntertainment = containsAny(screenText, "推荐", "直播", "短视频", "点赞", "评论区", "热搜", "for you");
        boolean hasShopping = containsAny(screenText, "购物车", "立即购买", "下单", "优惠券", "商品详情");

        FocusLocalRuleEngine.RuleAssessment localAssessment =
            FocusLocalRuleEngine.analyze(
                focusGoal,
                safePackageName,
                appName,
                screenText,
                safePageDomain,
                safePageTitle,
                safeSearchQuery
            );
        if (localAssessment != null) {
            String result = localAssessment.toJsonString();
            Log.d(TAG, "命中本地强规则，跳过AI: " + result);
            mainHandler.post(() -> {
                releaseTextAnalysisSlot();
                boolean isDistracted = distractionManager.analyzeAndCheck(result, safePackageName, interventionExemptApps);

                Intent aiIntent = new Intent(ACTION_AI_RESULT);
                aiIntent.putExtra("vision", result);
                aiIntent.putExtra("activity", distractionManager.getLastAiActivity());
                aiIntent.putExtra("is_focused", distractionManager.isLastAiFocused());
                aiIntent.putExtra("goal", focusGoal);
                aiIntent.putExtra("current_app", safePackageName);
                LocalBroadcastManager.getInstance(FocusService.this).sendBroadcast(aiIntent);

                if (isDistracted) {
                    handleDistraction();
                }
            });
            return;
        }

        // 构建JSON结构化输入
        String inputJson = "{\n" +
            "  \"task_goal\": \"" + escapeJson(focusGoal) + "\",\n" +
            "  \"goal_profile\": {\n" +
            "    \"goal_type\": \"" + escapeJson(goalProfile.goalType) + "\",\n" +
            "    \"scope\": \"" + escapeJson(goalProfile.scope) + "\",\n" +
            "    \"normalized_intent\": \"" + escapeJson(goalProfile.normalizedIntent) + "\",\n" +
            "    \"allowed_categories\": " + toJsonArray(goalProfile.allowedCategories) + ",\n" +
            "    \"discouraged_categories\": " + toJsonArray(goalProfile.discouragedCategories) + ",\n" +
            "    \"special_rule\": \"" + escapeJson(goalProfile.ruleSummary) + "\"\n" +
            "  },\n" +
            "  \"current_app\": {\n" +
            "    \"name\": \"" + escapeJson(appName) + "\",\n" +
            "    \"package\": \"" + safePackageName + "\",\n" +
            "    \"category\": \"" + escapeJson(appCategory) + "\",\n" +
            "    \"is_launcher\": " + isLauncher + ",\n" +
            "    \"is_system_ui\": " + isSystemUi + ",\n" +
            "    \"is_system_exempt\": " + isSystemExempt + ",\n" +
            "    \"is_user_allowed_app\": " + isUserAllowed + ",\n" +
            "    \"is_goal_relevant_app\": " + isGoalRelevantApp + "\n" +
            "  },\n" +
            "  \"page_context\": {\n" +
            "    \"page_url\": \"" + escapeJson(safePageUrl) + "\",\n" +
            "    \"page_domain\": \"" + escapeJson(safePageDomain) + "\",\n" +
            "    \"page_title\": \"" + escapeJson(safePageTitle) + "\",\n" +
            "    \"search_query\": \"" + escapeJson(safeSearchQuery) + "\"\n" +
            "  },\n" +
            "  \"recent_context\": {\n" +
            "    \"app_just_switched\": " + appJustSwitched + ",\n" +
            "    \"milliseconds_since_app_switch\": " + Math.max(0, System.currentTimeMillis() - lastForegroundSwitchTime) + "\n" +
            "  },\n" +
            "  \"screen_features\": {\n" +
            "    \"has_code\": " + hasCode + ",\n" +
            "    \"has_chat\": " + hasChat + ",\n" +
            "    \"has_math\": " + hasMath + ",\n" +
            "    \"has_document\": " + hasDocument + ",\n" +
            "    \"has_learning\": " + hasLearning + ",\n" +
            "    \"has_entertainment\": " + hasEntertainment + ",\n" +
            "    \"has_shopping\": " + hasShopping + ",\n" +
            "    \"text_preview\": \"" + escapeJson(truncatedScreen) + "\"\n" +
            "  }\n" +
            "}";

        // 构建AI提示词 - 对切应用/加载页走不确定态，避免一次误判直接记分
        String prompt = "你是专注力判断助手。根据以下JSON输入判断用户是否在专注任务。\n\n" +
                       "【输入】\n" + inputJson + "\n\n" +
                       "【判断规则（按优先级）】\n" +
                       "1. is_system_exempt=true → YES\n" +
                       "2. is_system_ui=true 或 is_launcher=true → YES\n" +
                       "3. app_just_switched=true、界面像加载页/权限页/通知页、证据不足 → UNSURE\n" +
                       "4. 应用或内容与任务目标明显相关 → YES\n" +
                       "5. 只有当前行为与任务明显无关，且是娱乐/社交闲逛/购物/纯聊天时 → NO\n" +
                       "6. is_user_allowed_app=true 只表示该应用被用户设为“专注期间允许使用”，不代表它自动命中目标；仍然要按页面内容和目标语义判断 YES/NO。\n" +
                       "7. 浏览器场景优先参考 page_domain/page_title/search_query；知识站点、搜索结果、文档标题通常更接近工作学习，购物/娱乐/社交站点通常更接近分心。\n" +
                       "8. 聊天场景要区分工作沟通和闲聊：需求、会议、文档、项目、作业、论文等偏 YES；吃饭、周末、开黑、追剧、闲聊寒暄等偏 NO。\n" +
                       "9. 不要把“玩手机/刷手机/休息”理解成任何手机操作都算命中目标；例如 goal_type=generic_leisure 且 current_app.category=utility_calc 时，通常应判 NO，除非目标明确提到计算或记账。\n\n" +
                       "【重要】conclusion和reason必须一致！\n" +
                       "- 如果conclusion=YES，reason必须说\"符合目标\"\n" +
                       "- 如果conclusion=NO，reason必须说\"不符合目标\"\n" +
                       "- 如果conclusion=UNSURE，reason必须说\"信息不足\"或\"正在切换/加载\"\n\n" +
                       "【reason要求】至少覆盖 4 点：任务目标、当前页面行为、关键证据、最终结论。\n" +
                       "【evidence要求】返回 1-3 条短证据。\n" +
                       "【suggestion要求】返回一句可执行建议。\n\n" +
                       "【输出格式】只返回JSON，不要其他文字：\n" +
                       "{\"conclusion\":\"YES\",\"behavior\":\"当前行为\",\"reason\":\"符合目标：用户任务是XX，当前页面在做XX，证据是XX，因此判断仍在任务内。\",\"evidence\":[\"证据1\",\"证据2\"],\"confidence\":85,\"suggestion\":\"继续当前任务。\"}\n" +
                       "或\n" +
                       "{\"conclusion\":\"NO\",\"behavior\":\"当前行为\",\"reason\":\"不符合目标：用户任务是XX，但当前页面在做XX，关键证据是XX，因此判断偏离目标。\",\"evidence\":[\"证据1\",\"证据2\"],\"confidence\":75,\"suggestion\":\"关闭当前页面并返回任务相关页面。\"}\n" +
                       "或\n" +
                       "{\"conclusion\":\"UNSURE\",\"behavior\":\"切换页面\",\"reason\":\"信息不足：刚切换应用/界面仍在加载，当前证据不足以稳定判断。\",\"evidence\":[\"页面内容很少\"],\"confidence\":40,\"suggestion\":\"等待页面稳定后再判断。\"}";

        GlmApiService.analyzeText(prompt, new GlmApiService.AiCallback() {
            @Override
            public void onSuccess(String result) {
                releaseTextAnalysisSlot();
                // 停止专注后忽略AI结果，不再处理
                if (currentState != FocusState.FOCUSING) {
                    Log.d(TAG, "⏹️ 专注已停止，忽略AI结果");
                    return;
                }

                Log.d(TAG, "AI 文字分析结果: " + result);

                mainHandler.post(() -> {
                    // 再次检查状态（防止并发）
                    if (currentState != FocusState.FOCUSING) {
                        Log.d(TAG, "⏹️ 专注已停止，忽略AI结果(主线程)");
                        return;
                    }

                    boolean isDistracted = distractionManager.analyzeAndCheck(result, safePackageName, interventionExemptApps);

                    // 广播 AI 结果
                    Intent aiIntent = new Intent(ACTION_AI_RESULT);
                    aiIntent.putExtra("vision", result);
                    aiIntent.putExtra("activity", distractionManager.getLastAiActivity());
                    aiIntent.putExtra("is_focused", distractionManager.isLastAiFocused());
                    aiIntent.putExtra("goal", focusGoal);
                    aiIntent.putExtra("current_app", safePackageName);
                    LocalBroadcastManager.getInstance(FocusService.this).sendBroadcast(aiIntent);

                    if (isDistracted) {
                        handleDistraction();
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                releaseTextAnalysisSlot();
                // 停止专注后忽略错误
                if (currentState != FocusState.FOCUSING) return;

                Log.w(TAG, "AI 文字分析失败: " + error);
                mainHandler.post(() -> {
                    if (currentState != FocusState.FOCUSING) return;

                    Intent aiIntent = new Intent(ACTION_AI_RESULT);
                    aiIntent.putExtra("vision", "AI 分析失败: " + error);
                    aiIntent.putExtra("activity", "分析失败");
                    aiIntent.putExtra("is_focused", true);
                    aiIntent.putExtra("current_app", safePackageName);
                    LocalBroadcastManager.getInstance(FocusService.this).sendBroadcast(aiIntent);
                });
            }
        });
    }

    // ==================== 前台应用监控 ====================

    public void onForegroundAppChanged(String packageName) {
        this.currentForegroundApp = packageName;
        this.lastForegroundSwitchTime = System.currentTimeMillis();
        Log.d(TAG, "前台应用切换: " + packageName);
    }

    // ==================== 允许使用应用管理 ====================

    private void loadWhitelist() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        systemExemptApps.clear();
        systemExemptApps.addAll(buildSystemExemptApps());

        userAllowedApps.clear();
        userAllowedApps.addAll(sanitizeUserAllowedApps(prefs.getStringSet(KEY_WHITELIST, new HashSet<>())));
        prefs.edit().putStringSet(KEY_WHITELIST, new HashSet<>(userAllowedApps)).apply();
    }

    public void updateWhitelist(Set<String> newWhitelist) {
        userAllowedApps.clear();
        userAllowedApps.addAll(sanitizeUserAllowedApps(newWhitelist));
        if (distractionManager != null) {
            distractionManager.setInterventionExemptApps(getInterventionExemptApps());
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putStringSet(KEY_WHITELIST, new HashSet<>(userAllowedApps)).apply();
    }

    private Set<String> sanitizeWhitelist(Set<String> input) {
        Set<String> out = new HashSet<>();
        if (input == null) return out;

        for (String pkg : input) {
            if (pkg == null) continue;
            String p = pkg.trim();
            if (p.isEmpty()) continue;
            // 过滤进程名形式：com.xxx:process
            if (p.contains(":")) continue;

            // 只允许可启动应用；本应用始终允许
            if (p.equals(getPackageName())) {
                out.add(p);
                continue;
            }
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(p);
                if (launch != null) {
                    out.add(p);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private Set<String> sanitizeUserAllowedApps(Set<String> input) {
        Set<String> cleaned = sanitizeWhitelist(input);
        cleaned.remove(getPackageName());
        cleaned.removeAll(buildSystemExemptApps());
        return cleaned;
    }

    private Set<String> buildSystemExemptApps() {
        Set<String> apps = new HashSet<>();
        apps.add(getPackageName());
        apps.add("com.android.settings");
        apps.add("com.android.calendar");
        apps.add("com.android.deskclock");
        apps.add("com.google.android.deskclock");
        apps.add("com.huawei.calendar");
        apps.add("com.huawei.deskclock");
        apps.add("com.xiaomi.calendar");
        return apps;
    }

    private boolean isSystemExemptApp(String packageName) {
        return packageName != null && !packageName.isEmpty() && systemExemptApps.contains(packageName);
    }

    private boolean isUserAllowedApp(String packageName) {
        return packageName != null && !packageName.isEmpty() && userAllowedApps.contains(packageName);
    }

    public Set<String> getWhitelist() {
        return new HashSet<>(userAllowedApps);
    }

    /**
     * 根据专注目标识别“可能相关”的应用，用于给 AI 提供上下文，但不自动视为命中目标
     */
    private void rebuildGoalRelevantApps() {
        goalRelevantApps.clear();
        goalRelevantApps.addAll(sanitizeWhitelist(buildGoalRelevantApps(focusGoal)));
    }

    private Set<String> buildGoalRelevantApps(String goal) {
        Set<String> smartApps = new HashSet<>();
        if (goal == null || goal.isEmpty()) return smartApps;

        String lowerGoal = goal.toLowerCase();

        // 微信相关
        if (lowerGoal.contains("微信") || lowerGoal.contains("wechat")) {
            smartApps.add("com.tencent.mm");
            Log.d(TAG, "任务相关应用: 添加微信");
        }

        // QQ相关
        if (lowerGoal.contains("qq")) {
            smartApps.add("com.tencent.mobileqq");
            Log.d(TAG, "任务相关应用: 添加QQ");
        }

        // 钉钉相关
        if (lowerGoal.contains("钉钉") || lowerGoal.contains("dingtalk")) {
            smartApps.add("com.alibaba.android.rimet");
            Log.d(TAG, "任务相关应用: 添加钉钉");
        }

        // 飞书相关
        if (lowerGoal.contains("飞书") || lowerGoal.contains("lark")) {
            smartApps.add("com.ss.android.lark");
            Log.d(TAG, "任务相关应用: 添加飞书");
        }

        // 企业微信
        if (lowerGoal.contains("企业微信") || lowerGoal.contains("企微")) {
            smartApps.add("com.tencent.wework");
            Log.d(TAG, "任务相关应用: 添加企业微信");
        }

        // 浏览器相关
        if (lowerGoal.contains("浏览器") || lowerGoal.contains("网页") || lowerGoal.contains("查资料")) {
            smartApps.add("com.android.chrome");
            smartApps.add("com.huawei.browser");
            smartApps.add("com.miui.browser");
            smartApps.add("com.vivo.browser");
            smartApps.add("com.oppo.browser");
            Log.d(TAG, "任务相关应用: 添加浏览器");
        }

        // 笔记相关
        if (lowerGoal.contains("笔记") || lowerGoal.contains("notion") || lowerGoal.contains("备忘")) {
            smartApps.add("notion.id");
            smartApps.add("com.evernote");
            smartApps.add("com.miui.notes");
            Log.d(TAG, "任务相关应用: 添加笔记应用");
        }

        // 邮件相关
        if (lowerGoal.contains("邮件") || lowerGoal.contains("邮箱") || lowerGoal.contains("email")) {
            smartApps.add("com.google.android.gm");
            smartApps.add("com.netease.mail");
            smartApps.add("com.tencent.androidqqmail");
            Log.d(TAG, "任务相关应用: 添加邮件应用");
        }

        return smartApps;
    }

    private Set<String> getInterventionExemptApps() {
        Set<String> allowed = new HashSet<>(systemExemptApps);
        allowed.addAll(userAllowedApps);
        return allowed;
    }

    private synchronized boolean tryAcquireTextAnalysisSlot(String screenText, String packageName) {
        if (screenText == null || packageName == null || packageName.isEmpty()) {
            return false;
        }
        if (isTextAnalysisInFlight) {
            Log.d(TAG, "已有文字分析在进行中，跳过本次请求");
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastForegroundSwitchTime < APP_SWITCH_GRACE_MS) {
            Log.d(TAG, "应用刚切换，先等待界面稳定再分析");
            return false;
        }
        String normalized = normalizeScreenText(screenText);
        if (normalized.length() < MIN_SCREEN_TEXT_LENGTH) {
            Log.d(TAG, "屏幕文本有效信息不足，跳过AI分析");
            return false;
        }
        String analysisKey = packageName + "|" + normalized;
        if (analysisKey.equals(lastTextAnalysisKey) && now - lastTextAnalysisAt < SAME_TEXT_ANALYSIS_COOLDOWN_MS) {
            Log.d(TAG, "同一页面近期已分析，跳过重复请求");
            return false;
        }
        if (now - lastTextAnalysisAt < MIN_TEXT_ANALYSIS_INTERVAL_MS) {
            Log.d(TAG, "距离上次文字分析过近，跳过本次请求");
            return false;
        }
        isTextAnalysisInFlight = true;
        lastTextAnalysisAt = now;
        lastTextAnalysisKey = analysisKey;
        return true;
    }

    private synchronized void releaseTextAnalysisSlot() {
        isTextAnalysisInFlight = false;
    }

    private synchronized boolean tryAcquireImageAnalysisSlot() {
        if (isImageAnalysisInFlight) {
            return false;
        }
        isImageAnalysisInFlight = true;
        return true;
    }

    private synchronized void releaseImageAnalysisSlot() {
        isImageAnalysisInFlight = false;
    }

    private void resetAnalysisState() {
        isTextAnalysisInFlight = false;
        isImageAnalysisInFlight = false;
        lastTextAnalysisAt = 0;
        lastTextAnalysisKey = "";
        lastForegroundSwitchTime = 0;
    }

    private String normalizeScreenText(String text) {
        if (text == null) return "";
        String normalized = text.replaceAll("\\s+", " ").trim().toLowerCase();
        if (normalized.length() > 180) {
            return normalized.substring(0, 180);
        }
        return normalized;
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String toJsonArray(String[] values) {
        if (values == null || values.length == 0) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append("\"").append(escapeJson(values[i])).append("\"");
        }
        builder.append("]");
        return builder.toString();
    }

    // ==================== 通知 ====================

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);

            // 专注通知渠道
            NotificationChannel focusChannel = new NotificationChannel(
                CHANNEL_ID, "专注模式", NotificationManager.IMPORTANCE_LOW);
            focusChannel.setDescription("显示专注计时状态");
            nm.createNotificationChannel(focusChannel);

            // 锁定通知渠道（高优先级）
            NotificationChannel lockChannel = new NotificationChannel(
                CHANNEL_LOCK_ID, "锁定提醒", NotificationManager.IMPORTANCE_HIGH);
            lockChannel.setDescription("分心锁定提醒");
            nm.createNotificationChannel(lockChannel);
        }
    }

    private Notification createFocusNotification() {
        int minutes = (int) (remainingMs / 60000);
        int seconds = (int) ((remainingMs % 60000) / 1000);
        String timeText = String.format("%02d:%02d", minutes, seconds);

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
            notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("专注中 - " + timeText)
            .setContentText("AI 正在守护您的专注状态")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }

    // ==================== 状态广播 ====================

    private void broadcastStateChange() {
        Intent intent = new Intent(ACTION_FOCUS_STATE_CHANGED);
        intent.putExtra("state", currentState.ordinal());
        intent.putExtra("remaining_ms", remainingMs);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    /**
     * 广播屏幕捕获状态
     */
    private void broadcastScreenCaptureStatus() {
        Intent intent = new Intent(ACTION_AI_RESULT);
        intent.putExtra("screen_capture_status", screenCaptureStatus);
        intent.putExtra("vision", "屏幕捕获: " + screenCaptureStatus);
        intent.putExtra("activity", isScreenCaptureActive ? "监控中" : "已停止");
        intent.putExtra("is_focused", true);
        intent.putExtra("current_app", currentForegroundApp);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public String getScreenCaptureStatus() {
        return screenCaptureStatus;
    }

    // ==================== Getter ====================

    public FocusState getCurrentState() { return currentState; }
    public long getRemainingMs() { return remainingMs; }
    public long getFocusDurationMs() { return focusDurationMs; }
    public String getCurrentAiVision() { return currentAiVision; }
    public int getWarningCount() { return distractionManager.getWarningCount(); }
}
