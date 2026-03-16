package com.example.mindflow.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无障碍服务 - 实时监控前台应用和屏幕内容
 * 
 * 功能：
 * 1. 监听窗口状态变化
 * 2. 获取当前前台应用包名
 * 3. 读取屏幕上的文字内容（类似豆包的屏幕理解）
 * 4. 通知 FocusService 进行分心检测
 * 
 * 注意：需要用户手动在系统设置中开启无障碍权限
 */
public class AppMonitorService extends AccessibilityService {
    private static final String TAG = "AppMonitorService";
    
    public static final String ACTION_SCREEN_CONTENT = "com.example.mindflow.SCREEN_CONTENT";
    public static final String ACTION_SERVICE_STATUS = "com.example.mindflow.SERVICE_STATUS";
    public static final String EXTRA_CONTENT = "content";
    public static final String EXTRA_PACKAGE = "package_name";
    public static final String EXTRA_PAGE_URL = "page_url";
    public static final String EXTRA_PAGE_DOMAIN = "page_domain";
    public static final String EXTRA_PAGE_TITLE = "page_title";
    public static final String EXTRA_SEARCH_QUERY = "search_query";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_METHOD = "method";
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b");
    
    private static AppMonitorService instance;
    private String currentPackageName = "";
    private String lastScreenContent = "";
    private String lastPageUrl = "";
    private String lastPageDomain = "";
    private String lastPageTitle = "";
    private String lastSearchQuery = "";
    private long lastContentTime = 0;
    private int contentReadCount = 0;
    private static final long CONTENT_INTERVAL = 3000; // 3秒读取一次
    
    private FocusService focusService;
    private boolean isBound = false;
    
    // === 锁机状态 ===
    private boolean isLockScreenActive = false;  // 锁机是否激活（唯一标志）
    private Set<String> whitelist = new HashSet<>();  // 白名单应用
    private long lockDuration = 60000L;  // 锁机时长
    private String lockReason = "";  // 锁机原因
    
    // === 缓冲时间 ===
    private static final long BUFFER_TIME_MS = 6000L;  // 6秒缓冲（锁机结束后）
    private long bufferEndTime = 0;  // 缓冲结束时间
    private boolean isInBufferPeriod = false;  // 是否在缓冲期
    
    // === 临时允许的应用（从锁机界面打开白名单应用时使用）===
    private String temporaryAllowedApp = null;
    private long temporaryAllowedExpireTime = 0;
    private boolean allowSeenTarget = false;  // 是否已经真正进入过目标白名单App
    private static final long ALLOW_WINDOW_MS = 3000; // 3秒启动窗口（覆盖慢设备启动过渡）
    
    // === 节流：防止频繁触发show() ===
    private long lastNotifyTime = 0;
    private static final long NOTIFY_THROTTLE_MS = 500; // 500ms内不重复通知
    
