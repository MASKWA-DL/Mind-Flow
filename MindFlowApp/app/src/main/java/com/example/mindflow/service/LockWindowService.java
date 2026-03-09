package com.example.mindflow.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.mindflow.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 番茄ToDo级别的悬浮窗锁机服务
 * 
 * 核心原理：使用 WindowManager 创建系统级悬浮窗，而非 Activity
 * 优势：
 * 1. 不受 Activity 生命周期影响
 * 2. 可以覆盖导航栏，阻止上滑手势
 * 3. 白名单抽屉在同一个 Window 内，无需跳转
 */
public class LockWindowService extends Service {
    private static final String TAG = "LockWindowService";
    private static final String CHANNEL_ID = "lock_window_channel";
    private static final int NOTIFICATION_ID = 2001;
    
    private static LockWindowService instance;
    
    private WindowManager windowManager;
    private View lockView;
    private WindowManager.LayoutParams windowParams;
    
    // UI 组件
    private TextView tvTimer;
    private TextView tvSessionId;
    private TextView tvReason;
    private ProgressBar progressBar;
    private View whitelistContainer;
    private CardView cardWhitelist;
    private RecyclerView rvWhitelist;
    
    // 状态
    private String currentSessionId;
    private long totalDurationMs = 60000L;
    private long remainingMs = 60000L;
    private CountDownTimer countDownTimer;
    private boolean isLockActive = false;
    private Set<String> whitelist = new HashSet<>();
    private String lockReason = "专注模式已开启";
    
    // 唤醒锁
    private PowerManager.WakeLock wakeLock;
    
    // 白名单适配器
    private WhitelistAdapter whitelistAdapter;
    private BroadcastReceiver screenStateReceiver;
    private boolean hiddenForDeviceUnlock = false;
    
    public static LockWindowService getInstance() {
        return instance;
    }
    
