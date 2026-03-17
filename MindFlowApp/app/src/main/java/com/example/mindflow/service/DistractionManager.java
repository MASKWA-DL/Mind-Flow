package com.example.mindflow.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.mindflow.MainActivity;
import com.example.mindflow.R;
import com.example.mindflow.database.MindFlowDatabase;
import com.example.mindflow.database.dao.InterventionDao;
import com.example.mindflow.model.Intervention;
import com.example.mindflow.utils.FocusModePreferences;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * 分心检测与渐进式警告管理器
 *
 * 警告级别（简化版）：
 * 1. NONE - 正常工作状态
 * 2. WARNING - 弹窗提醒 (1-2次分心)
 * 3. LOCK - 锁定应用 (3次以上)
 */
public class DistractionManager {
    private static final String TAG = "DistractionManager";

    public enum WarningLevel {
        NONE,      // 无警告
        WARNING,   // 弹窗提醒 (1-2次分心)
        LOCK       // 锁定 (3次以上)
    }

    // 阈值配置
    private static final int WARNING_THRESHOLD = 1;
    private static final int LOCK_THRESHOLD = 3;

    // 连续工作可减少警告计数的时间（秒）
    private static final int WORK_RECOVERY_SECONDS = 30;
    // 这些阈值会被设置页灵敏度动态映射覆盖（宽松->严格）。
    private static final long DEFAULT_DISTRACTION_DEBOUNCE_MS = 8000;
    private static final int DEFAULT_LOW_CONFIDENCE_THRESHOLD = 50;
    private static final int DEFAULT_MEDIUM_CONFIDENCE_THRESHOLD = 65;
    private static final int DEFAULT_HIGH_CONFIDENCE_THRESHOLD = 80;
    private static final int DEFAULT_DISTRACTION_TRIGGER_EVIDENCE = 55;
    private static final int DEFAULT_APP_SIGNAL_EVIDENCE = 30;
    private static final int DEFAULT_STRONG_FOCUS_RECOVERY = 25;
    private static final int DEFAULT_UNSURE_RECOVERY = 10;

    private enum DecisionStatus {
        FOCUSED,
        DISTRACTED,
        UNSURE
    }

    private static final class AiAssessment {
        DecisionStatus status = DecisionStatus.UNSURE;
        String behavior = "未知行为";
        String reason = "信息不足";
        int confidence = 40;
    }

    private final Context context;
    private int distractionCount = 0;
    private int totalDistractionCount = 0;
    private long lastDistractionTime = 0;
    private long lastWorkTime = 0;
    private int consecutiveWorkSeconds = 0;
    private int distractionEvidence = 0;
    private int consecutiveDistractedSignals = 0;

    private WarningLevel currentLevel = WarningLevel.NONE;
    private String lastWarningMessage = "";
    private String lastAiActivity = ""; // AI 识别的用户行为
    private boolean lastAiFocused = true; // AI 判断是否专注

    // 提醒冷却时间（毫秒）
    private static final long DEFAULT_WARNING_COOLDOWN_MS = 5000;
    private long lastWarningTime = 0;

    // 分心历史记录（从SharedPreferences加载）
    private final java.util.List<String> distractionHistoryList = new java.util.ArrayList<>();
    private static final String PREF_DISTRACTION_HISTORY = "distraction_history_list";

    // AI识别日志（调试用）
    private final java.util.List<String> aiRecognitionLog = new java.util.ArrayList<>();
    private static final String PREF_AI_RECOGNITION_LOG = "ai_recognition_log";

    // 锁定状态
    private boolean isLocked = false;

    // 监控是否启用（停止专注后设为false，防止继续触发锁机）
    private boolean isMonitoringEnabled = false;

    // 锁机页面分心记录缓存（只保存最近3条判断为"否"的记录）
    private final java.util.List<String> lockScreenDistractionCache = new java.util.ArrayList<>();
    private static final int MAX_LOCK_CACHE_SIZE = 3;

    // 系统应用白名单 - 这些应用永远不算分心（桌面、启动器、设置等）
    private static final String[] SYSTEM_WHITELIST = {
        "launcher", "home", "desktop", "桌面",  // 桌面启动器
        "systemui", "settings", "设置",  // 系统UI和设置
        "mindflow",  // 本应用
        "inputmethod", "keyboard", "输入法",  // 输入法
        "permissioncontroller", "packageinstaller"  // 权限管理
    };

    // 不应计为分心的AI识别关键词
    private static final String[] IGNORE_KEYWORDS = {
        "正在分析", "分析中", "加载中", "loading",
        "停留在应用桌面", "桌面", "主屏幕", "home",
        "锁屏", "解锁", "通知栏"
    };

    // 分心关键词
    private static final String[] DISTRACTION_KEYWORDS = {
        "视频", "抖音", "快手", "bilibili", "b站", "电影", "电视剧",
        "游戏", "王者", "吃鸡", "原神", "游戏中",
        "微博", "朋友圈", "刷",
        "小红书", "知乎闲逛", "娱乐",
        "聊天", "微信聊天", "qq聊天",
        "购物", "淘宝", "京东", "拼多多"
    };

