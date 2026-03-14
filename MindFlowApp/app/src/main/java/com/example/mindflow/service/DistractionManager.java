package com.example.mindflow.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.mindflow.MainActivity;
import com.example.mindflow.R;
import com.example.mindflow.database.MindFlowDatabase;
import com.example.mindflow.database.dao.InterventionDao;
import com.example.mindflow.model.Intervention;
import com.example.mindflow.core.TomatoFocusManager;
import com.example.mindflow.model.FocusTask;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * 分心检测与渐进式警告管理器 (漏桶算法防作弊加强版)
 */
public class DistractionManager {
    private static final String TAG = "DistractionManager";

    public enum WarningLevel {
        NONE,      // 无警告
        WARNING,   // 弹窗提醒 (1-2次分心)
        LOCK       // 锁定 (3次以上)
    }

    private final Context context;
    private final InterventionDao interventionDao;
    private String currentSessionId = "";

    // ================= 终极防作弊状态机核心变量 =================
    private boolean isMonitoringEnabled = false;
    private boolean isLocked = false;

    // 考官双源鉴定结果：当前是否处于分心状态（状态锁定）
    private boolean isCurrentlyDistracted = false;

    // 水桶与阀门
    private int dangerValue = 0;              // 当前水桶里的水（危险值）
    private int probationSeconds = 0;         // 排水阀门上的时间锁（考察期倒数）
    private static final int PROBATION_LOCK_MAX = 30; // 考察期固定30秒

    // 报告与UI显示数据
    private int distractionCount = 0;         // 当前连续警告阶段 (0, 1, 2, 3)
    private int totalDistractionCount = 0;    // 历史累计总分心次数 (用于报告)

    public enum DistractionPhase {
        NONE,           // 专注中
        BUFFERING,      // 正在迈向第1次警告
        WARNING_1,      // 正在迈向第2次警告
        WARNING_2,      // 正在迈向第3次警告
        WARNING_3       // 正在迈向锁机
    }
    private DistractionPhase currentPhase = DistractionPhase.NONE;

    private WarningLevel currentLevel = WarningLevel.NONE;
    private String lastWarningMessage = "";
    private String lastAiActivity = ""; // AI 识别的用户行为
    private boolean lastAiFocused = true; // AI 判断是否专注

    // 分心历史记录（从SharedPreferences加载）
    private final java.util.List<String> distractionHistoryList = new java.util.ArrayList<>();
    private static final String PREF_DISTRACTION_HISTORY = "distraction_history_list";

    // AI识别日志（调试用）
    private final java.util.List<String> aiRecognitionLog = new java.util.ArrayList<>();
    private static final String PREF_AI_RECOGNITION_LOG = "ai_recognition_log";

    // 锁机页面分心记录缓存（只保存最近3条判断为"否"的记录）
    private final java.util.List<String> lockScreenDistractionCache = new java.util.ArrayList<>();
    private static final int MAX_LOCK_CACHE_SIZE = 3;

