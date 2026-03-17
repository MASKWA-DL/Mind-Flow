package com.example.mindflow.ui.setup;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.mindflow.R;
import com.example.mindflow.service.AppMonitorService;
import com.example.mindflow.ui.auth.LoginActivity;

import java.util.List;

/**
 * 首次启动权限引导页面
 * 引导用户开启所有必要权限
 */
public class PermissionSetupActivity extends AppCompatActivity {

    private LinearLayout layoutAccessibility, layoutOverlay, layoutUsageStats;
    private ImageView ivAccessibility, ivOverlay, ivUsageStats;
    private TextView tvAccessibilityStatus, tvOverlayStatus, tvUsageStatsStatus;
    private Button btnAccessibility, btnOverlay, btnUsageStats, btnFinish;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 检查是否已完成设置
        if (isSetupComplete()) {
            goToMain();
            return;
        }
        
        setContentView(R.layout.activity_permission_setup);
        initViews();
        updateAllStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAllStatus();
    }

    private void initViews() {
        layoutAccessibility = findViewById(R.id.layoutAccessibility);
        layoutOverlay = findViewById(R.id.layoutOverlay);
        layoutUsageStats = findViewById(R.id.layoutUsageStats);
        
        ivAccessibility = findViewById(R.id.ivAccessibility);
        ivOverlay = findViewById(R.id.ivOverlay);
        ivUsageStats = findViewById(R.id.ivUsageStats);
        
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus);
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus);
        tvUsageStatsStatus = findViewById(R.id.tvUsageStatsStatus);
        
        btnAccessibility = findViewById(R.id.btnAccessibility);
        btnOverlay = findViewById(R.id.btnOverlay);
        btnUsageStats = findViewById(R.id.btnUsageStats);
        btnFinish = findViewById(R.id.btnFinish);
        
        btnAccessibility.setOnClickListener(v -> openAccessibilitySettings());
        btnOverlay.setOnClickListener(v -> openOverlaySettings());
        btnUsageStats.setOnClickListener(v -> openUsageStatsSettings());
        btnFinish.setOnClickListener(v -> finishSetup());
    }

    private void updateAllStatus() {
        boolean accessibilityOk = isAccessibilityEnabled();
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean usageStatsOk = isUsageStatsEnabled();
        
        // 更新无障碍服务状态
        updateItemStatus(ivAccessibility, tvAccessibilityStatus, btnAccessibility, 
            accessibilityOk, "无障碍服务", "用于监控屏幕内容");
        
        // 更新悬浮窗权限状态
        updateItemStatus(ivOverlay, tvOverlayStatus, btnOverlay, 
            overlayOk, "悬浮窗权限", "用于显示锁定界面");
        
        // 更新使用统计权限状态
        updateItemStatus(ivUsageStats, tvUsageStatsStatus, btnUsageStats, 
            usageStatsOk, "应用使用统计", "用于检测前台应用");
        
        // 检查是否全部完成
        boolean allComplete = accessibilityOk && overlayOk && usageStatsOk;
        btnFinish.setEnabled(allComplete);
        btnFinish.setText(allComplete ? "开始使用" : "请先开启所有权限");
        btnFinish.setAlpha(allComplete ? 1.0f : 0.5f);
    }

    private void updateItemStatus(ImageView iv, TextView tv, Button btn, 
                                   boolean enabled, String name, String desc) {
        if (enabled) {
            iv.setImageResource(android.R.drawable.checkbox_on_background);
            tv.setText(name + " ✓ 已开启");
            tv.setTextColor(0xFF4CAF50);
            btn.setVisibility(View.GONE);
        } else {
            iv.setImageResource(android.R.drawable.checkbox_off_background);
            tv.setText(name + " - " + desc);
            tv.setTextColor(0xFF757575);
            btn.setVisibility(View.VISIBLE);
        }
    }

    private boolean isAccessibilityEnabled() {
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

        String enabledServices = Settings.Secure.getString(
            getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        return enabledServices.contains(getPackageName()) && enabledServices.contains("AppMonitorService");
    }

    private boolean isUsageStatsEnabled() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }

    private void openOverlaySettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void openUsageStatsSettings() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        startActivity(intent);
    }

    private boolean isSetupComplete() {
        // 【修复死循环】必须同时满足：标志为true 且 所有核心权限已开启
        SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
        boolean flagComplete = prefs.getBoolean("setup_complete", false);
        if (!flagComplete) return false;
        
        // 即使标志为true，也要检查实际权限（用户可能撤回了权限）
        boolean hasOverlay = Settings.canDrawOverlays(this);
        boolean hasAccessibility = isAccessibilityEnabled();
        boolean hasUsageStats = isUsageStatsEnabled();
        
        // 如果任何权限被撤回，重置标志并返回false（让用户重新设置）
        if (!hasOverlay || !hasAccessibility || !hasUsageStats) {
            prefs.edit().putBoolean("setup_complete", false).apply();
            return false;
        }
        
        return true;
    }

    private void finishSetup() {
        // 标记设置完成
        SharedPreferences prefs = getSharedPreferences("MindFlowPrefs", MODE_PRIVATE);
        prefs.edit().putBoolean("setup_complete", true).apply();
        
        // 提示用户关闭电池优化和允许自启动
        showBatteryOptimizationHint();
    }
    
    /**
     * 提示用户关闭电池优化 - 对于锁机功能至关重要
     */
    private void showBatteryOptimizationHint() {
        // 检查是否已忽略电池优化
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                    .setTitle("重要：关闭电池优化")
                    .setMessage("为确保专注模式稳定运行，请允许MindFlow在后台运行：\n\n" +
                        "1. 点击\"去设置\"\n" +
                        "2. 选择\"允许\"或\"不优化\"\n\n" +
                        "⚠️ 否则锁机功能可能被系统杀死")
                    .setPositiveButton("去设置", (d, w) -> {
                        requestIgnoreBatteryOptimization();
                        // 延迟显示自启动提示
                        new android.os.Handler().postDelayed(this::showAutoStartHint, 500);
                    })
                    .setNegativeButton("稍后再说", (d, w) -> {
                        showAutoStartHint();
                    })
                    .setCancelable(false)
                    .show();
                return;
            }
        }
        showAutoStartHint();
    }
    
    /**
     * 请求忽略电池优化
     */
    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                // 某些设备可能不支持，打开电池设置页面
                try {
                    Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(intent);
                } catch (Exception e2) {
                    Toast.makeText(this, "请手动在设置中关闭电池优化", Toast.LENGTH_LONG).show();
                }
            }
        }
    }
    
    /**
     * 提示用户开启自启动权限（国产ROM）
     */
    private void showAutoStartHint() {
        String brand = Build.MANUFACTURER.toLowerCase();
        
        // 检测是否是国产ROM
        boolean isChinaRom = brand.contains("huawei") || brand.contains("honor") ||
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("oppo") ||
            brand.contains("vivo") || brand.contains("oneplus") || brand.contains("realme") ||
            brand.contains("meizu") || brand.contains("samsung");
        
        if (isChinaRom) {
            new AlertDialog.Builder(this)
                .setTitle("开启自启动权限")
                .setMessage("检测到您使用的是" + Build.MANUFACTURER + "手机\n\n" +
                    "为防止专注模式被系统杀死，建议开启自启动权限：\n\n" +
                    "1. 点击\"去设置\"\n" +
                    "2. 找到MindFlow并允许自启动\n\n" +
                    "💡 不同品牌设置位置可能不同")
                .setPositiveButton("去设置", (d, w) -> {
                    openAutoStartSettings();
                    goToMain();
                })
                .setNegativeButton("跳过", (d, w) -> goToMain())
                .setCancelable(false)
                .show();
        } else {
            goToMain();
        }
    }
    
    /**
     * 打开自启动设置页面（适配各品牌ROM）
     */
    private void openAutoStartSettings() {
        String brand = Build.MANUFACTURER.toLowerCase();
        Intent intent = new Intent();
        
        try {
            if (brand.contains("huawei") || brand.contains("honor")) {
                // 华为/荣耀
                intent.setComponent(new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
            } else if (brand.contains("xiaomi") || brand.contains("redmi")) {
                // 小米/红米
                intent.setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            } else if (brand.contains("oppo")) {
                // OPPO
                intent.setComponent(new ComponentName("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
            } else if (brand.contains("vivo")) {
                // vivo
                intent.setComponent(new ComponentName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
            } else if (brand.contains("oneplus")) {
                // 一加
                intent.setComponent(new ComponentName("com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"));
            } else if (brand.contains("samsung")) {
                // 三星
                intent.setComponent(new ComponentName("com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"));
            } else {
                // 通用：打开应用详情页
                intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
            }
            startActivity(intent);
        } catch (Exception e) {
            // 失败时打开应用详情页
            try {
                Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                fallback.setData(Uri.parse("package:" + getPackageName()));
                startActivity(fallback);
            } catch (Exception e2) {
                Toast.makeText(this, "请手动在设置中开启自启动权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void goToMain() {
        Intent intent = new Intent(this, LoginActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        // 阻止返回，必须完成权限设置
        if (!isSetupComplete()) {
            return;
        }
        super.onBackPressed();
    }
}