    // 工作关键词
    private static final String[] WORK_KEYWORDS = {
        "代码", "编程", "写作", "文档", "word", "excel", "ppt",
        "工作", "会议", "邮件", "邮箱",
        "学习", "阅读", "笔记", "课程", "论文",
        "设计", "画图", "开发"
    };

    // 数据库
    private final InterventionDao interventionDao;
    private String currentSessionId = "";

    public DistractionManager(Context context) {
        this.context = context;
        this.interventionDao = MindFlowDatabase.getInstance(context).interventionDao();
        // 从SharedPreferences加载分心历史和AI日志
        loadDistractionHistory();
        loadAiRecognitionLog();
    }

    /**
     * 从SharedPreferences加载分心历史
     */
    private void loadDistractionHistory() {
        android.content.SharedPreferences prefs = context.getSharedPreferences("MindFlowPrefs", Context.MODE_PRIVATE);
        String historyJson = prefs.getString(PREF_DISTRACTION_HISTORY, "");
        if (!historyJson.isEmpty()) {
            String[] items = historyJson.split("\\|\\|\\|");
            for (String item : items) {
                if (!item.trim().isEmpty()) {
                    distractionHistoryList.add(item);
                }
            }
        }
    }

    /**
     * 保存分心历史到SharedPreferences（上限20条）
     */
    private void saveDistractionHistory() {
        android.content.SharedPreferences prefs = context.getSharedPreferences("MindFlowPrefs", Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        // 只保留最近20条，不到20条不清除
        int start = Math.max(0, distractionHistoryList.size() - 20);
        for (int i = start; i < distractionHistoryList.size(); i++) {
            if (sb.length() > 0) sb.append("|||");
            sb.append(distractionHistoryList.get(i));
        }
        prefs.edit().putString(PREF_DISTRACTION_HISTORY, sb.toString()).apply();
    }

    /**
     * 从SharedPreferences加载AI识别日志
     */
    private void loadAiRecognitionLog() {
        android.content.SharedPreferences prefs = context.getSharedPreferences("MindFlowPrefs", Context.MODE_PRIVATE);
        String logJson = prefs.getString(PREF_AI_RECOGNITION_LOG, "");
        if (!logJson.isEmpty()) {
            String[] items = logJson.split("\\|\\|\\|");
            for (String item : items) {
                if (!item.trim().isEmpty()) {
                    aiRecognitionLog.add(item);
                }
            }
        }
    }

    /**
     * 保存AI识别日志到SharedPreferences（上限50条）
     */
    private void saveAiRecognitionLog() {
        android.content.SharedPreferences prefs = context.getSharedPreferences("MindFlowPrefs", Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        // 只保留最近50条
        int start = Math.max(0, aiRecognitionLog.size() - 50);
        for (int i = start; i < aiRecognitionLog.size(); i++) {
            if (sb.length() > 0) sb.append("|||");
            sb.append(aiRecognitionLog.get(i));
        }
        prefs.edit().putString(PREF_AI_RECOGNITION_LOG, sb.toString()).apply();
    }

    /**
     * 记录AI识别结果（带时间戳，用于调试）
     */
    public void logAiRecognition(String aiResult, boolean isFocused) {
        String timestamp = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
        String logEntry = "[" + timestamp + "] " + (isFocused ? "✓" : "✗") + " " + aiResult;
        aiRecognitionLog.add(logEntry);
        saveAiRecognitionLog();
        Log.d(TAG, "AI识别日志: " + logEntry);
    }

    /**
     * 获取AI识别日志（全部50条，带时间戳）
     */
    public String getAiRecognitionLog() {
        if (aiRecognitionLog.isEmpty()) {
            return "暂无识别记录";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== AI识别日志 (共").append(aiRecognitionLog.size()).append("条) ===\n\n");
        // 返回全部日志（最多50条）
        for (int i = 0; i < aiRecognitionLog.size(); i++) {
            sb.append(aiRecognitionLog.get(i));
            if (i < aiRecognitionLog.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 获取AI识别日志条数
     */
    public int getAiRecognitionLogCount() {
        return aiRecognitionLog.size();
    }

    /**
     * 清空AI识别日志
     */
    public void clearAiRecognitionLog() {
        aiRecognitionLog.clear();
        saveAiRecognitionLog();
        Log.d(TAG, "AI识别日志已清空");
    }

    /**
     * 设置当前会话ID（用于记录干预事件）
     */
    public void setSessionId(String sessionId) {
        this.currentSessionId = sessionId;
    }

    /**
     * 分析当前状态并检测是否分心
     * @param aiVision AI 视觉理解结果
     * @param foregroundApp 当前前台应用包名
     * @param interventionExemptApps 允许使用、但不代表自动命中目标的应用
     * @return 是否处于分心状态
     */
    public boolean analyzeAndCheck(String aiVision, String foregroundApp, Set<String> interventionExemptApps) {
        long now = System.currentTimeMillis();
        final int lowConfidenceThreshold = getLowConfidenceThreshold();
        final int mediumConfidenceThreshold = getMediumConfidenceThreshold();
        final int highConfidenceThreshold = getHighConfidenceThreshold();
        final int distractionTriggerEvidence = getDistractionTriggerEvidence();
        final int appSignalEvidence = getAppSignalEvidence();
        final int strongFocusRecovery = getStrongFocusRecovery();
        final int unsureRecovery = getUnsureRecovery();
        final long warningCooldownMs = getWarningCooldownMs();
        final long distractionDebounceMs = getDistractionDebounceMs();
        final int sensitivity = getSensitivity();
        final boolean ultraStrictMode = sensitivity >= 80;
        final boolean extremeStrictMode = sensitivity >= 90;

        // 监控未启用时跳过（停止专注后）
        if (!isMonitoringEnabled) {
            Log.d(TAG, "监控未启用，跳过检测");
            return false;
        }

        // 锁定期间不进行监控
        if (isLocked) {
            Log.d(TAG, "应用已锁定，跳过监控");
            return false;
        }

        // 提醒冷却期内仍继续监控，仅抑制重复提醒，避免“判定到分心却不计数”。
        if (now - lastWarningTime < warningCooldownMs && lastWarningTime > 0) {
            Log.d(TAG, "提醒冷却期内，继续监控并计数");
        }

        // 缓冲期内不进行监控（开始专注后/锁机结束后的6秒缓冲）
        AppMonitorService service = AppMonitorService.getInstance();
        if (service != null && service.isInBufferPeriod()) {
            Log.d(TAG, "缓冲期内，跳过监控");
            return false;
        }

        AiAssessment assessment = parseAiAssessment(aiVision);
        lastAiActivity = buildActivityLabel(assessment.behavior, foregroundApp);
        boolean isInterventionExempt = isInterventionExemptApp(foregroundApp, interventionExemptApps);
        boolean isDistractedByApp = assessment.status != DecisionStatus.FOCUSED
                && checkAppDistraction(foregroundApp, interventionExemptApps);

        if (assessment.status == DecisionStatus.FOCUSED) {
            lastAiFocused = true;
            consecutiveDistractedSignals = 0;
            distractionEvidence = Math.max(0, distractionEvidence - strongFocusRecovery);
            logAiRecognition(aiVision, true);
            lastWorkTime = now;
            return false;
        }

        if (assessment.status == DecisionStatus.UNSURE && !isDistractedByApp && !extremeStrictMode) {
            lastAiFocused = true;
            consecutiveDistractedSignals = 0;
            distractionEvidence = Math.max(0, distractionEvidence - unsureRecovery);
            logAiRecognition(aiVision + " [不确定]", true);
            lastWorkTime = now;
            return false;
        }

        if (assessment.status == DecisionStatus.DISTRACTED
                && assessment.confidence < lowConfidenceThreshold
                && !isDistractedByApp
                && !ultraStrictMode) {
            lastAiFocused = true;
            consecutiveDistractedSignals = 0;
            distractionEvidence = Math.max(0, distractionEvidence - unsureRecovery);
            logAiRecognition(aiVision + " [低置信忽略]", true);
            lastWorkTime = now;
            return false;
        }

        if (assessment.status == DecisionStatus.DISTRACTED && isInterventionExempt) {
            // 允许使用应用命中偏离目标时仅记录日志，不在UI上标记为“分心”以免误解为会计分。
            lastAiFocused = true;
            consecutiveDistractedSignals = 0;
            distractionEvidence = Math.max(0, distractionEvidence - unsureRecovery);
            logAiRecognition(aiVision + " [允许使用应用，仅记录偏离目标不干预]", false);
            Log.i(TAG, "允许使用应用命中偏离目标，仅记录不干预: " + foregroundApp);
            return false;
        }

        boolean nonFocusedSignal = assessment.status == DecisionStatus.DISTRACTED
                || (extremeStrictMode && assessment.status == DecisionStatus.UNSURE);

        if (nonFocusedSignal) {
            consecutiveDistractedSignals++;
        } else {
            consecutiveDistractedSignals = 0;
        }

        int evidenceDelta = getEvidenceDelta(assessment.confidence, assessment.status);
        if (isDistractedByApp) {
            evidenceDelta += appSignalEvidence;
        }
        distractionEvidence = clamp(distractionEvidence + evidenceDelta, 0, 100);
        lastAiFocused = false;

        boolean directDistractedHit = nonFocusedSignal
            && (assessment.confidence >= mediumConfidenceThreshold
            || (isDistractedByApp && assessment.confidence >= lowConfidenceThreshold)
            || (ultraStrictMode && (isDistractedByApp || assessment.confidence >= 35))
            || extremeStrictMode);
        boolean strongDistracted = assessment.status == DecisionStatus.DISTRACTED
                && assessment.confidence >= highConfidenceThreshold;
        boolean repeatedMediumDistracted = assessment.status == DecisionStatus.DISTRACTED
                && assessment.confidence >= mediumConfidenceThreshold
                && consecutiveDistractedSignals >= 2;
        boolean evidenceEnough = distractionEvidence >= distractionTriggerEvidence;

        Log.d(TAG, "分心证据: status=" + assessment.status + ", confidence=" + assessment.confidence
                + ", appSignal=" + isDistractedByApp + ", consecutive=" + consecutiveDistractedSignals
                + ", evidence=" + distractionEvidence);

        if (!directDistractedHit && !strongDistracted && !repeatedMediumDistracted && !evidenceEnough) {
            logAiRecognition(aiVision + " [证据累计中]", false);
            return false;
        }

        if (now - lastDistractionTime < distractionDebounceMs) {
            Log.d(TAG, "分心事件防抖中，暂不重复记分");
            return false;
        }

        recordDistraction(now, foregroundApp, aiVision, assessment);
        return true;
    }

    private AiAssessment parseAiAssessment(String aiVision) {
        AiAssessment assessment = new AiAssessment();
        if (aiVision == null || aiVision.isEmpty()) {
            assessment.behavior = "信息不足";
            return assessment;
        }

        String visionLower = aiVision.toLowerCase(Locale.ROOT);
        for (String ignoreKw : IGNORE_KEYWORDS) {
            if (visionLower.contains(ignoreKw.toLowerCase(Locale.ROOT))) {
                assessment.status = DecisionStatus.FOCUSED;
                assessment.behavior = aiVision;
                assessment.reason = "系统过渡界面";
                assessment.confidence = 100;
                return assessment;
            }
        }

        String jsonPayload = extractJsonPayload(aiVision);
        if (jsonPayload.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(jsonPayload);
                String conclusion = json.optString("conclusion", "").trim().toUpperCase(Locale.ROOT);
                String behavior = json.optString("behavior", "").trim();
                String reason = json.optString("reason", "").trim();
                int confidence = parseConfidence(json);

                assessment.status = parseConclusion(conclusion, reason);
                assessment.behavior = behavior.isEmpty() ? "未知行为" : behavior;
                assessment.reason = reason.isEmpty() ? "未提供理由" : reason;
                assessment.confidence = confidence;

                boolean reasonSaysDistracted = containsAnyIgnoreCase(reason,
                    "不符合", "偏离目标", "无关", "与目标无关", "娱乐", "购物", "刷视频", "闲聊");
                boolean reasonSaysFocused = containsAnyIgnoreCase(reason,
                    "符合目标", "与目标一致", "高度相关", "正在执行任务", "与当前任务匹配");
                if ((assessment.status == DecisionStatus.FOCUSED && reasonSaysDistracted)
                        || (assessment.status == DecisionStatus.DISTRACTED && reasonSaysFocused)) {
                    Log.w(TAG, "AI JSON结论与原因矛盾，降级为不确定: " + jsonPayload);
                    assessment.status = DecisionStatus.UNSURE;
                    assessment.confidence = Math.min(assessment.confidence, 55);
                }

                Log.d(TAG, "AI JSON解析: conclusion=" + conclusion + ", behavior=" + assessment.behavior
                        + ", reason=" + assessment.reason + ", confidence=" + assessment.confidence);
                return assessment;
            } catch (Exception e) {
                Log.w(TAG, "JSON解析失败，回退兼容逻辑: " + e.getMessage());
            }
        }

        if (aiVision.contains("结论")) {
            int conclusionIndex = aiVision.lastIndexOf("结论");
            if (conclusionIndex >= 0) {
                assessment.behavior = "分心行为";
                assessment.reason = aiVision;
                String conclusionPart = aiVision.substring(conclusionIndex);
                if (conclusionPart.contains("否")) {
                    assessment.status = DecisionStatus.DISTRACTED;
                    assessment.confidence = 75;
                } else if (conclusionPart.contains("是")) {
                    assessment.status = DecisionStatus.FOCUSED;
                    assessment.confidence = 75;
                }
                return assessment;
            }
        }

        if (aiVision.contains("|")) {
            String[] parts = aiVision.split("\\|");
            if (parts.length >= 2) {
                assessment.behavior = parts[0].trim();
                assessment.reason = parts[1].trim();
                String focusStatus = parts[1].trim().toLowerCase(Locale.ROOT);
                if (focusStatus.endsWith("否") || focusStatus.contains(":否") || focusStatus.contains("：否")) {
                    assessment.status = DecisionStatus.DISTRACTED;
                    assessment.confidence = 70;
                } else if (focusStatus.endsWith("是") || focusStatus.contains(":是") || focusStatus.contains("：是")) {
                    assessment.status = DecisionStatus.FOCUSED;
                    assessment.confidence = 70;
                }
                return assessment;
            }
        }

        if (aiVision.contains("行为描述") || aiVision.contains("错误") || aiVision.contains("无法")) {
            assessment.behavior = "分析中...";
            assessment.reason = aiVision;
            assessment.confidence = 20;
            return assessment;
        }

        assessment.behavior = aiVision;
        if (containsAnyIgnoreCase(aiVision, WORK_KEYWORDS)) {
            assessment.status = DecisionStatus.FOCUSED;
            assessment.confidence = 65;
            assessment.reason = "关键词表明当前行为与工作/学习相关";
            return assessment;
        }
        if (containsAnyIgnoreCase(aiVision, DISTRACTION_KEYWORDS)) {
            assessment.status = DecisionStatus.DISTRACTED;
            assessment.confidence = 65;
            assessment.reason = "关键词表明当前行为偏向娱乐/闲逛";
            return assessment;
        }

        assessment.behavior = "信息不足";
        assessment.reason = "无法从AI结果中提取稳定结论";
        assessment.confidence = 35;
        return assessment;
    }

    private boolean checkAppDistraction(String packageName, Set<String> interventionExemptApps) {
        if (packageName == null || packageName.isEmpty()) return false;

        if (isInterventionExemptApp(packageName, interventionExemptApps)) return false;

        String pkg = packageName.toLowerCase();

        // 系统应用白名单 - 桌面、启动器、设置等永远不算分心
        for (String sysApp : SYSTEM_WHITELIST) {
            if (pkg.contains(sysApp.toLowerCase())) {
                Log.d(TAG, "系统应用白名单匹配: " + packageName);
                return false;
            }
        }

        // 常见分心应用包名
        return pkg.contains("douyin") || pkg.contains("tiktok") ||
               pkg.contains("kuaishou") || pkg.contains("bilibili") ||
               pkg.contains("weibo") || pkg.contains("xiaohongshu") ||
               pkg.contains("game") || pkg.contains("video") ||
               pkg.contains("taobao") || pkg.contains("jd.com") ||
               pkg.contains("pinduoduo");
    }

    private boolean isInterventionExemptApp(String packageName, Set<String> interventionExemptApps) {
        if (packageName == null || packageName.isEmpty() || interventionExemptApps == null) {
            return false;
        }
        if (interventionExemptApps.contains(packageName)) {
            return true;
        }
        for (String allowed : interventionExemptApps) {
            if (allowed == null || allowed.isEmpty()) {
                continue;
            }
            if (packageName.startsWith(allowed + ".") || allowed.startsWith(packageName + ".")) {
                return true;
            }
        }
        return false;
    }

    private void updateWarningLevel() {
        // 简化逻辑：分心3次直接锁机，不弹窗警告
        if (distractionCount >= LOCK_THRESHOLD) {
            currentLevel = WarningLevel.LOCK;
            lastWarningMessage = "分心次数已达 " + distractionCount + " 次，应用将被锁定！";
        } else {
            // 不再显示WARNING弹窗，只记录状态
            currentLevel = distractionCount > 0 ? WarningLevel.WARNING : WarningLevel.NONE;
            int remaining = LOCK_THRESHOLD - distractionCount;
            lastWarningMessage = distractionCount > 0 ?
                "分心: " + distractionCount + "/" + LOCK_THRESHOLD : "";
        }
    }

    private void executeWarning() {
        long now = System.currentTimeMillis();
        final long warningCooldownMs = getWarningCooldownMs();

        switch (currentLevel) {
            case WARNING:
                // 分心1-2次：震动通知提醒（有冷却时间）
                if (now - lastWarningTime >= warningCooldownMs) {
                    lastWarningTime = now;
                    recordIntervention("warning");
                    triggerHapticAlert(false);
                    showWarningNotification();
                }
                break;
            case LOCK:
                // 分心3次：锁机
                recordIntervention("lock");
                triggerHapticAlert(true);
                showWarningNotification();
                showLockNotification();
                break;
            default:
                break;
        }
    }

    /**
     * 记录干预事件到数据库（用于报告统计）
     */
    private void recordIntervention(String type) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Intervention intervention = new Intervention();
                intervention.sessionId = currentSessionId;
                intervention.timestamp = System.currentTimeMillis();
                intervention.type = type;
                intervention.triggerReason = "ai_distraction";
                intervention.stateBefore = lastAiActivity;
                intervention.interruptibilityBefore = 0.5f;
                intervention.deltaDistraction = 1.0f;
                intervention.deltaFocusTime = 0f;
                intervention.userFeedback = "pending";

                interventionDao.insert(intervention);
                Log.d(TAG, "干预事件已记录: " + type + " - " + lastAiActivity);
            } catch (Exception e) {
                Log.e(TAG, "记录干预事件失败: " + e.getMessage());
            }
        });
    }

    private static final String CHANNEL_WARNING_ID = "MindFlow_Warning_Channel";
    private static final int NOTIFICATION_WARNING_ID = 2001;

    private void triggerHapticAlert(boolean strong) {
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            long[] pattern = strong ? new long[]{0, 260, 120, 260} : new long[]{0, 160, 90, 160};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } catch (Exception e) {
            Log.w(TAG, "触发震动提醒失败: " + e.getMessage());
        }
    }

