package com.example.mindflow.ui.lock;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mindflow.R;
import com.example.mindflow.service.AppMonitorService;

import java.util.HashSet;
import java.util.Set;

/**
 * 锁机界面 Activity（番茄ToDo架构）
 * 
 * 核心机制：
 * 1. singleInstance 模式，独立任务栈
 * 2. 白名单应用时调用 moveTaskToBack(true) 隐藏到后台
 * 3. 看门狗通过 FullScreenIntent 拉回前台
 */
public class LockScreenActivity extends Activity {
    private static final String TAG = "LockScreenActivity";
    
    // 单例引用（供外部查询状态）
    private static LockScreenActivity instance;
    
    // UI组件
    private TextView tvTimer;
    private TextView tvReason;
    private ProgressBar progressBar;
    private LinearLayout llWhitelistApps;
    
    // 状态
    private long totalDurationMs = 60000;
    private long remainingMs = 60000;
    private CountDownTimer countDownTimer;
    private boolean isLockActive = false;
    private boolean isOpeningWhitelistApp = false;  // 标记是否正在打开白名单应用
    private Set<String> whitelist = new HashSet<>();
    private String lockReason = "专注模式已开启";
    
    // 唤醒锁
    private PowerManager.WakeLock wakeLock;
    private BroadcastReceiver screenStateReceiver;
    
    // 【关键】状态持久化（防止被系统杀死后丢失状态）
    private static final String PREFS_LOCK_STATE = "lock_screen_state";
    private static final String KEY_END_TIME = "end_time";
    private static final String KEY_TOTAL_DURATION = "total_duration";
    private static final String KEY_REASON = "reason";
    private static final String KEY_IS_ACTIVE = "is_active";
    
    public static LockScreenActivity getInstance() {
        return instance;
    }
    
    public static boolean isActive() {
        return instance != null && instance.isLockActive;
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        
        // 设置窗口标志：显示在锁屏上方、保持屏幕常亮
        setupWindowFlags();
        
        setContentView(R.layout.activity_lock_screen);
        
        // 初始化UI
        initViews();
        registerScreenStateReceiver();
        
        // 【关键】尝试从持久化状态恢复（被系统杀死后复活）
        if (!tryRestoreState()) {
            // 无持久化状态，从 Intent 解析
            parseIntent(getIntent());
        }
        
        // 加载白名单
        loadWhitelist();
        
        // 显示白名单应用图标
        displayWhitelistApps();
        
        // 启动倒计时
        startCountdown();
        
        isLockActive = true;
        
        // 保存状态
        saveState();
        
        // 获取唤醒锁
        acquireWakeLock();
        
        Log.i(TAG, "🔒 锁机界面已创建，时长: " + (totalDurationMs / 1000) + "秒");
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        
        Log.d(TAG, "🔄 onNewIntent: 锁机界面被拉回前台");
        
        // 【复活逻辑】如果锁机已经激活，只需确保显示在前台
        if (isLockActive) {
            Log.d(TAG, "✅ 锁机已激活，继续倒计时");
            return;
        }
        
        // 尝试从持久化状态恢复
        if (tryRestoreState()) {
            startCountdown();
            isLockActive = true;
            saveState();
            Log.d(TAG, "🔄 从持久化状态恢复锁机");
            return;
        }
        
        // 从 Intent 解析新的锁机参数
        if (intent.hasExtra("duration")) {
            parseIntent(intent);
            startCountdown();
            isLockActive = true;
            saveState();
        }
    }
    
