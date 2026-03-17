package com.example.mindflow;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.pm.PackageManager;
import android.media.RingtoneManager;
import android.media.MediaPlayer;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import android.view.accessibility.AccessibilityManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.example.mindflow.database.MindFlowDatabase;
import com.example.mindflow.model.*;
import com.example.mindflow.network.GlmApiService;
import com.example.mindflow.network.NetworkClient;
import com.example.mindflow.service.ScreenCaptureService;
import com.example.mindflow.service.AppMonitorService;
import com.example.mindflow.ui.setup.PermissionSetupActivity;
import com.example.mindflow.utils.AppSettings;
import com.example.mindflow.utils.FocusModePreferences;
import com.example.mindflow.utils.ScreenCaptureManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MindFlow_Audit";
    private static final String CHANNEL_ID = "MindFlow_Emergency_Lock";

    private static final String LOG_EYE = "👁️ [感知] ";
    private static final String LOG_BRAIN = "🧠 [AI决策] ";
    private static final String LOG_SYSTEM = "⚙️ [审查官] ";

    public enum State {
        IDLE, FOCUSING, RESTING
    }

    public State mCurrentState = State.IDLE;
    public boolean isFocusing = false;

    private long mFocusStartTime = 0;
    private long mTotalTaskTimeMs = 25 * 60 * 1000L;
    private boolean isAlarmInsuranceActive = false;
    private long mLastAlarmTimeMs = 0;
    private int mAlarmCountInSession = 0;
    private static long mLastSessionEndTimeMs = 0;
    private long mLastRestDurationMs = 30 * 60 * 1000L;
    private long mLastActionTime = 0;
    private long mActiveSessionId = -1;

    private long mDistractionTimeMs = 0;
    private long mMaxDistractionQuotaMs = 120000;
    private Set<String> mWhitelistPackages = new HashSet<>();
    private boolean mIsAppLocked = false;

    private final ScheduledExecutorService mExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> mSamplerHandle;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final List<String> mSessionLogs = Collections.synchronizedList(new ArrayList<>());
    private String mAiCurrentVision = "工作中";
    private final LinkedList<WindowData> mRealTimeBuffer = new LinkedList<>();

    private ActivityResultLauncher<Intent> mProjLauncher;
    private ActivityResultLauncher<String> mPermissionLauncher;
    private MediaPlayer mPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 【启动时清理】清除可能残留的锁机状态，防止黑屏
        clearStaleLockState();

        // 进入即引导：缺少核心权限时先进入权限引导页，避免用户自己摸索
        if (shouldLaunchPermissionSetup()) {
            startActivity(new Intent(this, PermissionSetupActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        createNotificationChannel();
        initLaunchers();
        if (mLastSessionEndTimeMs > 0)
            mLastRestDurationMs = System.currentTimeMillis() - mLastSessionEndTimeMs;
        
        // 每次启动清空AI识别日志和分心历史
        clearAllLogs();
        
        checkAllRequiredPermissions();
        loadWhitelist();
        initNavigation();
        initAiVisualEngine();
    }

    private boolean shouldLaunchPermissionSetup() {
        SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
        boolean setupComplete = prefs.getBoolean("setup_complete", false);

        boolean hasOverlay = Settings.canDrawOverlays(this);
        boolean hasAccessibility = isAccessibilityServiceEnabled();
        boolean hasUsageStats = hasUsageStatsPermission();

        // 任何一项缺失都进入引导页（即使之前完成过，权限可能被用户撤回）
        if (!hasOverlay || !hasAccessibility || !hasUsageStats) {
            // 如果是首次使用（未完成引导），直接引导
            if (!setupComplete) return true;
            // 已完成过引导但权限被撤回，也应主动引导
            return true;
        }

        // 核心权限都具备后，不需要再进入引导页
        return false;
    }
    
    private void clearAllLogs() {
        SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
        prefs.edit()
            .remove("ai_recognition_log")
            .remove("distraction_history")
            .apply();
        Log.d(TAG, "已清空AI日志和分心历史");
    }
    
    /**
     * 清除残留的锁机状态（防止启动时被锁导致黑屏）
     */
    private void clearStaleLockState() {
        // 清除 LockScreenActivity 持久化状态
        SharedPreferences lockPrefs = getSharedPreferences("lock_screen_state", MODE_PRIVATE);
        boolean wasActive = lockPrefs.getBoolean("is_active", false);
        long endTime = lockPrefs.getLong("end_time", 0);
        
        // 如果锁机状态已过期或残留，清除它
        if (wasActive && endTime < System.currentTimeMillis()) {
            lockPrefs.edit().clear().apply();
            Log.d(TAG, "已清除过期的锁机状态");
        }
        
        // 停止可能残留的 LockWindowService
        try {
            stopService(new Intent(this, com.example.mindflow.service.LockWindowService.class));
        } catch (Exception e) {
            // 忽略
        }
        
        // 通知 AppMonitorService 停用锁机（如果正在运行）
        com.example.mindflow.service.AppMonitorService service = 
            com.example.mindflow.service.AppMonitorService.getInstance();
        if (service != null && service.isLockScreenActive()) {
            service.disableLockMode();
            Log.d(TAG, "已停用 AppMonitorService 锁机模式");
        }
    }
    
    private void checkAndRemindPermissions() {
        SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
        boolean permissionSetupDone = prefs.getBoolean("permission_setup_completed", false);
        
        // 如果之前已完成设置，直接跳过权限引导（不再重复检查）
        if (permissionSetupDone) {
            Log.d(TAG, "权限设置已完成（本地缓存），跳过引导流程");
            return;
        }
        
        // 首次设置：检查核心权限是否都已授予
        boolean hasOverlay = Settings.canDrawOverlays(this);
        boolean hasAccessibility = isAccessibilityServiceEnabled();
        boolean hasUsageStats = hasUsageStatsPermission();
        
        // 依次检查并弹窗引导开启权限（按优先级）
        if (!hasOverlay) {
            showPermissionDialog(
                "悬浮窗权限",
                "需要悬浮窗权限来显示锁定界面，防止分心时退出。\n\n请找到MindFlow并开启权限。",
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())))
            );
        } else if (!hasAccessibility) {
            showPermissionDialog(
                "无障碍服务",
                "需要无障碍服务来监控应用切换和拦截返回/Home键。\n\n请往下滑找到【MindFlow】并开启。",
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            );
        } else if (!hasUsageStats) {
            showPermissionDialog(
                "使用情况访问",
                "需要此权限来检测当前前台应用。\n\n请找到MindFlow并开启权限。",
                () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            );
        } else if (!isIgnoringBatteryOptimizations()) {
            showPermissionDialog(
                "忽略电池优化",
                "需要此权限让应用在后台稳定运行，防止锁机被系统杀死。\n\n请点击「允许」。",
                this::requestIgnoreBatteryOptimizations
            );
        } else {
            // 所有核心权限已开启，标记完成并检查厂商特殊权限
            prefs.edit().putBoolean("permission_setup_completed", true).apply();
            Log.d(TAG, "权限设置已完成，已缓存状态");
            checkVendorPermissions();
        }
    }
    
    private void checkVendorPermissions() {
        String brand = android.os.Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        // 华为/鸿蒙、小米、OPPO、VIVO等需要额外的自启动权限
        if (brand.contains("huawei") || brand.contains("honor") || 
            brand.contains("xiaomi") || brand.contains("oppo") || 
            brand.contains("vivo") || brand.contains("oneplus")) {
            
            SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
            boolean hasShownAutoStart = prefs.getBoolean("has_shown_autostart_guide", false);
            
            if (!hasShownAutoStart) {
                new AlertDialog.Builder(this)
                    .setTitle("后台运行设置")
                    .setMessage("检测到您使用的是" + android.os.Build.MANUFACTURER + "手机。\n\n" +
                        "为确保锁机功能正常工作，建议开启：\n" +
                        "• 自启动权限\n" +
                        "• 后台运行权限\n" +
                        "• 锁屏后继续运行\n\n" +
                        "是否前往设置？")
                    .setPositiveButton("去设置", (d, w) -> {
                        com.example.mindflow.utils.PermissionHelper.openAutoStartSettings(this);
                    })
                    .setNegativeButton("已设置", (d, w) -> {
                        prefs.edit().putBoolean("has_shown_autostart_guide", true).apply();
                    })
                    .show();
            }
        }
    }
    
    private boolean isIgnoringBatteryOptimizations() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }
    
    @android.annotation.SuppressLint("BatteryLife")
    private void requestIgnoreBatteryOptimizations() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }
    
    private void showPermissionDialog(String title, String message, Runnable onGrant) {
        new AlertDialog.Builder(this)
            .setTitle("需要「" + title + "」")
            .setMessage(message)
            .setPositiveButton("立即开启", (d, w) -> onGrant.run())
            .setNegativeButton("稍后", null)
            .setCancelable(false)
            .show();
    }
    
    private boolean isAccessibilityServiceEnabled() {
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                String packageName = getPackageName();
                for (AccessibilityServiceInfo info : enabledServices) {
                    String id = info.getId();
                    if (id != null && id.contains(packageName) && id.contains("AppMonitorService")) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 兜底：部分机型只有字符串列表可用（且可能是简写形式 pkg/.Service）
        String enabledServices = Settings.Secure.getString(
            getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        return enabledServices.contains(getPackageName()) && enabledServices.contains("AppMonitorService");
    }
    
    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    // ✨ 核心修复：唤醒开关。只有手动点击回到主界面，审计员才准睁眼。
    @Override
    protected void onResume() {
        super.onResume();
        if (mIsAppLocked) {
            Log.i(TAG, LOG_SYSTEM + "检测到回到主App界面，正式唤醒审计员，并重置违规计时。");
            mIsAppLocked = false;
            mDistractionTimeMs = 0;
        }
    }

    private void initLaunchers() {
        mProjLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                com.example.mindflow.utils.ScreenCaptureDataHolder.setPermissionData(r.getResultCode(), r.getData());
                startService(new Intent(this, ScreenCaptureService.class));
                startFocusing();
            }
        });
        mPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isG -> {
        });
    }

    public void setSessionDuration(int mins) {
        this.mTotalTaskTimeMs = (long) mins * 60 * 1000;
    }

    public int getSessionDurationMinutes() {
        return (int) (mTotalTaskTimeMs / 60000);
    }

    // --- 🏗️ 审计数据泵 ---

    private void startFocusing() {
        mCurrentState = State.FOCUSING;
        isFocusing = true;
        mFocusStartTime = System.currentTimeMillis();
        mDistractionTimeMs = 0;
        mIsAppLocked = false;
        mRealTimeBuffer.clear();
        mSessionLogs.add("--- 审计启动 ---");
        startFocusSessionRecord();
        mSamplerHandle = mExecutor.scheduleAtFixedRate(this::samplerTick, 0, 1, TimeUnit.SECONDS);
    }

    private void samplerTick() {
        if (!isFocusing)
            return;

        // ✨ 核心修复：如果在锁定状态，彻底禁止任何采样和审计活动
        if (mIsAppLocked) {
            // 禁止采样，直到 onResume() 将其复位
            return;
        }

        WindowData currentSnap = new WindowData();
        currentSnap.window_start = System.currentTimeMillis();
        Calendar calendar = Calendar.getInstance();
        currentSnap.hour_of_day = calendar.get(Calendar.HOUR_OF_DAY);
        int dayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday=0 ... Sunday=6
        currentSnap.day_of_week = dayOfWeek;
        currentSnap.in_focus_session = isFocusing ? 1 : 0;
        currentSnap.session_type = isFocusing ? "work" : "rest";
        currentSnap.major_app_category = mapVisionToCategory(mAiCurrentVision);

        synchronized (mRealTimeBuffer) {
            mRealTimeBuffer.addLast(currentSnap);
            if (mRealTimeBuffer.size() > 12)
                mRealTimeBuffer.removeFirst();

            if (mRealTimeBuffer.size() == 12 && System.currentTimeMillis() % 10000 < 1000) {
                sendBufferToBackend(new ArrayList<>(mRealTimeBuffer));
            }
        }
    }

    private void sendBufferToBackend(List<WindowData> payload) {
        mExecutor.execute(() -> {
            if (AppSettings.useBackend(this)) {
                PredictionRequest request = new PredictionRequest(payload);
                NetworkClient.getService(this).predict(request).enqueue(new Callback<PredictionResponse>() {
                    @Override
                    public void onResponse(Call<PredictionResponse> call, Response<PredictionResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            handlePredictionResponse(response.body());
                        } else {
                            Log.w(TAG, "Backend response invalid, falling back to local inference");
                            handlePredictionResponse(buildLocalPrediction(payload));
                        }
                    }

                    @Override
                    public void onFailure(Call<PredictionResponse> call, Throwable t) {
                        Log.w(TAG, "Backend call failed, falling back to local inference", t);
                        handlePredictionResponse(buildLocalPrediction(payload));
                    }
                });
            } else {
                handlePredictionResponse(buildLocalPrediction(payload));
            }
        });
    }

    private PredictionResponse buildLocalPrediction(List<WindowData> payload) {
        com.example.mindflow.ai.InferenceEngine.PredictionResult result = com.example.mindflow.ai.InferenceEngine
                .getInstance(this).predict(payload);

        PredictionResponse resp = new PredictionResponse();
        resp.pred_state = result.state;
        resp.pred_state_id = mapStateToId(result.state);
        resp.interruptibility_score = result.interruptibility;
        resp.state_probs = new HashMap<>();
        resp.state_probs_id = new HashMap<>();

        PredictionResponse.ExplanationData explanation = new PredictionResponse.ExplanationData();
        explanation.summary = "本地推理结果：" + result.state + "，可打断性 " + String.format(Locale.CHINA, "%.2f", result.interruptibility);
        resp.explanation = explanation;
        return resp;
    }

    private void handlePredictionResponse(PredictionResponse resp) {
        if (resp == null) {
            return;
        }

        if (resp.pred_state_id == null || resp.pred_state_id.isEmpty()) {
            resp.pred_state_id = mapStateToId(resp.pred_state);
        }

        // Insert result to DB for history tracking
        try {
            LabelWindow label = new LabelWindow();
            label.windowId = java.util.UUID.randomUUID().toString();
            label.timestamp = System.currentTimeMillis();
            label.cognitiveState = resp.pred_state;
            label.interruptibilityScore = (float) resp.interruptibility_score;
            label.interruptibilityLabel = resp.interruptibility_score < 0.3 ? 1
                    : (resp.interruptibility_score > 0.7 ? 3 : 2);
            label.focusLabel = mapFocusLabel(resp.pred_state_id);

            MindFlowDatabase.getInstance(this).labelWindowDao().insert(label);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save inference result", e);
        }

        mMainHandler.post(() -> {
            Log.d(TAG, LOG_BRAIN + "AI 判定: " + resp.pred_state);
            handleHumanizedLogic(resp);
            performIntentAudit(resp);
        });
    }

    // --- 🔐 物理锁定逻辑 ---

    private void performIntentAudit(PredictionResponse resp) {
        String currentPkg = getTopPackageName();
        if (currentPkg.isEmpty() || mIsAppLocked)
            return;

        if (!mWhitelistPackages.contains(currentPkg)) {
            String stateId = resp.pred_state_id == null ? "unknown" : resp.pred_state_id;
            boolean isFocusState = "deep_focus".equals(stateId) || "light_focus".equals(stateId);
            boolean visionLooksWork = mAiCurrentVision.contains("工作") || mAiCurrentVision.contains("学习")
                    || mAiCurrentVision.contains("代码") || mAiCurrentVision.contains("文档");
            boolean shouldAccumulateDistraction = !isFocusState || !visionLooksWork;

            if (shouldAccumulateDistraction) {
                double beta = (resp.interruptibility_score < 0.4) ? 2.2 : 1.2;
                if (!isFocusState) {
                    beta += 0.8;
                }
                if (!visionLooksWork) {
                    beta += 0.6;
                }
                int sensitivity = FocusModePreferences.getAiAuditSensitivity(this); // 0=宽松,100=严格
                double sensitivityFactor = 0.7 + (sensitivity / 100.0) * 0.8; // 0.7~1.5
                mDistractionTimeMs += (long) (10000 * beta * sensitivityFactor);
                mMaxDistractionQuotaMs = (long) ((2.0 * 60 * 1000) / (1.0 + mAlarmCountInSession * 0.4));

                Log.w(TAG, LOG_SYSTEM + "分心计时: " + mDistractionTimeMs / 1000 + "s / " + mMaxDistractionQuotaMs / 1000
                        + "s");

                if (mDistractionTimeMs >= mMaxDistractionQuotaMs) {
                    mIsAppLocked = true; // ✨ 状态上锁
                    String advice = (resp.explanation != null) ? resp.explanation.summary : "视觉审计拦截。";
                    mMainHandler.post(() -> triggerHardLock("额度清零", advice));
                }
            } else {
                mDistractionTimeMs = Math.max(0, mDistractionTimeMs - 5000);
            }
        } else {
            mDistractionTimeMs = Math.max(0, mDistractionTimeMs - 20000);
        }
    }

    private void triggerHardLock(String reason, String advice) {
        Log.e(TAG, LOG_SYSTEM + ">>> 信用耗尽，执行物理强拉 <<<");
        try {
            if (mPlayer != null)
                mPlayer.release();
            mPlayer = MediaPlayer.create(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            if (mPlayer != null)
                mPlayer.start();
        } catch (Exception ignored) {
        }

        // 通过广播通知FocusService触发锁机Overlay（不再使用LockActivity）
        Intent lockIntent = new Intent("com.example.mindflow.TRIGGER_LOCK");
        lockIntent.putExtra("reason", reason);
        lockIntent.putExtra("advice", advice);
        LocalBroadcastManager.getInstance(this).sendBroadcast(lockIntent);
        
        // 同时激活AppMonitorService的锁机状态
        AppMonitorService service = AppMonitorService.getInstance();
        if (service != null) {
            service.activateLockScreen();
        }
        
        Log.i(TAG, "🔒 已发送锁机广播，由FocusService显示锁机Overlay");
    }

    // --- 🚨 动态建议闹钟 ---

    private void handleHumanizedLogic(PredictionResponse resp) {
        long now = System.currentTimeMillis();
        float progress = (float) (now - mFocusStartTime) / mTotalTaskTimeMs;
        if (!isAlarmInsuranceActive && progress >= 0.01f)
            isAlarmInsuranceActive = true;

        long currentCd = calculateHumanizedCd();
        if (now - mLastAlarmTimeMs < currentCd)
            return;

        if (isAlarmInsuranceActive && "high_stress".equals(resp.pred_state_id)) {
            mLastAlarmTimeMs = now;
            mAlarmCountInSession++;
            long dynamicRestMs = (long) (5 * 60 * 1000 * (1.0 + resp.interruptibility_score)
                    * Math.pow(1.1, mAlarmCountInSession));
            mMainHandler.post(() -> triggerUltimateAlarm(dynamicRestMs));
        }
    }

    private void triggerUltimateAlarm(long suggestedRestMs) {
        triggerVibrate();
        new AlertDialog.Builder(this).setTitle("🧠 AI 专注建议").setMessage("建议休息 " + (suggestedRestMs / 60000) + " 分钟")
                .setPositiveButton("接受", (d, w) -> {
                    if (mPlayer != null)
                        mPlayer.stop();
                    enterRestPhase(suggestedRestMs);
                })
                .setNegativeButton("忽略", (d, w) -> {
                    if (mPlayer != null)
                        mPlayer.stop();
                    mLastActionTime = System.currentTimeMillis();
                }).show();
    }

    // --- 📊 系统工具 ---

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSamplerHandle != null)
            mSamplerHandle.cancel(true);
        if (mExecutor != null)
            mExecutor.shutdownNow();
    }

    private void stopFocusSession() {
        isFocusing = false;
        mIsAppLocked = false;
        if (mSamplerHandle != null)
            mSamplerHandle.cancel(true);
        endFocusSessionRecord();
        stopService(new Intent(this, ScreenCaptureService.class));
    }

    private void startFocusSessionRecord() {
        mExecutor.execute(() -> {
            FocusSession session = new FocusSession();
            session.sessionId = UUID.randomUUID().toString();
            session.sessionType = "work";
            session.goalText = "专注会话";
            session.plannedMin = (int) (mTotalTaskTimeMs / 60000);
            session.actualMin = 0;
            session.startTs = System.currentTimeMillis();
            session.endTs = 0;
            session.distractionCount = 0;
            session.interventionCount = 0;
            session.selfFocusScore = 0;
            session.selfFatigueScore = 0;
            session.isActive = true;

            mActiveSessionId = MindFlowDatabase.getInstance(this).focusSessionDao().insert(session);
        });
    }

    private void endFocusSessionRecord() {
        long endTs = System.currentTimeMillis();
        long durationMin = Math.max(0, (endTs - mFocusStartTime) / 60000);
        long sessionId = mActiveSessionId;
        mActiveSessionId = -1;
        mExecutor.execute(() -> {
            if (sessionId <= 0) {
                return;
            }
            FocusSession session = MindFlowDatabase.getInstance(this).focusSessionDao().getSessionById(sessionId);
            if (session == null) {
                return;
            }
            session.endTs = endTs;
            session.actualMin = (int) durationMin;
            session.isActive = false;
            MindFlowDatabase.getInstance(this).focusSessionDao().update(session);
            mLastSessionEndTimeMs = endTs;
        });
    }

    private void initAiVisualEngine() {
        ScreenCaptureManager.getInstance().setAiListener(b -> {
            GlmApiService.analyzeImage(b, new GlmApiService.AiCallback() {
                @Override
                public void onSuccess(String r) {
                    mAiCurrentVision = r;
                    Log.d(TAG, LOG_EYE + "视觉解释: [" + r + "]");
                }

                @Override
                public void onFailure(String e) {
                }
            });
        });
    }

    private String mapVisionToCategory(String vision) {
        if (vision == null) {
            return "other";
        }
        String v = vision.toLowerCase(Locale.ROOT);
        if (v.contains("代码") || v.contains("编程") || v.contains("写作") || v.contains("文档")
                || v.contains("工作") || v.contains("会议") || v.contains("邮箱") || v.contains("学习")) {
            return "work";
        }
        if (v.contains("微信") || v.contains("聊天") || v.contains("社交") || v.contains("微博")
                || v.contains("朋友圈") || v.contains("qq") || v.contains("telegram")) {
            return "social";
        }
        if (v.contains("视频") || v.contains("电影") || v.contains("抖音") || v.contains("快手")
                || v.contains("b站") || v.contains("游戏") || v.contains("音乐") || v.contains("娱乐")) {
            return "entertainment";
        }
        return "other";
    }

    private String mapStateToId(String stateZh) {
        if (stateZh == null) return "unknown";
        switch (stateZh) {
            case "深度专注":
                return "deep_focus";
            case "轻度专注":
                return "light_focus";
            case "休闲刷屏":
                return "casual_scrolling";
            case "高压忙乱":
                return "high_stress";
            case "放松休息":
                return "rest_relax";
            default:
                return "unknown";
        }
    }

    private int mapFocusLabel(String stateId) {
        if (stateId == null) {
            return 2;
        }
        switch (stateId) {
            case "deep_focus":
                return 3;
            case "light_focus":
                return 2;
            case "casual_scrolling":
            case "high_stress":
            case "rest_relax":
                return 1;
            default:
                return 2;
        }
    }

    private String getTopPackageName() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10000, now);
        if (stats != null && !stats.isEmpty()) {
            SortedMap<Long, UsageStats> mySortedMap = new TreeMap<>();
            for (UsageStats s : stats)
                mySortedMap.put(s.getLastTimeUsed(), s);
            return Objects.requireNonNull(mySortedMap.get(mySortedMap.lastKey())).getPackageName();
        }
        return "";
    }

    private void loadWhitelist() {
        SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
        mWhitelistPackages = new HashSet<>(prefs.getStringSet("whitelist", new HashSet<>()));
        mWhitelistPackages.add(getPackageName());
        mWhitelistPackages.add("com.android.settings");
    }

    private void checkAllRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                mPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "锁定系统",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void triggerVibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null)
            v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private long calculateHumanizedCd() {
        double alpha = Math.pow(1.6, mAlarmCountInSession);
        double beta = 30.0 / Math.max(1, (mTotalTaskTimeMs / 60000.0));
        double gamma = 1.0 + (Math.log1p(mLastRestDurationMs / 60000.0) / 5.0);
        return Math.min((long) (60000.0 * alpha * beta * gamma), 15 * 60 * 1000L);
    }

    private void enterRestPhase(long duration) {
        mCurrentState = State.RESTING;
        mIsAppLocked = false;
        mExecutor.schedule(() -> {
            mCurrentState = State.FOCUSING;
        }, duration, TimeUnit.MILLISECONDS);
    }

    public void toggleFocus() {
        if (!isFocusing)
            mProjLauncher.launch(((MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE))
                    .createScreenCaptureIntent());
        else
            stopFocusSession();
    }

    private void initNavigation() {
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        if (host != null && bottomNav != null) {
            NavigationUI.setupWithNavController(bottomNav, host.getNavController());
        }
    }
}