    /**
     * 显示分心警告通知（Heads-up弹出）
     * 只是提醒，点击后只是取消通知，不进入锁机
     */
    private void showWarningNotification() {
        createWarningChannel();

        int remaining = LOCK_THRESHOLD - distractionCount;

        // 点击通知只是打开主界面，不进入锁机
        Intent mainIntent = new Intent(context, com.example.mindflow.MainActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(
            context, 100, mainIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // 根据官方文档，Heads-up通知需要：IMPORTANCE_HIGH + PRIORITY_HIGH + sound或vibrate
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_WARNING_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("⚠️ 检测到分心")
            .setContentText("再分心 " + remaining + " 次将锁定应用")
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText("🚨 你正在: " + lastAiActivity + "\n" +
                    "再分心 " + remaining + " 次将锁定应用\n" +
                    "请回到工作中！"))
            .setContentIntent(mainPendingIntent)  // 点击只是打开主界面
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)  // 点击后自动取消
            .setTimeoutAfter(5000)  // 5秒后自动消失
            .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_WARNING_ID, builder.build());
        }
    }

    /**
     * 显示锁定通知并立即进入锁屏界面
     */
    private void showLockNotification() {
        createWarningChannel();

        // 设置锁定状态
        isLocked = true;

        // 锁机界面由FocusService通过LockOverlayManager统一管理
        // DistractionManager只负责检测和计数，不直接启动锁机界面
        // FocusService会监听WARNING级别变化并触发锁机Overlay

        Log.i(TAG, "🔒 锁定状态已设置，等待FocusService显示锁机Overlay");
    }

    private void createWarningChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            // 先删除旧渠道（如果存在），确保新设置生效
            nm.deleteNotificationChannel(CHANNEL_WARNING_ID);

            NotificationChannel channel = new NotificationChannel(
                CHANNEL_WARNING_ID,
                "分心警告",
                NotificationManager.IMPORTANCE_HIGH  // HIGH才能显示Heads-up
            );
            channel.setDescription("分心提醒通知（会弹出显示）");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 300, 100, 300});
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setBypassDnd(true);  // 绕过勿扰模式
            channel.enableLights(true);
            channel.setShowBadge(true);

            nm.createNotificationChannel(channel);
        }
    }

    /**
     * 锁定触发后调用，重置部分计数
     */
    public void onLockTriggered() {
        distractionCount = 0;
        currentLevel = WarningLevel.NONE;
        consecutiveWorkSeconds = 0;
        distractionEvidence = 0;
        consecutiveDistractedSignals = 0;
    }

    /**
     * 重置分心次数为0（恢复3/3机会）
     */
    public void resetDistractionCount() {
        distractionCount = 0;
        currentLevel = WarningLevel.NONE;
        isLocked = false;  // 【关键】重置锁定状态，否则AI分析不会恢复
        distractionEvidence = 0;
        consecutiveDistractedSignals = 0;
        Log.i(TAG, "分心次数已重置，恢复 3/3 机会");
    }

    /**
     * 完全重置（新会话开始时调用）
     */
    public void reset() {
        distractionCount = 0;
        totalDistractionCount = 0;
        lastDistractionTime = 0;
        lastWorkTime = 0;
        consecutiveWorkSeconds = 0;
        distractionEvidence = 0;
        consecutiveDistractedSignals = 0;
        currentLevel = WarningLevel.NONE;
        lastWarningMessage = "";
        isLocked = false;  // 重置锁定状态
        // 清空AI识别日志（每次新会话重新开始记录）
        aiRecognitionLog.clear();
        saveAiRecognitionLog();
        // 清空分心历史（本次会话的）
        distractionHistoryList.clear();
        saveDistractionHistory();
        Log.d(TAG, "会话重置：AI日志和分心历史已清空");
    }

    /**
     * 设置锁定状态
     */
    public void setLocked(boolean locked) {
        this.isLocked = locked;
    }

    /**
     * 获取锁定状态
     */
    public boolean isLocked() {
        return isLocked;
    }

    /**
     * 启用监控（开始专注时调用）
     */
    public void enableMonitoring() {
        this.isMonitoringEnabled = true;
        Log.i(TAG, "📊 监控已启用");
    }

    /**
     * 禁用监控（停止专注时调用）
     */
    public void disableMonitoring() {
        this.isMonitoringEnabled = false;
        Log.i(TAG, "📊 监控已禁用");
    }

    /**
     * 完全停止并重置（停止专注时调用）
     */
    public void stopAndReset() {
        this.isMonitoringEnabled = false;
        this.isLocked = false;
        this.distractionCount = 0;
        this.currentLevel = WarningLevel.NONE;
        this.lastAiFocused = true;
        this.distractionEvidence = 0;
        this.consecutiveDistractedSignals = 0;
        this.lockScreenDistractionCache.clear();  // 清空锁机缓存
        Log.i(TAG, "🛑 监控已完全停止，所有状态已重置");
    }

    /**
     * 添加分心记录到锁机缓存（只保留最近3条）
     */
    private void addToLockScreenCache(String behavior, String reason) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
            .format(new java.util.Date());
        String record = timestamp + " | " + behavior + "\n   原因: " + reason;
        lockScreenDistractionCache.add(record);
        // 只保留最近3条
        while (lockScreenDistractionCache.size() > MAX_LOCK_CACHE_SIZE) {
            lockScreenDistractionCache.remove(0);
        }
        Log.d(TAG, "📝 锁机缓存添加记录: " + record);
    }

    /**
     * 获取锁机页面显示的分心记录（最近3条）
     */
    public String getLockScreenDistractionRecords() {
        if (lockScreenDistractionCache.isEmpty()) {
            return "暂无分心记录";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lockScreenDistractionCache.size(); i++) {
            sb.append(i + 1).append(". ").append(lockScreenDistractionCache.get(i));
            if (i < lockScreenDistractionCache.size() - 1) {
                sb.append("\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * 清空锁机缓存（锁机结束时调用）
     */
    public void clearLockScreenCache() {
        lockScreenDistractionCache.clear();
        Log.d(TAG, "🗑️ 锁机缓存已清空");
    }

    /**
     * 记录一次有效分心事件
     */
    private void recordDistraction(long now, String foregroundApp, String aiVision, AiAssessment assessment) {
        distractionCount++;
        totalDistractionCount++;
        lastDistractionTime = now;
        consecutiveWorkSeconds = 0;
        distractionEvidence = 25;
        consecutiveDistractedSignals = 0;

        String activity = buildActivityLabel(assessment.behavior, foregroundApp);
        lastAiActivity = activity;
        logAiRecognition(aiVision + " [有效分心]", false);
        addToLockScreenCache(activity, assessment.reason);

        Log.w(TAG, "检测到分心！计数: " + distractionCount
                + ", confidence=" + assessment.confidence
                + ", activity=" + activity
                + ", reason=" + assessment.reason);

        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(new java.util.Date());
        String appInfo = (foregroundApp != null && !foregroundApp.isEmpty()) ? getAppLabel(foregroundApp) : "";
        String record = "• [" + timestamp + "] " + activity;
        if (!appInfo.isEmpty() && !activity.contains(appInfo)) {
            record += " (App: " + appInfo + ")";
        }
        distractionHistoryList.add(record);
        saveDistractionHistory();
        saveDistractionToDatabase(activity, foregroundApp, aiVision);
        updateWarningLevel();
        executeWarning();
    }

    private int getEvidenceDelta(int confidence, DecisionStatus status) {
        if (status == DecisionStatus.UNSURE) {
            return getAppSignalEvidence() / 2;
        }
        if (status != DecisionStatus.DISTRACTED) {
            return -getUnsureRecovery();
        }
        if (confidence >= getHighConfidenceThreshold()) {
            return 60;
        }
        if (confidence >= getMediumConfidenceThreshold()) {
            return 40;
        }
        if (confidence >= getLowConfidenceThreshold()) {
            return 25;
        }
        return 10;
    }

    private int getSensitivity() {
        return FocusModePreferences.getAiAuditSensitivity(context);
    }

    private double getSensitivityRatio() {
        return Math.max(0d, Math.min(1d, getSensitivity() / 100d));
    }

    private int getLowConfidenceThreshold() {
        double r = getSensitivityRatio();
        return (int) Math.round(80 - 60 * r); // 宽松80 -> 严格20
    }

    private int getMediumConfidenceThreshold() {
        double r = getSensitivityRatio();
        return (int) Math.round(90 - 55 * r); // 宽松90 -> 严格35
    }

    private int getHighConfidenceThreshold() {
        double r = getSensitivityRatio();
        return (int) Math.round(96 - 41 * r); // 宽松96 -> 严格55
    }

    private int getDistractionTriggerEvidence() {
        double r = getSensitivityRatio();
        return (int) Math.round(95 - 75 * r); // 宽松95 -> 严格20
    }

    private int getAppSignalEvidence() {
        double r = getSensitivityRatio();
        return (int) Math.round(10 + 45 * r); // 宽松10 -> 严格55
    }

    private int getStrongFocusRecovery() {
        double r = getSensitivityRatio();
        return (int) Math.round(45 - 37 * r); // 宽松45 -> 严格8
    }

    private int getUnsureRecovery() {
        double r = getSensitivityRatio();
        return (int) Math.round(24 - 22 * r); // 宽松24 -> 严格2
    }

    private long getWarningCooldownMs() {
        double r = getSensitivityRatio();
        return (long) Math.round(12000 - 11200 * r); // 宽松12s -> 严格0.8s
    }

    private long getDistractionDebounceMs() {
        double r = getSensitivityRatio();
        return (long) Math.round(18000 - 17500 * r); // 宽松18s -> 严格0.5s
    }

    private DecisionStatus parseConclusion(String conclusion, String reason) {
        if (conclusion == null) {
            return containsAnyIgnoreCase(reason, "不符合", "偏离目标", "无关")
                    ? DecisionStatus.DISTRACTED
                    : DecisionStatus.UNSURE;
        }
        String normalized = conclusion.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("YES") || normalized.contains("符合")) {
            return DecisionStatus.FOCUSED;
        }
        if (normalized.startsWith("NO") || normalized.contains("不符合")) {
            return DecisionStatus.DISTRACTED;
        }
        if (normalized.contains("UNSURE") || normalized.contains("UNKNOWN")
                || normalized.contains("MAYBE") || normalized.contains("不确定")
                || normalized.contains("信息不足")) {
            return DecisionStatus.UNSURE;
        }
        if (containsAnyIgnoreCase(reason, "不符合", "偏离目标", "无关")) {
            return DecisionStatus.DISTRACTED;
        }
        if (containsAnyIgnoreCase(reason, "符合目标", "相关", "正在进行")) {
            return DecisionStatus.FOCUSED;
        }
        return DecisionStatus.UNSURE;
    }

    private int parseConfidence(JSONObject json) {
        Object confidenceObj = json.opt("confidence");
        if (confidenceObj instanceof Number) {
            return clamp(((Number) confidenceObj).intValue(), 0, 100);
        }
        if (confidenceObj != null) {
            String digits = confidenceObj.toString().replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    return clamp(Integer.parseInt(digits), 0, 100);
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }
        return 50;
    }

    private String extractJsonPayload(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```$", "");
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    private boolean containsAnyIgnoreCase(String text, String... keywords) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String buildActivityLabel(String behavior, String foregroundApp) {
        String activity = (behavior == null || behavior.trim().isEmpty()) ? "未知行为" : behavior.trim();
        String appInfo = (foregroundApp == null || foregroundApp.isEmpty()) ? "" : getAppLabel(foregroundApp);
        if (!appInfo.isEmpty() && !activity.contains(appInfo)) {
            return activity + " (" + appInfo + ")";
        }
        return activity;
    }

    // Getters
    public WarningLevel getWarningLevel() { return currentLevel; }
    public String getWarningMessage() { return lastWarningMessage; }
    public int getWarningCount() { return distractionCount; }
    public int getTotalDistractionCount() { return totalDistractionCount; }
    public String getLastAiActivity() { return lastAiActivity; }
    public boolean isLastAiFocused() { return lastAiFocused; }

    /**
     * 获取分心历史记录（格式化字符串）
     */
    public String getDistractionHistory() {
        if (distractionHistoryList.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // 显示最近10条
        int count = Math.min(distractionHistoryList.size(), 10);
        for (int i = distractionHistoryList.size() - count; i < distractionHistoryList.size(); i++) {
            sb.append(distractionHistoryList.get(i));
            if (i < distractionHistoryList.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 清空分心历史
     */
    public void clearHistory() {
        distractionHistoryList.clear();
    }

    // === 番茄todo风格：AccessibilityService锁机模式控制 ===
    private java.util.Set<String> currentInterventionExemptApps = new java.util.HashSet<>();

    /**
     * 设置允许使用、但不自动算命中目标的应用（从 FocusService 传入）
     */
    public void setInterventionExemptApps(java.util.Set<String> apps) {
        this.currentInterventionExemptApps = apps;
    }

    /**
     * 激活锁机界面（分心3次后调用）
     */
    private void enableAccessibilityLockMode(long duration, String reason, String advice) {
        AppMonitorService service = AppMonitorService.getInstance();
        if (service != null) {
            service.updateWhitelist(currentInterventionExemptApps);
            // 激活锁机界面（开始强制拉回）
            service.activateLockScreen();
            Log.i(TAG, "🔒 已激活锁机界面，允许使用应用: " + currentInterventionExemptApps.size() + " 个");
        } else {
            Log.w(TAG, "⚠️ AppMonitorService未运行，无法激活锁机界面");
        }
    }

    /**
     * 禁用AccessibilityService的锁机模式
     */
    public void disableAccessibilityLockMode() {
        AppMonitorService service = AppMonitorService.getInstance();
        if (service != null) {
            service.disableLockMode();
            Log.i(TAG, "🔓 已禁用AccessibilityService锁机模式");
        }
        isLocked = false;
    }


    /**
     * 获取应用名称
     */
    private String getAppLabel(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "";
        try {
            return context.getPackageManager().getApplicationLabel(
                context.getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName.substring(packageName.lastIndexOf('.') + 1);
        }
    }

    /**
     * 保存分心记录到数据库
     */
    private void saveDistractionToDatabase(String activity, String appPackage, String aiResult) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Intervention intervention = new Intervention();
                intervention.type = "distraction";
                intervention.timestamp = System.currentTimeMillis();
                intervention.triggerReason = appPackage != null ? appPackage : "";
                intervention.stateBefore = activity + " | AI: " + aiResult;
                intervention.sessionId = currentSessionId;
                interventionDao.insert(intervention);
                Log.d(TAG, "分心记录已保存到数据库");
            } catch (Exception e) {
                Log.e(TAG, "保存分心记录失败: " + e.getMessage());
            }
        });
    }
}