    private void setupWindowFlags() {
        // 显示在锁屏上方
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }
        
        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        // 【关键】真正的全屏模式（像来电界面，禁止下拉通知栏）
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        
        // 禁止状态栏下拉（需要TYPE_SYSTEM_ERROR或更高权限，但我们用沉浸式代替）
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        
        // 【关键】刘海屏/挖孔屏适配，防止状态栏出现黑边
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 每次获得焦点时重新设置全屏，防止被系统重置
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }
    
    private void initViews() {
        tvTimer = findViewById(R.id.tvTimer);
        tvReason = findViewById(R.id.tvReason);
        progressBar = findViewById(R.id.progressBar);
        llWhitelistApps = findViewById(R.id.llWhitelistApps);
        
        if (tvReason != null) {
            tvReason.setText(lockReason);
        }
    }
    
    private void parseIntent(Intent intent) {
        if (intent == null) return;
        
        totalDurationMs = intent.getLongExtra("duration", 60000);
        remainingMs = intent.getLongExtra("remaining", totalDurationMs);
        lockReason = intent.getStringExtra("reason");
        if (lockReason == null || lockReason.isEmpty()) {
            lockReason = "专注模式已开启";
        }
        
        if (tvReason != null) {
            tvReason.setText(lockReason);
        }
    }
    
    private void loadWhitelist() {
        // 【修复】从正确的位置读取白名单（与FocusService/WhitelistActivity一致）
        SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
        whitelist = prefs.getStringSet("whitelist", new HashSet<>());
        whitelist = new HashSet<>(whitelist); // 防止返回不可变Set
        
        // 添加本应用
        whitelist.add(getPackageName());
        whitelist.add("com.example.mindflow");
        
        Log.d(TAG, "📋 加载白名单: " + whitelist.size() + " 个应用");
    }
    
    private void displayWhitelistApps() {
        if (llWhitelistApps == null) return;
        llWhitelistApps.removeAllViews();
        
        PackageManager pm = getPackageManager();
        int iconSize = (int) (48 * getResources().getDisplayMetrics().density);
        int margin = (int) (8 * getResources().getDisplayMetrics().density);
        
        for (String pkg : whitelist) {
            if (pkg.equals(getPackageName()) || pkg.equals("com.example.mindflow")) {
                continue; // 不显示本应用
            }
            
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                Drawable icon = pm.getApplicationIcon(appInfo);
                
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(icon);
                
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(iconSize, iconSize);
                params.setMargins(margin, 0, margin, 0);
                iv.setLayoutParams(params);
                iv.setContentDescription(pm.getApplicationLabel(appInfo));
                
                // 点击打开白名单应用
                final String packageName = pkg;
                iv.setOnClickListener(v -> openWhitelistApp(packageName));
                
                llWhitelistApps.addView(iv);
            } catch (PackageManager.NameNotFoundException e) {
                // 应用未安装，跳过
            }
        }
    }
    
    /**
     * 打开白名单应用 - 核心：使用 moveTaskToBack
     */
    private void openWhitelistApp(String packageName) {
        Log.d(TAG, "📱 打开白名单应用: " + packageName);
        
        // 【关键】标记正在打开白名单应用，防止 onPause 拉回
        isOpeningWhitelistApp = true;
        
        // 通知看门狗临时放行
        AppMonitorService service = AppMonitorService.getInstance();
        if (service != null) {
            service.setTemporaryAllowedApp(packageName);
        }
        
        // 启动白名单应用
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "启动应用失败: " + e.getMessage());
            Toast.makeText(this, "无法打开应用", Toast.LENGTH_SHORT).show();
            isOpeningWhitelistApp = false;  // 失败时重置标志
        }
    }
    
    private void startCountdown() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        
        if (progressBar != null) {
            progressBar.setMax((int) (totalDurationMs / 1000));
            progressBar.setProgress((int) (remainingMs / 1000));
        }
        
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
        }.start();
    }
    
    private void updateTimerDisplay() {
        if (tvTimer != null) {
            long minutes = remainingMs / 60000;
            long seconds = (remainingMs % 60000) / 1000;
            tvTimer.setText(String.format("%02d:%02d", minutes, seconds));
        }
        
        if (progressBar != null) {
            progressBar.setProgress((int) (remainingMs / 1000));
        }
    }
    
    private void endLock() {
        Log.i(TAG, "⏰ 锁机时间结束");
        isLockActive = false;
        
        // 【关键】清除持久化状态
        clearState();
        
        // 通知看门狗
        AppMonitorService service = AppMonitorService.getInstance();
        if (service != null) {
            service.deactivateLockScreen();
        }
        
        // 【关键】广播锁机结束事件，通知所有组件清零分心次数
        Intent intent = new Intent("com.example.mindflow.LOCK_ENDED");
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.i(TAG, "📢 已广播锁机结束事件");
        
        // 释放唤醒锁
        releaseWakeLock();
        
        // 结束Activity
        finish();
    }
    
    // ==================== 状态持久化（小米/华为特供） ====================
    
    /**
     * 保存锁机状态到 SharedPreferences
     * 用于被系统杀死后恢复
     */
    private void saveState() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_LOCK_STATE, MODE_PRIVATE);
            long endTime = System.currentTimeMillis() + remainingMs;
            prefs.edit()
                .putLong(KEY_END_TIME, endTime)
                .putLong(KEY_TOTAL_DURATION, totalDurationMs)
                .putString(KEY_REASON, lockReason)
                .putBoolean(KEY_IS_ACTIVE, true)
                .apply();
            Log.d(TAG, "💾 已保存锁机状态，结束时间: " + endTime);
        } catch (Exception e) {
            Log.e(TAG, "保存状态失败: " + e.getMessage());
        }
    }
    
    /**
     * 尝试从 SharedPreferences 恢复锁机状态
     * @return true 如果成功恢复，false 如果没有有效状态
     */
    private boolean tryRestoreState() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_LOCK_STATE, MODE_PRIVATE);
            boolean wasActive = prefs.getBoolean(KEY_IS_ACTIVE, false);
            if (!wasActive) {
                return false;
            }
            
            long endTime = prefs.getLong(KEY_END_TIME, 0);
            long now = System.currentTimeMillis();
            
            // 检查是否已过期
            if (endTime <= now) {
                Log.d(TAG, "⏰ 持久化状态已过期，清除");
                clearState();
                return false;
            }
            
            // 恢复状态
            remainingMs = endTime - now;
            totalDurationMs = prefs.getLong(KEY_TOTAL_DURATION, 60000);
            lockReason = prefs.getString(KEY_REASON, "专注模式已开启");
            
            Log.i(TAG, "🔄 从持久化恢复：剩余 " + (remainingMs / 1000) + " 秒");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "恢复状态失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 清除持久化状态
     */
    private void clearState() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_LOCK_STATE, MODE_PRIVATE);
            prefs.edit().clear().apply();
            Log.d(TAG, "🗑️ 已清除持久化状态");
        } catch (Exception e) {
            Log.e(TAG, "清除状态失败: " + e.getMessage());
        }
    }
    
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MindFlow:LockScreenWakeLock"
                );
                wakeLock.acquire(totalDurationMs + 60000); // 比锁机时长多1分钟
            }
        } catch (Exception e) {
            Log.e(TAG, "获取唤醒锁失败: " + e.getMessage());
        }
    }
    
    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "释放唤醒锁失败: " + e.getMessage());
        }
    }
    
    // ==================== 拦截系统按键 ====================
    
    @Override
    public void onBackPressed() {
        // 禁用返回键
        Log.d(TAG, "🚫 返回键被拦截");
    }
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 拦截Home键、最近任务键等
        if (keyCode == KeyEvent.KEYCODE_HOME ||
            keyCode == KeyEvent.KEYCODE_APP_SWITCH ||
            keyCode == KeyEvent.KEYCODE_BACK) {
            Log.d(TAG, "🚫 系统键被拦截: " + keyCode);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "⏸️ onPause - 锁机界面进入后台, isOpeningWhitelistApp=" + isOpeningWhitelistApp);
        // 不在这里自救，完全依赖 AppMonitorService 看门狗拉回
        // Android 10+ 禁止后台 Activity 启动新 Activity，自救代码会失效
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "▶️ onResume - 锁机界面回到前台");
        
        // 【关键】重置白名单应用标志（回到锁机页说明白名单应用已退出）
        isOpeningWhitelistApp = false;
        
        // 重新设置全屏（隐藏导航栏 + 状态栏 + 沉浸式）
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "💀 onDestroy");

        unregisterScreenStateReceiver();
        
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        releaseWakeLock();
        
        if (instance == this) {
            instance = null;
        }
    }
    
    // ==================== 公开方法 ====================
    
    /**
     * 获取剩余时间（毫秒）
     */
    public long getRemainingMs() {
        return remainingMs;
    }
    
    /**
     * 检查锁机是否激活
     */
    public boolean isLockActive() {
        return isLockActive;
    }
    
    /**
     * 强制结束锁机（紧急情况）
     */
    public void forceEnd() {
        Log.w(TAG, "⚠️ 强制结束锁机");
        endLock();
    }

    private void registerScreenStateReceiver() {
        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) || !isLockActive) {
                    return;
                }
                saveState();
                Log.i(TAG, "📴 息屏，暂时退出锁机 Activity 以让出系统解锁界面");
                finish();
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
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
}