    // === 看门狗Handler ===
    private Handler overlayHandler = new Handler(Looper.getMainLooper());
    private final Runnable temporaryAllowExpiryCheck = new Runnable() {
        @Override
        public void run() {
            if (temporaryAllowedApp == null) {
                return;
            }
            if (allowSeenTarget) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now < temporaryAllowedExpireTime) {
                long delay = Math.max(50, temporaryAllowedExpireTime - now + 50);
                overlayHandler.postDelayed(this, delay);
                return;
            }

            String pkg = basePkg(currentPackageName);
            clearTemporaryAllowedApp();

            if (isLockScreenActive && !isDeviceLocked() && !pkg.contains("mindflow") && !isInWhitelistOrAllowed(pkg)) {
                Log.w(TAG, "🚨 临时放行到期且仍不在白名单，强制拉回锁机: " + pkg);
                notifyShowLockOverlay();
            }
        }
    };
    
    public static AppMonitorService getInstance() {
        return instance;
    }

    private static final class PageContextSnapshot {
        final String pageUrl;
        final String pageDomain;
        final String pageTitle;
        final String searchQuery;

        PageContextSnapshot(String pageUrl, String pageDomain, String pageTitle, String searchQuery) {
            this.pageUrl = pageUrl;
            this.pageDomain = pageDomain;
            this.pageTitle = pageTitle;
            this.searchQuery = searchQuery;
        }
    }

    private static final class PageContextCollector {
        String urlCandidate = "";
        String titleCandidate = "";
        String searchQuery = "";

        void maybeCapture(String viewId, CharSequence value, boolean editable) {
            if (value == null) return;
            String text = value.toString().trim();
            if (text.isEmpty()) return;

            String lowerId = viewId == null ? "" : viewId.toLowerCase();
            String lowerText = text.toLowerCase();

            if (urlCandidate.isEmpty() && looksLikeUrl(text)) {
                urlCandidate = text;
            }
            if (containsAny(lowerId, "url", "address", "omnibox", "location", "search_box")) {
                if (looksLikeUrl(text) || lowerText.contains(".com") || lowerText.contains(".cn")
                        || lowerText.contains(".org") || lowerText.contains(".net")) {
                    urlCandidate = text;
                } else if (searchQuery.isEmpty()) {
                    searchQuery = text;
                }
            }
            if (containsAny(lowerId, "title", "toolbar", "web_title", "tab_title")
                    && titleCandidate.isEmpty() && text.length() >= 4 && !looksLikeUrl(text)) {
                titleCandidate = text;
            }
            if (editable && searchQuery.isEmpty() && !looksLikeUrl(text) && text.length() >= 2) {
                searchQuery = text;
            }
            if (titleCandidate.isEmpty() && text.length() >= 8 && !looksLikeUrl(text)
                    && !editable && !containsAny(lowerId, "button", "input", "edit")) {
                titleCandidate = text;
            }
        }
    }
    
    public static boolean isRunning() {
        return instance != null;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "AppMonitorService 创建");
    }
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        
        // 配置监听事件类型（必须订阅全，否则离开App时的事件收不到）
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED 
                        | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                        | AccessibilityEvent.TYPE_VIEW_FOCUSED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                   | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                   | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                   | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;  // 拦截按键
        info.notificationTimeout = 100;
        setServiceInfo(info);
        
        // 绑定 FocusService
        bindFocusService();
        
        // 广播服务状态
        broadcastServiceStatus("已连接", "AccessibilityService");
        
        Log.i(TAG, "AppMonitorService 已连接，屏幕内容读取已启用");
    }
    
    /**
     * 广播服务状态
     */
    private void broadcastServiceStatus(String status, String method) {
        Intent intent = new Intent(ACTION_SERVICE_STATUS);
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_METHOD, method);
        intent.putExtra("read_count", contentReadCount);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        
        int eventType = event.getEventType();
        CharSequence packageNameCs = event.getPackageName();

        if (isDeviceLocked()) {
            if (packageNameCs != null) {
                currentPackageName = packageNameCs.toString();
            }
            Log.d(TAG, "🔐 系统锁屏中，跳过锁机看门狗");
            return;
        }
        
        // === 锁机界面激活时拦截手势事件 ===
        if (isLockScreenActive) {
            if (eventType == AccessibilityEvent.TYPE_GESTURE_DETECTION_START ||
                eventType == AccessibilityEvent.TYPE_GESTURE_DETECTION_END) {
                Log.d(TAG, "🚫 锁机界面拦截手势事件");
                // 通知显示锁机遮罩
                notifyShowLockOverlay();
                return;
            }
        }
        
        if (packageNameCs != null) {
            String packageName = packageNameCs.toString();
            
            // =====================================================
            // === 看门狗核心逻辑（番茄ToDo架构 - Activity版） ===
            // =====================================================
            // 只监听窗口切换事件
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                String pkg = basePkg(packageName);
                
                // 【防死循环 - 至关重要】检测到本应用时，跳过看门狗逻辑
                if (!pkg.contains("mindflow")) {
                    // 检查锁机是否激活（核心判断依据）
                    if (isLockScreenActive) {
                        
                        // 处理临时放行状态机
                        handleTemporaryAllowState(pkg);
                        
                        // 判断是否在白名单
                        boolean inWhitelist = isInWhitelistOrAllowed(pkg);
                        
                        if (inWhitelist) {
                            // 【白名单应用】-> 不做任何操作，Activity已在后台
                            Log.d(TAG, "✅ 白名单应用: " + pkg);
                        } else {
                            // 【非白名单】-> 用FullScreenIntent拉起锁机Activity
                            Log.w(TAG, "🚨 非白名单: " + pkg + "，看门狗抓回！");
                            notifyShowLockOverlay();
                            
                            // 针对特定界面的防御（Back键）
                            if (isSystemUi(pkg) || isRecents(pkg) || pkg.contains("settings")) {
                                performGlobalAction(GLOBAL_ACTION_BACK);
                                overlayHandler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 100);
                            }
                        }
                    }
                } else {
                    Log.d(TAG, "🛡️ 检测到本应用，跳过看门狗逻辑");
                }
            }
            
            // 过滤输入法等必须放行的系统组件（仅用于FocusService通知，但不影响锁机检测）
            // 注意：SystemUI不在这里过滤，锁机时要检测它
            if (isAlwaysAllowedSystem(packageName) && !isLockScreenActive) return;
            
            // 包名变化时通知FocusService
            if (!packageName.equals(currentPackageName)) {
                currentPackageName = packageName;
                Log.d(TAG, "前台应用切换: " + packageName);
                
                if (focusService != null) {
                    focusService.onForegroundAppChanged(packageName);
                }
            }
        }
        
        // 定时读取屏幕内容
        long now = System.currentTimeMillis();
        if (now - lastContentTime >= CONTENT_INTERVAL) {
            lastContentTime = now;
            readScreenContent();
        }
    }
    
    /**
     * 读取当前屏幕上的所有文字内容
     */
    private void readScreenContent() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;
        
        StringBuilder content = new StringBuilder();
        PageContextCollector collector = new PageContextCollector();
        extractAllText(rootNode, content, collector);
        PageContextSnapshot snapshot = buildPageContextSnapshot(content.toString(), collector);
        rootNode.recycle();
        
        String screenContent = content.toString().trim();
        if (screenContent.length() > 10 && !screenContent.equals(lastScreenContent)) {
            lastScreenContent = screenContent;
            lastPageUrl = snapshot.pageUrl;
            lastPageDomain = snapshot.pageDomain;
            lastPageTitle = snapshot.pageTitle;
            lastSearchQuery = snapshot.searchQuery;
            contentReadCount++;
            
            // 广播屏幕内容给 FocusService
            Intent intent = new Intent(ACTION_SCREEN_CONTENT);
            intent.putExtra(EXTRA_CONTENT, screenContent);
            intent.putExtra(EXTRA_PACKAGE, currentPackageName);
            intent.putExtra(EXTRA_PAGE_URL, lastPageUrl);
            intent.putExtra(EXTRA_PAGE_DOMAIN, lastPageDomain);
            intent.putExtra(EXTRA_PAGE_TITLE, lastPageTitle);
            intent.putExtra(EXTRA_SEARCH_QUERY, lastSearchQuery);
            intent.putExtra("content_length", screenContent.length());
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            
            Log.d(TAG, "屏幕内容已读取[" + contentReadCount + "]: " + screenContent.substring(0, Math.min(100, screenContent.length())) + "...");
        }
    }
    
    /**
     * 递归提取所有文字
     */
    private void extractAllText(AccessibilityNodeInfo node, StringBuilder sb) {
        extractAllText(node, sb, null);
    }

    private void extractAllText(AccessibilityNodeInfo node, StringBuilder sb, PageContextCollector collector) {
        if (node == null) return;
        
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            sb.append(text).append(" ");
        }

        if (collector != null) {
            collector.maybeCapture(node.getViewIdResourceName(), text, node.isEditable());
        }
        
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            sb.append(desc).append(" ");
        }

        if (collector != null) {
            collector.maybeCapture(node.getViewIdResourceName(), desc, node.isEditable());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                collector.maybeCapture(node.getViewIdResourceName(), node.getHintText(), true);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                collector.maybeCapture(node.getViewIdResourceName(), node.getPaneTitle(), false);
            }
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                extractAllText(child, sb, collector);
                child.recycle();
            }
        }
    }
    
    /**
     * 主动获取屏幕内容（供外部调用）
     */
    public String getScreenContent() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return "";
        
        StringBuilder content = new StringBuilder();
        PageContextCollector collector = new PageContextCollector();
        extractAllText(rootNode, content, collector);
        PageContextSnapshot snapshot = buildPageContextSnapshot(content.toString(), collector);
        rootNode.recycle();
        lastPageUrl = snapshot.pageUrl;
        lastPageDomain = snapshot.pageDomain;
        lastPageTitle = snapshot.pageTitle;
        lastSearchQuery = snapshot.searchQuery;
        return content.toString().trim();
    }

    public String getLastPageUrl() {
        return lastPageUrl;
    }

    public String getLastPageDomain() {
        return lastPageDomain;
    }

    public String getLastPageTitle() {
        return lastPageTitle;
    }

    public String getLastSearchQuery() {
        return lastSearchQuery;
    }

    private PageContextSnapshot buildPageContextSnapshot(String screenContent, PageContextCollector collector) {
        String pageUrl = safeText(collector != null ? collector.urlCandidate : "");
        String pageTitle = safeText(collector != null ? collector.titleCandidate : "");
        String searchQuery = safeText(collector != null ? collector.searchQuery : "");
        String pageDomain = extractDomain(pageUrl);

        String normalizedScreen = safeText(screenContent).toLowerCase();
        if (pageDomain.isEmpty()) {
            pageDomain = extractDomain(screenContent);
        }
        if (pageTitle.isEmpty() && !normalizedScreen.isEmpty()) {
            pageTitle = guessTitleFromContent(screenContent);
        }
        if (searchQuery.isEmpty() && containsAny(normalizedScreen, "搜索", "百度一下", "google search")) {
            searchQuery = guessSearchQueryFromContent(screenContent);
        }

        return new PageContextSnapshot(pageUrl, pageDomain, pageTitle, searchQuery);
    }

    private static String guessTitleFromContent(String screenContent) {
        if (screenContent == null || screenContent.trim().isEmpty()) return "";
        String[] parts = screenContent.trim().split("\\s+");
        StringBuilder title = new StringBuilder();
        for (String part : parts) {
            if (part.length() < 2 || looksLikeUrl(part)) {
                continue;
            }
            if (title.length() > 0) {
                title.append(' ');
            }
            title.append(part);
            if (title.length() >= 40) {
                break;
            }
        }
        return title.toString().trim();
    }

    private static String guessSearchQueryFromContent(String screenContent) {
        if (screenContent == null || screenContent.trim().isEmpty()) return "";
        String cleaned = screenContent.replace('\n', ' ').trim();
        if (cleaned.length() <= 40) {
            return cleaned;
        }
        return cleaned.substring(0, 40).trim();
    }

    private static String extractDomain(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        Matcher matcher = DOMAIN_PATTERN.matcher(raw.toLowerCase());
        while (matcher.find()) {
            String domain = matcher.group();
            if (domain.contains("android") || domain.contains("miui") || domain.contains("huawei")) {
                continue;
            }
            return domain;
        }
        return "";
    }

    private static boolean looksLikeUrl(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String lower = text.trim().toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.contains(".com") || lower.contains(".cn")
                || lower.contains(".org") || lower.contains(".net");
    }

    private static String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private static boolean containsAny(String text, String... keywords) {
        if (text == null || text.isEmpty()) return false;
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    @Override
    public void onInterrupt() {
        Log.w(TAG, "AppMonitorService 中断");
    }
    
    /**
     * 拦截Home键 - 锁机模式下阻止退出
     */
    @Override
    protected boolean onKeyEvent(android.view.KeyEvent event) {
        // 只有锁机界面激活时才拦截按键
        if (isLockScreenActive) {
            int keyCode = event.getKeyCode();
            if (keyCode == android.view.KeyEvent.KEYCODE_HOME ||
                keyCode == android.view.KeyEvent.KEYCODE_APP_SWITCH ||
                keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                Log.d(TAG, "🚫 锁机界面拦截按键: " + keyCode);
                return true;  // 拦截
            }
        }
        return super.onKeyEvent(event);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        Log.d(TAG, "AppMonitorService 销毁");
    }
    
    /**
     * 必须永远放行的系统组件（输入法/权限弹窗/安装器）
     */
    private boolean isAlwaysAllowedSystem(String pkg) {
        if (pkg == null) return false;
        return pkg.contains("inputmethod") ||
               pkg.contains("keyguard") ||
               pkg.contains("keyboard") ||
               pkg.contains("permissioncontroller") ||
               pkg.contains("packageinstaller");
    }

    private boolean isDeviceLocked() {
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return keyguardManager.isDeviceLocked();
        }
        return keyguardManager.isKeyguardLocked();
    }
    
    /**
     * SystemUI（通知栏/最近任务/状态栏）- 锁机时要阻止！
     */
    private boolean isSystemUi(String pkg) {
        return "com.android.systemui".equals(pkg);
    }
    
    /**
     * 检测是否是桌面启动器或最近任务界面（多机型兼容）
     */
    private boolean isLauncherOrRecents(String packageName) {
        if (packageName == null) return false;
        String pkg = packageName.toLowerCase();
        
        // 通用桌面启动器
        if (pkg.contains("launcher") || pkg.contains("home")) return true;
        
        // 最近任务/任务切换
        if (pkg.contains("recents") || pkg.contains("recent") || 
            pkg.contains("task") || pkg.contains("switch")) return true;
        
        // Android原生
        if (pkg.equals("com.android.launcher") || 
            pkg.equals("com.android.launcher2") ||
            pkg.equals("com.android.launcher3") ||
            pkg.equals("com.google.android.apps.nexuslauncher")) return true;
        
        // 华为/荣耀
        if (pkg.equals("com.huawei.android.launcher") ||
            pkg.equals("com.huawei.home") ||
            pkg.equals("com.hihonor.android.launcher")) return true;
        
        // 小米/红米
        if (pkg.equals("com.miui.home") ||
            pkg.equals("com.mi.android.globallauncher")) return true;
        
        // OPPO/一加/realme
        if (pkg.equals("com.oppo.launcher") ||
            pkg.equals("com.oneplus.launcher") ||
            pkg.equals("com.realme.launcher")) return true;
        
        // vivo/iQOO
        if (pkg.equals("com.bbk.launcher2") ||
            pkg.equals("com.vivo.launcher")) return true;
        
        // 三星
        if (pkg.equals("com.sec.android.app.launcher") ||
            pkg.equals("com.samsung.android.launcher")) return true;
        
        // 注意：SystemUI不放这里，用isSystemUi()单独判断
        return false;
    }
    
    /**
     * 检测是否是最近任务界面（不含桌面）
     */
    private boolean isRecents(String packageName) {
        if (packageName == null) return false;
        String pkg = packageName.toLowerCase();
        return pkg.contains("recents") || pkg.contains("recent") || 
               pkg.contains("task") || pkg.contains("switch");
    }
    
    /**
     * 【看门狗辅助】处理临时放行状态机
     */
    private void handleTemporaryAllowState(String pkg) {
        if (temporaryAllowedApp == null) return;
        
        if (pkg.equals(temporaryAllowedApp)) {
            allowSeenTarget = true; // 已经真正进入过目标白名单App
        } else {
            long now = System.currentTimeMillis();
            // A) 超过启动窗口还没进目标App → 清掉
            if (!allowSeenTarget && now > temporaryAllowedExpireTime) {
                Log.w(TAG, "⏰ 启动窗口过期，清除放行");
                clearTemporaryAllowedApp();
            }
            // B) 已经进过目标App了，但现在离开了 → 立即清掉
            if (allowSeenTarget) {
                Log.w(TAG, "🚨 离开白名单App: " + temporaryAllowedApp + " → " + pkg);
                clearTemporaryAllowedApp();
            }
        }
    }
    
    /**
     * 【看门狗辅助】判断是否在白名单或临时放行
     */
    private boolean isInWhitelistOrAllowed(String pkg) {
        // 1. 必须放行的系统组件（输入法等）
        if (isAlwaysAllowedSystem(pkg)) return true;
        
        // 2. 临时放行的白名单应用
        if (temporaryAllowedApp != null) {
            long now = System.currentTimeMillis();
            boolean inAllowWindow = now <= temporaryAllowedExpireTime;
            
            // 目标白名单App本身
            if (pkg.equals(temporaryAllowedApp)) return true;
            
            // 启动窗口内的过渡（桌面/SystemUI）
            if (inAllowWindow && !allowSeenTarget && (isLauncherOrRecents(pkg) || isSystemUi(pkg))) {
                return true;
            }
        }
        
        // 3. 永久白名单
        if (whitelist.contains(pkg)) return true;
        
        // 4. 前缀匹配白名单
        for (String allowed : whitelist) {
            if (pkg.startsWith(allowed + ".") || allowed.startsWith(pkg + ".")) {
                return true;
            }
        }
        
        return false;
    }
    
    private void bindFocusService() {
        Intent intent = new Intent(this, FocusService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            FocusService.FocusBinder binder = (FocusService.FocusBinder) service;
            focusService = binder.getService();
            isBound = true;
            Log.d(TAG, "已绑定 FocusService");
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            focusService = null;
            isBound = false;
            Log.d(TAG, "FocusService 断开连接");
        }
    };
    
    public String getCurrentPackageName() {
        return currentPackageName;
    }
    
    // =====================================================
    // === 番茄todo风格锁机模式实现 ===
    // =====================================================
    
    /**
     * 激活锁机（设置白名单和参数）
     */
    public void enableLockMode(Set<String> whitelistApps, long duration, String reason, String advice, int warnCount, String history) {
        this.isLockScreenActive = true;
        this.whitelist = new HashSet<>(whitelistApps);
        this.whitelist.add(getPackageName());
        this.whitelist.add("com.example.mindflow");
        this.lockDuration = duration;
        this.lockReason = reason;
        startBufferPeriod();
        Log.i(TAG, "🔒 锁机已激活");
    }
    
    /**
     * 停用锁机
     */
    public void disableLockMode() {
        this.isLockScreenActive = false;
        Log.i(TAG, "🔓 锁机已停用");
    }
    
    /**
     * 激活锁机界面（分心3次后调用）
     */
    public void activateLockScreen() {
        this.isLockScreenActive = true;
        Log.i(TAG, "🔒 锁机界面已激活");
    }
    
    /**
     * 停用锁机界面（锁机倒计时结束后调用）
     */
    public void deactivateLockScreen() {
        this.isLockScreenActive = false;
        startBufferPeriod();  // 启动缓冲期
        Log.i(TAG, "🔓 锁机界面已停用，进入缓冲期");
    }

    /**
     * 重置分心次数为0
     */
    public void resetDistractionCount() {
        // 分心次数由 DistractionManager 管理
        Log.i(TAG, "🔄 分心次数重置请求已发送");
    }

    /**
     * 启动缓冲期（6秒内不检测分心）
     */
    public void startBufferPeriod() {
        this.bufferEndTime = System.currentTimeMillis() + BUFFER_TIME_MS;
        this.isInBufferPeriod = true;
        Log.i(TAG, "⏳ 缓冲期开始，6秒内不检测分心");
    }

    /**
     * 检查是否在缓冲期内
     */
    public boolean isInBufferPeriod() {
        if (isInBufferPeriod && System.currentTimeMillis() < bufferEndTime) {
            return true;
        }
        if (isInBufferPeriod) {
            isInBufferPeriod = false;
            Log.i(TAG, "✅ 缓冲期结束，开始正常检测");
        }
        return false;
    }

    /**
     * 获取白名单应用列表
     */
    public Set<String> getWhitelist() {
        return new HashSet<>(whitelist);
    }
    
    /**
     * 通知显示锁机遮罩（带节流，500ms内不重复通知）
     */
    private void notifyShowLockOverlay() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotifyTime < NOTIFY_THROTTLE_MS) {
            Log.d(TAG, "📢 节流跳过：距上次通知仅 " + (now - lastNotifyTime) + "ms");
            return;
        }
        lastNotifyTime = now;
        
        Log.w(TAG, "📢 触发显示锁机界面！");
        
        // 检查锁机是否已显示（优先检查悬浮窗）
        if (LockWindowService.isActive()) {
            // 悬浮窗已激活，恢复显示
            LockWindowService service = LockWindowService.getInstance();
            if (service != null && service.isLockViewMinimized()) {
                service.restoreLockView();
                Log.d(TAG, "🔒 恢复悬浮窗锁机界面");
            }
            return;
        }
        
        if (com.example.mindflow.ui.lock.LockScreenActivity.isActive()) {
            Log.d(TAG, "🔒 LockScreenActivity已显示，跳过");
            return;
        }
        
        // 启动锁机（优先使用悬浮窗）
        launchLockWindow();
    }
    
    /**
     * 【核心】启动悬浮窗锁机服务
     */
    private void launchLockWindow() {
        try {
            Intent intent = new Intent(this, LockWindowService.class);
            intent.putExtra("duration", lockDuration);
            intent.putExtra("reason", lockReason != null ? lockReason : "检测到非白名单应用");
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Log.w(TAG, "🚀 已启动LockWindowService");
        } catch (Exception e) {
            Log.e(TAG, "启动LockWindowService失败: " + e.getMessage());
            // 兆底：使用 Activity
            launchLockScreenActivity();
        }
    }
    
    /**
     * 【兆底】拉起 LockScreenActivity
     * 使用 FullScreenIntent 通知绕过 Android 10+ 后台启动限制
     */
    private void launchLockScreenActivity() {
        // 节流检查
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotifyTime < NOTIFY_THROTTLE_MS) return;
        lastNotifyTime = now;
        
        Context context = this;
        Intent fullScreenIntent = new Intent(context, com.example.mindflow.ui.lock.LockScreenActivity.class);
        fullScreenIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK | 
            Intent.FLAG_ACTIVITY_SINGLE_TOP | 
            Intent.FLAG_ACTIVITY_CLEAR_TOP |
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        );
        fullScreenIntent.putExtra("duration", lockDuration);
        fullScreenIntent.putExtra("reason", lockReason != null ? lockReason : "检测到非白名单应用");
        
        // 【关键】使用 FullScreenIntent 通知绕过 Android 10+ 后台启动限制
        try {
            android.app.PendingIntent fullScreenPendingIntent = android.app.PendingIntent.getActivity(
                context, 
                (int) System.currentTimeMillis(),  // 唯一请求码
                fullScreenIntent, 
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
            );

            String channelId = "lock_fullscreen_channel";
            android.app.NotificationManager notificationManager = 
                (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            
            // 创建高优先级渠道（Android 8.0+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    channelId, 
                    "锁机全屏通知", 
                    android.app.NotificationManager.IMPORTANCE_HIGH
                );
                channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
                channel.setBypassDnd(true);  // 绕过勿扰模式
                notificationManager.createNotificationChannel(channel);
            }

            // 构建全屏通知（这是绕过 Android 10+ 限制的关键）
            androidx.core.app.NotificationCompat.Builder builder = 
                new androidx.core.app.NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setContentTitle("专注模式")
                    .setContentText("正在返回锁机界面...")
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                    .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)  // 使用来电类别
                    .setFullScreenIntent(fullScreenPendingIntent, true)  // 【关键】全屏Intent
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setTimeoutAfter(3000);  // 3秒后自动消失

            // 发送通知（会立即触发全屏Intent）
            notificationManager.notify(9999, builder.build());
            
            // 立即取消通知（我们只需要触发效果，不需要显示通知）
            overlayHandler.post(() -> {
                notificationManager.cancel(9999);
            });
            
            Log.w(TAG, "🚀 已发送FullScreenIntent通知拉起LockScreenActivity");
        } catch (Exception e) {
            Log.e(TAG, "FullScreenIntent启动失败: " + e.getMessage());
            
            // 兜底：直接启动（可能在某些有悬浮窗权限的设备上有效）
            try {
                context.startActivity(fullScreenIntent);
                Log.w(TAG, "🚀 兜底：直接启动LockScreenActivity");
            } catch (Exception e2) {
                Log.e(TAG, "直接启动也失败: " + e2.getMessage());
            }
        }
    }
    
    
    /**
     * 更新白名单（锁机期间可动态更新）
     */
    public void updateWhitelist(Set<String> newWhitelist) {
        this.whitelist = new HashSet<>(newWhitelist);
        this.whitelist.add(getPackageName());
        this.whitelist.add("com.example.mindflow");
        Log.i(TAG, "🔄 白名单已更新，共 " + whitelist.size() + " 个应用");
    }
    
    /**
     * 设置临时允许的应用（从锁机界面打开白名单应用时调用）
     * 有效期60秒，防止应用启动时被立即拉回
     */
    public void setTemporaryAllowedApp(String packageName) {
        this.temporaryAllowedApp = basePkg(packageName);
        this.temporaryAllowedExpireTime = System.currentTimeMillis() + ALLOW_WINDOW_MS;
        this.allowSeenTarget = false;  // 重置：还没真正进入目标App
        overlayHandler.removeCallbacks(temporaryAllowExpiryCheck);
        overlayHandler.postDelayed(temporaryAllowExpiryCheck, ALLOW_WINDOW_MS + 80);
        Log.i(TAG, "🔓 临时允许应用: " + temporaryAllowedApp + " (" + ALLOW_WINDOW_MS + "ms窗口)");
    }
    
    /**
     * 清除临时允许的应用（用户返回锁机界面时调用）
     */
    public void clearTemporaryAllowedApp() {
        this.temporaryAllowedApp = null;
        this.temporaryAllowedExpireTime = 0;
        this.allowSeenTarget = false;
        overlayHandler.removeCallbacks(temporaryAllowExpiryCheck);
        Log.i(TAG, "🔒 清除临时允许应用");
    }
    
    /**
     * 提取基础包名（去掉:进程后缀，如com.tencent.mm:push -> com.tencent.mm）
     */
    private static String basePkg(String s) {
        if (s == null) return "";
        int idx = s.indexOf(':');
        return idx > 0 ? s.substring(0, idx) : s;
    }
    
    /**
     * 检查锁机是否激活
     */
    public boolean isLockScreenActive() {
        return isLockScreenActive;
    }
    
}