    // 系统应用白名单 - 这些应用永远不算分心（兜底防误杀机制）
    private static final String[] SYSTEM_WHITELIST = {
            "launcher", "home", "desktop", "桌面",
            "systemui", "settings", "设置",
            "mindflow",
            "inputmethod", "keyboard", "输入法",
            "permissioncontroller", "packageinstaller"
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

    private java.util.Set<String> currentWhitelist = new java.util.HashSet<>();

    private static final String CHANNEL_WARNING_ID = "MindFlow_Warning_Channel";
    private static final int NOTIFICATION_WARNING_ID = 2001;

    public DistractionManager(Context context) {
        this.context = context;
        this.interventionDao = MindFlowDatabase.getInstance(context).interventionDao();
        loadDistractionHistory();
        loadAiRecognitionLog();
    }

    // ================= 核心 1：双源考官，只负责鉴定状态 =================
    /**
     * 分析当前状态并检测是否分心 (状态锁定，不再直接累加次数或弹窗)
     */
    public boolean analyzeAndCheck(String aiVision, String foregroundApp, Set<String> whitelist) {
        if (!isMonitoringEnabled || isLocked) {
            return false;
        }

        AppMonitorService service = AppMonitorService.getInstance();
        if (service != null && service.isInBufferPeriod()) {
            return false;
        }

        // 考官 A：包名雷达（一票否决）
        boolean isDistractedByApp = checkAppDistraction(foregroundApp, whitelist);
        boolean isDistractedByVision = false;

        // 考官 B：AI 视觉（仅当包名在白名单里，或者包名检测未判定为分心时，再看AI的意见）
        if (!isDistractedByApp && aiVision != null && !aiVision.isEmpty()) {
            isDistractedByVision = checkVisionDistraction(aiVision);
        }

        // 或门逻辑：只要有一个判定分心，当前即为分心状态
        boolean newDistractedState = isDistractedByApp || isDistractedByVision;

        // 如果状态由“专注”变为“分心”，记录一次底层的案发日志 (这不会触发惩罚，只是记录)
        if (newDistractedState && !this.isCurrentlyDistracted) {
            logDistractionEvent(foregroundApp, aiVision);
        }

        // 状态锁定！具体的加水、惩罚交给 tickStateMachine 去做
        this.isCurrentlyDistracted = newDistractedState;

        return newDistractedState;
    }

    // ================= 核心 2：1秒心跳驱动的漏桶算法 =================
    /**
     * 由 FocusService 的 1 秒心跳调用
     * @return true 表示水桶溢出，触发了警告弹窗；false 表示安然无恙
     */
    public boolean tickStateMachine() {
        if (!isMonitoringEnabled || isLocked) return false;

        if (isCurrentlyDistracted) {
            // 【作死/分心状态】
            probationSeconds = PROBATION_LOCK_MAX; // 排水阀门瞬间死死锁上！重置考察期
            dangerValue++; // 危险值涨水

            if (currentPhase == DistractionPhase.NONE) {
                currentPhase = DistractionPhase.BUFFERING; // 启动第一阶段
                Log.w(TAG, "⏳ 发现分心，进入缓冲期倒计时...");
            }
        } else {
            // 【伪装/回归状态】
            if (probationSeconds > 0) {
                probationSeconds--; // 阀门紧锁，只消耗考察期，危险值不减！
            } else {
                // 【彻底洗白状态】熬过了30秒考察期，阀门打开！
                if (dangerValue > 0) {
                    dangerValue--;
                } else {
                    // 水抽干了，恢复完全清白
                    if (currentPhase != DistractionPhase.NONE || distractionCount > 0) {
                        Log.i(TAG, "🎉 熬过考察期且危险值清零，用户彻底洗白！状态机重置。");
                        currentPhase = DistractionPhase.NONE;
                        distractionCount = 0;
                        currentLevel = WarningLevel.NONE;

                        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                        if (nm != null) nm.cancel(NOTIFICATION_WARNING_ID);
                    }
                }
            }
        }

        // 检查水桶是否溢出
        if (currentPhase != DistractionPhase.NONE) {
            int currentThreshold = getThresholdForCurrentPhase();
            if (dangerValue >= currentThreshold) {
                // 💥 爆表了！
                dangerValue = 0; // 清空水桶，准备迎接下一阶段
                totalDistractionCount++; // 只有爆表了，才真正记 1 次黑账！
                advanceDistractionPhase();
                return true; // 告诉 FocusService 去弹窗或发广播
            }
        }
        return false;
    }

    /**
     * 判断当前是否处于危险或考察期（供给 FocusService 用来决定 AI 薛定谔巡逻频率）
     */
    public boolean isInProbationOrDanger() {
        return dangerValue > 0 || probationSeconds > 0 || isCurrentlyDistracted;
    }

    // ================= 阶段流转与动态时间计算 =================
    private void advanceDistractionPhase() {
        switch (currentPhase) {
            case BUFFERING:
                currentPhase = DistractionPhase.WARNING_1;
                distractionCount = 1;
                executeDynamicWarning("请注意，你已经分心一段时间了！", 3);
                break;
            case WARNING_1:
                currentPhase = DistractionPhase.WARNING_2;
                distractionCount = 2;
                executeDynamicWarning("连续分心！再不回去就要锁屏了！", 2);
                break;
            case WARNING_2:
                currentPhase = DistractionPhase.WARNING_3;
                distractionCount = 3;
                executeDynamicWarning("【最后警告】即将强制锁机！", 1);
                break;
            case WARNING_3:
                Log.e(TAG, "💥 分心时间彻底耗尽，执行强制锁机！");
                currentPhase = DistractionPhase.NONE;
                distractionCount = 4; // 确保触发锁机阈值
                currentLevel = WarningLevel.LOCK;

                recordIntervention("lock");
                showLockNotification();

                // 通知 TomatoFocusManager 触发锁机
                TomatoFocusManager.getInstance(context).triggerLock("动态分心时间耗尽");
                break;
            default: break;
        }
    }

    private int getThresholdForCurrentPhase() {
        TomatoFocusManager focusManager = TomatoFocusManager.getInstance(context);
        FocusTask task = focusManager.getCurrentTask();

        // 默认短时专注（小于20分钟）或无任务时的静态配置(秒)
        long bufferSecs = 15;
        long interval1Secs = 10;
        long interval2Secs = 10;
        long interval3Secs = 10;

        if (task != null && task.focusDurationMinutes >= 20) {
            // 长时专注：动态计算
            long totalMs = task.focusDurationMinutes * 60 * 1000L;
            long elapsedMs = totalMs - focusManager.getRemainingMs();
            float ratio = Math.min(1.0f, Math.max(0.0f, (float) elapsedMs / totalMs));

            // 套用数学模型
            bufferSecs = 10 + (long) (ratio * 50);     // 10s ~ 60s
            interval1Secs = 5 + (long) (ratio * 10);   // 5s ~ 15s
            interval2Secs = 10 + (long) (ratio * 15);  // 10s ~ 25s
            interval3Secs = 15 + (long) (ratio * 20);  // 15s ~ 35s
        }

        switch (currentPhase) {
            case BUFFERING: return (int) bufferSecs;
            case WARNING_1: return (int) interval1Secs;
            case WARNING_2: return (int) interval2Secs;
            case WARNING_3: return (int) interval3Secs;
            default: return 999;
        }
    }

    // ================= 以下为原版业务逻辑代码，全部原封不动保留 =================

    private boolean checkVisionDistraction(String aiVision) {
        if (aiVision == null || aiVision.isEmpty()) return false;

        String visionLower = aiVision.toLowerCase();
        for (String ignoreKw : IGNORE_KEYWORDS) {
            if (visionLower.contains(ignoreKw.toLowerCase())) {
                Log.d(TAG, "忽略关键词匹配，不计为分心: " + aiVision);
                lastAiActivity = aiVision;
                lastAiFocused = true;
                return false;
            }
        }

        if (aiVision.contains("conclusion")) {
            try {
                String conclusion = extractJsonField(aiVision, "conclusion").toUpperCase().trim();
                String behavior = extractJsonField(aiVision, "behavior");
                String reason = extractJsonField(aiVision, "reason");
                String confidenceStr = extractJsonField(aiVision, "confidence");
                int confidence = 50;
                try {
                    confidence = Integer.parseInt(confidenceStr.replaceAll("[^0-9]", ""));
                } catch (Exception ignored) {
                }

                lastAiActivity = behavior.isEmpty() ? "未知行为" : behavior;
                Log.d(TAG, "AI JSON解析: conclusion=" + conclusion + ", behavior=" + behavior + ", reason=" + reason + ", confidence=" + confidence);

                boolean conclusionIsNo = conclusion.equals("NO") || conclusion.startsWith("NO");
                boolean conclusionIsYes = conclusion.equals("YES") || conclusion.startsWith("YES");

                boolean reasonSaysDistracted = reason.contains("不符合") || reason.contains("偏离目标") || reason.contains("与目标无关");
                boolean reasonSaysFocused = reason.contains("符合目标") || reason.contains("相关") || reason.contains("正在进行");

                if ((conclusionIsYes && reasonSaysDistracted) || (conclusionIsNo && reasonSaysFocused)) {
                    Log.w(TAG, "⚠️ AI判断矛盾! conclusion=" + conclusion + ", reason=" + reason + " -> 以conclusion为准");
                }

                boolean isDistracted = conclusionIsNo && confidence >= 50;

                if (!conclusionIsNo && !conclusionIsYes) {
                    Log.w(TAG, "⚠️ AI conclusion不明确: " + conclusion + " -> 默认为专注");
                    isDistracted = false;
                }

                if (isDistracted) {
                    lastAiFocused = false;
                    logAiRecognition(aiVision, false);
                    addToLockScreenCache(behavior, reason);
                    Log.w(TAG, "⚠️ AI判断: 分心! conclusion=" + conclusion + ", reason=" + reason);
                    return true;
                } else {
                    lastAiFocused = true;
                    logAiRecognition(aiVision, true);
                    Log.d(TAG, "✅ AI判断: 专注! conclusion=" + conclusion + ", reason=" + reason);
                    return false;
                }
            } catch (Exception e) {
                Log.w(TAG, "JSON解析失败: " + e.getMessage() + " -> 默认为专注");
                lastAiFocused = true;
                return false;
            }
        }

        if (aiVision.contains("结论")) {
            int conclusionIndex = aiVision.lastIndexOf("结论");
            if (conclusionIndex >= 0) {
                String conclusionPart = aiVision.substring(conclusionIndex);
                if (conclusionPart.contains("否")) {
                    lastAiFocused = false;
                    logAiRecognition(aiVision, false);
                    addToLockScreenCache("分心行为", aiVision.length() > 50 ? aiVision.substring(0, 50) : aiVision);
                    return true;
                } else if (conclusionPart.contains("是")) {
                    lastAiFocused = true;
                    logAiRecognition(aiVision, true);
                    return false;
                }
            }
        }

        if (aiVision.contains("|")) {
            String[] parts = aiVision.split("\\|");
            if (parts.length >= 2) {
                lastAiActivity = parts[0].trim();
                String focusStatus = parts[1].trim().toLowerCase();

                if (focusStatus.endsWith("否") || focusStatus.contains(":否") || focusStatus.contains("：否")) {
                    lastAiFocused = false;
                    logAiRecognition(aiVision, false);
                    return true;
                } else if (focusStatus.endsWith("是") || focusStatus.contains(":是") || focusStatus.contains("：是")) {
                    lastAiFocused = true;
                    logAiRecognition(aiVision, true);
                    return false;
                }

                Log.w(TAG, "AI响应格式异常: " + aiVision);
                logAiRecognition(aiVision + " [格式异常]", true);
                lastAiFocused = true;
                return false;
            }
        }

        if (aiVision.contains("行为描述") || aiVision.contains("错误") || aiVision.contains("无法")) {
            Log.w(TAG, "AI返回异常内容: " + aiVision);
            logAiRecognition(aiVision + " [异常]", true);
            lastAiActivity = "分析中...";
            lastAiFocused = true;
            return false;
        }

        lastAiActivity = aiVision;
        String vision = aiVision.toLowerCase(Locale.CHINA);

        for (String keyword : WORK_KEYWORDS) {
            if (vision.contains(keyword.toLowerCase())) {
                lastAiFocused = true;
                logAiRecognition(aiVision + " [关键词:工作]", true);
                return false;
            }
        }

        for (String keyword : DISTRACTION_KEYWORDS) {
            if (vision.contains(keyword.toLowerCase())) {
                lastAiFocused = false;
                logAiRecognition(aiVision + " [关键词:分心]", false);
                return true;
            }
        }

        lastAiFocused = true;
        logAiRecognition(aiVision + " [默认:专注]", true);
        return false;
    }

    private boolean checkAppDistraction(String packageName, Set<String> whitelist) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (whitelist.contains(packageName)) return false;
        String pkg = packageName.toLowerCase();

        for (String sysApp : SYSTEM_WHITELIST) {
            if (pkg.contains(sysApp.toLowerCase())) {
                Log.d(TAG, "系统应用白名单匹配: " + packageName);
                return false;
            }
        }

        return pkg.contains("douyin") || pkg.contains("tiktok") ||
                pkg.contains("kuaishou") || pkg.contains("bilibili") ||
                pkg.contains("weibo") || pkg.contains("xiaohongshu") ||
                pkg.contains("game") || pkg.contains("video") ||
                pkg.contains("taobao") || pkg.contains("jd.com") ||
                pkg.contains("pinduoduo");
    }