    public static boolean isActive() {
        return instance != null && instance.isLockActive;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        registerScreenStateReceiver();
        Log.i(TAG, "🔒 LockWindowService 创建");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            long duration = intent.getLongExtra("duration", 60000L);
            String reason = intent.getStringExtra("reason");
            
            totalDurationMs = duration;
            remainingMs = duration;
            lockReason = reason != null ? reason : "分心次数过多，需要冷却";
        }
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification());
        
        // 加载白名单
        loadWhitelist();
        
        // 显示锁机界面
        showLock();
        
        return START_STICKY;
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterScreenStateReceiver();
        hideLock();
        releaseWakeLock();
        instance = null;
        Log.i(TAG, "🔓 LockWindowService 销毁");
    }
    
    // ==================== 核心：显示/隐藏锁机界面 ====================
    
    private void showLock() {
        if (lockView != null) {
            Log.d(TAG, "锁机界面已存在，跳过");
            return;
        }
        
        // 创建窗口参数
        windowParams = createWindowParams();
        
        // 加载布局
        lockView = LayoutInflater.from(this).inflate(R.layout.window_lock_layer, null);
        
        // 初始化组件
        initViews();
        
        // 生成锁机码
        currentSessionId = UUID.randomUUID().toString().substring(0, 10);
        tvSessionId.setText("锁机 " + currentSessionId);
        tvReason.setText(lockReason);
        
        // 设置沉浸式（关键：防止上滑）
        applyImmersiveMode();
        
        // 添加到窗口
        try {
            windowManager.addView(lockView, windowParams);
            isLockActive = true;
            
            // 启动倒计时
            startCountdown();
            
            // 获取唤醒锁
            acquireWakeLock();
            
            // 通知看门狗
            AppMonitorService service = AppMonitorService.getInstance();
            if (service != null) {
                service.activateLockScreen();
            }
            
            Log.i(TAG, "🔒 锁机界面已显示，时长: " + (totalDurationMs / 1000) + "秒");
        } catch (Exception e) {
            Log.e(TAG, "添加悬浮窗失败: " + e.getMessage());
        }
    }
    
    private void hideLock() {
        if (lockView != null && windowManager != null) {
            try {
                windowManager.removeView(lockView);
            } catch (Exception e) {
                Log.e(TAG, "移除悬浮窗失败: " + e.getMessage());
            }
            lockView = null;
        }
        
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        
        isLockActive = false;
        hiddenForDeviceUnlock = false;
    }
    
    // ==================== 窗口参数（关键配置）====================
    
    private WindowManager.LayoutParams createWindowParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        
        // 类型：系统级悬浮窗
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            params.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        
        // 强制全屏
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        
        // 关键 Flags：覆盖导航栏，防止上滑
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS  // 延伸到屏幕外，覆盖刘海和导航条
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        
        params.format = PixelFormat.TRANSLUCENT;
        params.gravity = Gravity.TOP | Gravity.START;
        
        // 刘海屏适配
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = 
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        
        return params;
    }
    
    // ==================== 初始化视图 ====================
    
    private void initViews() {
        tvTimer = lockView.findViewById(R.id.tv_timer);
        tvSessionId = lockView.findViewById(R.id.tv_session_id);
        tvReason = lockView.findViewById(R.id.tv_reason);
        progressBar = lockView.findViewById(R.id.progress_bar);
        whitelistContainer = lockView.findViewById(R.id.fl_whitelist_container);
        cardWhitelist = lockView.findViewById(R.id.card_whitelist);
        rvWhitelist = lockView.findViewById(R.id.rv_whitelist_apps);
        
        // 设置白名单列表
        rvWhitelist.setLayoutManager(new GridLayoutManager(this, 4));
        whitelistAdapter = new WhitelistAdapter(this, this::onWhitelistAppClicked);
        rvWhitelist.setAdapter(whitelistAdapter);
        loadWhitelistApps();
        
        // 白名单按钮点击
        lockView.findViewById(R.id.btn_whitelist).setOnClickListener(v -> {
            showWhitelistDrawer();
        });
        
        // 点击抽屉背景关闭
        whitelistContainer.setOnClickListener(v -> {
            hideWhitelistDrawer();
        });
        
        // 阻止卡片内部点击事件传递
        cardWhitelist.setOnClickListener(v -> {});
    }
    
    // ==================== 白名单抽屉 ====================
    
    private void showWhitelistDrawer() {
        try {
            if (whitelistContainer == null || cardWhitelist == null) {
                Log.e(TAG, "showWhitelistDrawer() 失败: whitelistContainer/cardWhitelist 为 null");
                return;
            }
            whitelistContainer.setVisibility(View.VISIBLE);
            Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_in_bottom);
            cardWhitelist.startAnimation(slideUp);
        } catch (Exception e) {
            Log.e(TAG, "showWhitelistDrawer() 异常: " + e.getMessage());
            if (whitelistContainer != null) {
                whitelistContainer.setVisibility(View.VISIBLE);
            }
        }
    }
    
    private void hideWhitelistDrawer() {
        try {
            if (whitelistContainer == null || cardWhitelist == null) {
                Log.e(TAG, "hideWhitelistDrawer() 失败: whitelistContainer/cardWhitelist 为 null");
                if (whitelistContainer != null) {
                    whitelistContainer.setVisibility(View.GONE);
                }
                return;
            }
            Animation slideDown = AnimationUtils.loadAnimation(this, R.anim.slide_out_bottom);
            slideDown.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {}
                
                @Override
                public void onAnimationEnd(Animation animation) {
                    whitelistContainer.setVisibility(View.GONE);
                }
                
                @Override
                public void onAnimationRepeat(Animation animation) {}
            });
            cardWhitelist.startAnimation(slideDown);
        } catch (Exception e) {
            Log.e(TAG, "hideWhitelistDrawer() 异常: " + e.getMessage());
            if (whitelistContainer != null) {
                whitelistContainer.setVisibility(View.GONE);
            }
        }
    }
    
    private void onWhitelistAppClicked(String packageName) {
        Log.d(TAG, "📱 打开白名单应用: " + packageName);
        
        // 关闭抽屉
        hideWhitelistDrawer();
        
        // 最小化悬浮窗（变成1x1像素，不移除）
        minimizeLockView();
        
        // 通知看门狗临时放行
        AppMonitorService service = AppMonitorService.getInstance();
        if (service != null) {
            service.setTemporaryAllowedApp(packageName);
        }
        
        // 启动应用
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "启动应用失败: " + e.getMessage());
            Toast.makeText(this, "无法打开应用", Toast.LENGTH_SHORT).show();
            restoreLockView();
        }
    }
    
    /**
     * 最小化锁机窗口（用户打开白名单应用时）
     */
    private void minimizeLockView() {
        if (lockView != null && windowParams != null) {
            windowParams.width = 1;
            windowParams.height = 1;
            windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                windowManager.updateViewLayout(lockView, windowParams);
            } catch (Exception e) {
                Log.e(TAG, "最小化失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 恢复锁机窗口（用户离开白名单应用时）
     */
    public void restoreLockView() {
        if (lockView != null && windowParams != null && isLockActive) {
            windowParams.width = WindowManager.LayoutParams.MATCH_PARENT;
            windowParams.height = WindowManager.LayoutParams.MATCH_PARENT;
            windowParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            windowParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            try {
                windowManager.updateViewLayout(lockView, windowParams);
                applyImmersiveMode();
                Log.i(TAG, "🔒 锁机窗口已恢复");
            } catch (Exception e) {
                Log.e(TAG, "恢复失败: " + e.getMessage());
            }
        }
    }
    
    // ==================== 倒计时 ====================
    
    private void startCountdown() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        
        progressBar.setMax((int) (totalDurationMs / 1000));
        progressBar.setProgress((int) (remainingMs / 1000));
        
        countDownTimer = new CountDownTimer(remainingMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                remainingMs = millisUntilFinished;
                updateTimerDisplay();
            }
            
            @Override
            public void onFinish() {
                remainingMs = 0;
                updateTimerDisplay();
                endLock();
            }
        };
        countDownTimer.start();
    }
    
    private void updateTimerDisplay() {
        long seconds = remainingMs / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        
        String timeStr = String.format("%02d:%02d", minutes, seconds);
        if (tvTimer != null) {
            tvTimer.setText(timeStr);
        }
        if (progressBar != null) {
            progressBar.setProgress((int) (remainingMs / 1000));
        }
    }
    
    // ==================== 结束锁机 ====================
    
    private void endLock() {
        Log.i(TAG, "🔓 锁机时间到，结束锁机");
        
        // 通知看门狗
        AppMonitorService service = AppMonitorService.getInstance();
        if (service != null) {
            service.deactivateLockScreen();
        }
        
        // 广播锁机结束
        Intent intent = new Intent("com.example.mindflow.LOCK_ENDED");
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        
        // 停止服务
        stopSelf();
    }
    
    /**
     * 强制结束锁机（紧急情况）
     */
    public void forceEnd() {
        Log.w(TAG, "⚠️ 强制结束锁机");
        endLock();
    }
    
    // ==================== 白名单加载 ====================
    
    private void loadWhitelist() {
        SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
        whitelist = prefs.getStringSet("whitelist", new HashSet<>());
        whitelist = new HashSet<>(whitelist);
        whitelist.add(getPackageName());
        whitelist.add("com.example.mindflow");
        Log.d(TAG, "📋 加载白名单: " + whitelist.size() + " 个应用");
    }
    
    private void loadWhitelistApps() {
        List<WhitelistApp> apps = new ArrayList<>();
        PackageManager pm = getPackageManager();
        
        for (String pkg : whitelist) {
            if (pkg.equals(getPackageName()) || pkg.equals("com.example.mindflow")) {
                continue;
            }
            
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                Drawable icon = pm.getApplicationIcon(appInfo);
                String name = pm.getApplicationLabel(appInfo).toString();
                apps.add(new WhitelistApp(pkg, name, icon));
            } catch (PackageManager.NameNotFoundException e) {
                // 应用未安装
            }
        }
        
        whitelistAdapter.setApps(apps);
    }
    
    // ==================== 通知 ====================

    private void registerScreenStateReceiver() {
        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    prepareForDeviceUnlock();
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    restoreAfterDeviceUnlock();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenStateReceiver, filter);
    }

    private void unregisterScreenStateReceiver() {
        if (screenStateReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(screenStateReceiver);
        } catch (Exception e) {
            Log.w(TAG, "注销息屏接收器失败", e);
        }
        screenStateReceiver = null;
    }

    private void prepareForDeviceUnlock() {
        if (!isLockActive || lockView == null || windowParams == null) {
            return;
        }
        hiddenForDeviceUnlock = true;
        windowParams.width = 1;
        windowParams.height = 1;
        windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try {
            windowManager.updateViewLayout(lockView, windowParams);
            Log.i(TAG, "🔐 锁机悬浮层已让出系统解锁界面");
        } catch (Exception e) {
            Log.e(TAG, "让出系统解锁界面失败: " + e.getMessage());
        }
    }

    private void restoreAfterDeviceUnlock() {
        if (!hiddenForDeviceUnlock || !isLockActive || lockView == null || windowParams == null) {
            return;
        }
        hiddenForDeviceUnlock = false;
        restoreLockView();
        Log.i(TAG, "🔒 用户解锁后恢复锁机悬浮层");
    }

    private void applyImmersiveMode() {
        if (lockView == null) {
            return;
        }
        lockView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "锁机服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("专注模式锁机服务");
            channel.setShowBadge(false);
            
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        
        return builder
            .setContentTitle("专注模式运行中")
            .setContentText("锁机倒计时进行中...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build();
    }
    
    // ==================== 唤醒锁 ====================
    
    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MindFlow:LockWindowWakeLock"
            );
            wakeLock.acquire(totalDurationMs + 60000);
        }
    }
    
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }
    
    // ==================== 公开方法 ====================
    
    public long getRemainingMs() {
        return remainingMs;
    }
    
    public boolean isLockViewMinimized() {
        return windowParams != null && windowParams.width == 1;
    }
    
    // ==================== 白名单数据类 ====================
    
    public static class WhitelistApp {
        public final String packageName;
        public final String appName;
        public final Drawable icon;
        
        public WhitelistApp(String packageName, String appName, Drawable icon) {
            this.packageName = packageName;
            this.appName = appName;
            this.icon = icon;
        }
    }
    
    // ==================== 白名单适配器 ====================
    
    public static class WhitelistAdapter extends RecyclerView.Adapter<WhitelistAdapter.ViewHolder> {
        private final Context context;
        private final OnAppClickListener listener;
        private List<WhitelistApp> apps = new ArrayList<>();
        
        public interface OnAppClickListener {
            void onAppClick(String packageName);
        }
        
        public WhitelistAdapter(Context context, OnAppClickListener listener) {
            this.context = context;
            this.listener = listener;
        }
        
        public void setApps(List<WhitelistApp> apps) {
            this.apps = apps;
            notifyDataSetChanged();
        }
        
        @Override
        public ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_lock_whitelist_app, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            WhitelistApp app = apps.get(position);
            holder.icon.setImageDrawable(app.icon);
            holder.name.setText(app.appName);
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onAppClick(app.packageName);
                }
            });
        }
        
        @Override
        public int getItemCount() {
            return apps.size();
        }
        
        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView name;
            
            ViewHolder(View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.ivAppIcon);
                name = itemView.findViewById(R.id.tvAppName);
            }
        }
    }
}