    private void executeDynamicWarning(String customMessage, int remainingChances) {
        currentLevel = WarningLevel.WARNING;
        lastWarningMessage = customMessage;
        recordIntervention("warning_step_" + distractionCount);

        createWarningChannel();

        Intent mainIntent = new Intent(context, com.example.mindflow.MainActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(
                context, 100, mainIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_WARNING_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("⚠️ " + customMessage)
                .setContentText("如果不回去，还有 " + remainingChances + " 次机会将强制锁机")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("🚨 你正在: " + lastAiActivity + "\n系统正在倒计时，请立即回到工作中！"))
                .setContentIntent(mainPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setTimeoutAfter(8000)
                .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_WARNING_ID, builder.build());
        }
    }

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

    private void showLockNotification() {
        createWarningChannel();
        isLocked = true;
        Log.i(TAG, "🔒 锁定状态已设置，等待FocusService显示锁机Overlay");
    }

    private void createWarningChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.deleteNotificationChannel(CHANNEL_WARNING_ID);

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_WARNING_ID,
                    "分心警告",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("分心提醒通知（会弹出显示）");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 300, 100, 300});
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setBypassDnd(true);
            channel.enableLights(true);
            channel.setShowBadge(true);

            nm.createNotificationChannel(channel);
        }
    }

    // =========== 状态重置与控制 ===========
    public void onLockTriggered() {
        distractionCount = 0;
        currentLevel = WarningLevel.NONE;
        currentPhase = DistractionPhase.NONE;
        dangerValue = 0;
        probationSeconds = 0;
    }

    public void resetDistractionCount() {
        distractionCount = 0;
        currentLevel = WarningLevel.NONE;
        isLocked = false;
        dangerValue = 0;
        probationSeconds = 0;
        Log.i(TAG, "分心次数已重置，恢复 3/3 机会");
    }

    public void reset() {
        distractionCount = 0;
        totalDistractionCount = 0;
        currentLevel = WarningLevel.NONE;
        lastWarningMessage = "";
        isLocked = false;
        isCurrentlyDistracted = false;
        dangerValue = 0;
        probationSeconds = 0;
        currentPhase = DistractionPhase.NONE;
        aiRecognitionLog.clear();
        saveAiRecognitionLog();
        distractionHistoryList.clear();
        saveDistractionHistory();
        Log.d(TAG, "会话重置：AI日志和分心历史已清空");
    }

    public void setLocked(boolean locked) { this.isLocked = locked; }
    public boolean isLocked() { return isLocked; }
    public void enableMonitoring() { this.isMonitoringEnabled = true; Log.i(TAG, "📊 监控已启用"); }
    public void disableMonitoring() { this.isMonitoringEnabled = false; Log.i(TAG, "📊 监控已禁用"); }
    public void stopAndReset() {
        this.isMonitoringEnabled = false;
        this.isLocked = false;
        this.distractionCount = 0;
        this.currentLevel = WarningLevel.NONE;
        this.lastAiFocused = true;
        this.lockScreenDistractionCache.clear();
        Log.i(TAG, "🛑 监控已完全停止，所有状态已重置");
    }

    // =========== SharedPreferences & Database & Utils ===========
    private void loadDistractionHistory() {
        android.content.SharedPreferences prefs = context.getSharedPreferences("MindFlowPrefs", Context.MODE_PRIVATE);
        String historyJson = prefs.getString(PREF_DISTRACTION_HISTORY, "");
        if (!historyJson.isEmpty()) {
            String[] items = historyJson.split("\\|\\|\\|");
            for (String item : items) {
                if (!item.trim().isEmpty()) distractionHistoryList.add(item);
            }
        }
    }

    private void saveDistractionHistory() {
        android.content.SharedPreferences prefs = context.getSharedPreferences("MindFlowPrefs", Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, distractionHistoryList.size() - 20);
        for (int i = start; i < distractionHistoryList.size(); i++) {
            if (sb.length() > 0) sb.append("|||");
            sb.append(distractionHistoryList.get(i));
        }
        prefs.edit().putString(PREF_DISTRACTION_HISTORY, sb.toString()).apply();
    }

    private void loadAiRecognitionLog() {
        android.content.SharedPreferences prefs = context.getSharedPreferences("MindFlowPrefs", Context.MODE_PRIVATE);
        String logJson = prefs.getString(PREF_AI_RECOGNITION_LOG, "");
        if (!logJson.isEmpty()) {
            String[] items = logJson.split("\\|\\|\\|");
            for (String item : items) {
                if (!item.trim().isEmpty()) aiRecognitionLog.add(item);
            }
        }
    }

    private void saveAiRecognitionLog() {
        android.content.SharedPreferences prefs = context.getSharedPreferences("MindFlowPrefs", Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, aiRecognitionLog.size() - 50);
        for (int i = start; i < aiRecognitionLog.size(); i++) {
            if (sb.length() > 0) sb.append("|||");
            sb.append(aiRecognitionLog.get(i));
        }
        prefs.edit().putString(PREF_AI_RECOGNITION_LOG, sb.toString()).apply();
    }

    public void logAiRecognition(String aiResult, boolean isFocused) {
        String timestamp = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
        String logEntry = "[" + timestamp + "] " + (isFocused ? "✓" : "✗") + " " + aiResult;
        aiRecognitionLog.add(logEntry);
        saveAiRecognitionLog();
        Log.d(TAG, "AI识别日志: " + logEntry);
    }

    public String getAiRecognitionLog() {
        if (aiRecognitionLog.isEmpty()) return "暂无识别记录";
        StringBuilder sb = new StringBuilder();
        sb.append("=== AI识别日志 (共").append(aiRecognitionLog.size()).append("条) ===\n\n");
        for (int i = 0; i < aiRecognitionLog.size(); i++) {
            sb.append(aiRecognitionLog.get(i));
            if (i < aiRecognitionLog.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }

    public int getAiRecognitionLogCount() { return aiRecognitionLog.size(); }
    public void clearAiRecognitionLog() { aiRecognitionLog.clear(); saveAiRecognitionLog(); Log.d(TAG, "AI识别日志已清空"); }
    public void setSessionId(String sessionId) { this.currentSessionId = sessionId; }

    private void addToLockScreenCache(String behavior, String reason) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date());
        String record = timestamp + " | " + behavior + "\n   原因: " + reason;
        lockScreenDistractionCache.add(record);
        while (lockScreenDistractionCache.size() > MAX_LOCK_CACHE_SIZE) {
            lockScreenDistractionCache.remove(0);
        }
        Log.d(TAG, "📝 锁机缓存添加记录: " + record);
    }

    public String getLockScreenDistractionRecords() {
        if (lockScreenDistractionCache.isEmpty()) return "暂无分心记录";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lockScreenDistractionCache.size(); i++) {
            sb.append(i + 1).append(". ").append(lockScreenDistractionCache.get(i));
            if (i < lockScreenDistractionCache.size() - 1) sb.append("\n\n");
        }
        return sb.toString();
    }

    public void clearLockScreenCache() { lockScreenDistractionCache.clear(); Log.d(TAG, "🗑️ 锁机缓存已清空"); }

    private String extractJsonField(String json, String fieldName) {
        try {
            String pattern1 = "\"" + fieldName + "\":\"";
            String pattern2 = "\"" + fieldName + "\": \"";
            int start = json.indexOf(pattern1);
            if (start == -1) start = json.indexOf(pattern2);
            if (start == -1) return "";
            start = json.indexOf("\"", start + fieldName.length() + 2) + 1;
            int end = json.indexOf("\"", start);
            if (end > start) return json.substring(start, end);
        } catch (Exception e) {
            Log.w(TAG, "提取JSON字段失败: " + fieldName);
        }
        return "";
    }

    public WarningLevel getWarningLevel() { return currentLevel; }
    public String getWarningMessage() { return lastWarningMessage; }
    public int getWarningCount() { return distractionCount; }
    public int getTotalDistractionCount() { return totalDistractionCount; }
    public String getLastAiActivity() { return lastAiActivity; }
    public boolean isLastAiFocused() { return lastAiFocused; }

    public String getDistractionHistory() {
        if (distractionHistoryList.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int count = Math.min(distractionHistoryList.size(), 10);
        for (int i = distractionHistoryList.size() - count; i < distractionHistoryList.size(); i++) {
            sb.append(distractionHistoryList.get(i));
            if (i < distractionHistoryList.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }

    public void clearHistory() { distractionHistoryList.clear(); }

    public void setWhitelist(java.util.Set<String> whitelist) { this.currentWhitelist = whitelist; }

    private void enableAccessibilityLockMode(long duration, String reason, String advice) {
        AppMonitorService service = AppMonitorService.getInstance();
        if (service != null) {
            service.updateWhitelist(currentWhitelist);
            service.activateLockScreen();
            Log.i(TAG, "🔒 已激活锁机界面，白名单: " + currentWhitelist.size() + " 个应用");
        } else {
            Log.w(TAG, "⚠️ AppMonitorService未运行，无法激活锁机界面");
        }
    }

    public void disableAccessibilityLockMode() {
        AppMonitorService service = AppMonitorService.getInstance();
        if (service != null) {
            service.disableLockMode();
            Log.i(TAG, "🔓 已禁用AccessibilityService锁机模式");
        }
        isLocked = false;
    }

    private String getAppLabel(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "";
        try {
            return context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName.substring(packageName.lastIndexOf('.') + 1);
        }
    }

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

    private void logDistractionEvent(String foregroundApp, String aiVision) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
        String activity = (lastAiActivity != null && !lastAiActivity.isEmpty()) ? lastAiActivity : "未知行为";
        String appInfo = (foregroundApp != null && !foregroundApp.isEmpty()) ? getAppLabel(foregroundApp) : "";
        String record = "• [" + timestamp + "] " + activity;
        if (!appInfo.isEmpty() && !activity.contains(appInfo)) {
            record += " (App: " + appInfo + ")";
        }
        distractionHistoryList.add(record);
        saveDistractionHistory();
        saveDistractionToDatabase(activity, foregroundApp, aiVision);
    }
}