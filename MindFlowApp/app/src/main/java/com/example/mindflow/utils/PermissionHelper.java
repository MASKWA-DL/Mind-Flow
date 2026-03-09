package com.example.mindflow.utils;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import androidx.core.app.NotificationManagerCompat;

import com.example.mindflow.service.AppMonitorService;

import java.util.List;

/**
 * 权限检查和引导工具类
 */
public class PermissionHelper {

    private static final String EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME =
        "android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME";
    private static final String EXTRA_ACCESSIBILITY_SERVICE_ENABLED =
        "android.provider.extra.ACCESSIBILITY_SERVICE_ENABLED";
    
    /**
     * 检查悬浮窗权限
     */
    public static boolean hasOverlayPermission(Context context) {
        return Settings.canDrawOverlays(context);
    }
    
    /**
     * 打开悬浮窗权限设置
     */
    public static void requestOverlayPermission(Context context) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    
    /**
     * 检查使用情况访问权限
     */
    public static boolean hasUsageStatsPermission(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }
    
    /**
     * 打开使用情况访问权限设置
     */
    public static void requestUsageStatsPermission(Context context) {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    
    /**
     * 检查无障碍服务是否启用
     */
    public static boolean isAccessibilityServiceEnabled(Context context) {
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        
        String packageName = context.getPackageName();
        for (AccessibilityServiceInfo info : enabledServices) {
            String id = info.getId();
            // 使用宽松匹配：包含包名和服务名即可
            if (id != null && id.contains(packageName) && id.contains("AppMonitorService")) {
                return true;
            }
        }
        
        // 备用检查方式
        return AppMonitorService.isRunning();
    }
    
    /**
     * 打开无障碍服务设置
     */
    public static void requestAccessibilityPermission(Context context) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * 尝试直接跳转到本应用无障碍服务的详情页（部分机型可直接看到开关）。
     * 注意：Android 不允许应用“自动开启”无障碍，只能引导用户在系统界面手动开启。
     */
    public static void openAccessibilityServiceDetails(Context context) {
        String componentName = context.getPackageName() + "/" + AppMonitorService.class.getName();
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME, componentName);
            intent.putExtra(EXTRA_ACCESSIBILITY_SERVICE_ENABLED, true);
            context.startActivity(intent);
        } catch (Exception e) {
            requestAccessibilityPermission(context);
        }
    }
    
    /**
     * 检查通知权限 (Android 13+)
     */
    public static boolean hasNotificationPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
    
    /**
     * 检查勿扰模式访问权限（用于专注时自动开启DND减少干扰）
     */
    public static boolean hasDndAccessPermission(Context context) {
        android.app.NotificationManager nm = (android.app.NotificationManager) 
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }
    
    /**
     * 打开勿扰模式权限设置
     */
    public static void requestDndAccessPermission(Context context) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * 检查通知访问权限（NotificationListenerService）
     */
    public static boolean hasNotificationListenerPermission(Context context) {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.getPackageName());
    }

    /**
     * 打开通知访问权限设置
     */
    public static void openNotificationListenerSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    
    /**
     * 开启勿扰模式（需要先获得权限）
     */
    public static boolean enableDndMode(Context context) {
        if (!hasDndAccessPermission(context)) return false;
        
        android.app.NotificationManager nm = (android.app.NotificationManager) 
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            // 设置为仅允许闹钟的勿扰模式
            nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS);
            return true;
        }
        return false;
    }
    
    /**
     * 关闭勿扰模式
     */
    public static boolean disableDndMode(Context context) {
        if (!hasDndAccessPermission(context)) return false;
        
        android.app.NotificationManager nm = (android.app.NotificationManager) 
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL);
            return true;
        }
        return false;
    }
    
    /**
     * 检查所有必要权限是否已授予
     */
    public static boolean hasAllRequiredPermissions(Context context) {
        return hasOverlayPermission(context) && hasUsageStatsPermission(context);
    }
    
    /**
     * 获取缺失权限的描述
     */
    public static String getMissingPermissionsDescription(Context context) {
        StringBuilder sb = new StringBuilder();
        
        if (!hasOverlayPermission(context)) {
            sb.append("• 悬浮窗权限（用于显示锁定界面）\n");
        }
        if (!hasUsageStatsPermission(context)) {
            sb.append("• 使用情况访问权限（用于监控前台应用）\n");
        }
        if (!isAccessibilityServiceEnabled(context)) {
            sb.append("• 无障碍服务（用于实时监控应用切换）\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 检查是否已忽略电池优化（后台运行必需）
     */
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        android.os.PowerManager pm = (android.os.PowerManager) 
            context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }
    
    /**
     * 请求忽略电池优化
     */
    @android.annotation.SuppressLint("BatteryLife")
    public static void requestIgnoreBatteryOptimizations(Context context) {
        if (!isIgnoringBatteryOptimizations(context)) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }
    
    /**
     * 打开应用电池优化设置页面
     */
    public static void openBatteryOptimizationSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            // 部分设备不支持，打开应用详情页
            openAppDetailSettings(context);
        }
    }
    
    /**
     * 打开应用详情设置页面
     */
    public static void openAppDetailSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    
    /**
     * 尝试打开厂商自启动设置（华为、小米、OPPO、VIVO、鸿蒙等）
     */
    public static void openAutoStartSettings(Context context) {
        String[] intents = {
            // 华为/鸿蒙
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
            // 小米
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            // OPPO
            "com.coloros.safecenter/.startupapp.StartupAppListActivity",
            "com.oppo.safe/.permission.startup.StartupAppListActivity",
            // VIVO
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager",
            // 三星
            "com.samsung.android.lool/.activity.MainActivity",
            // 一加
            "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity"
        };
        
        for (String intentStr : intents) {
            try {
                String[] parts = intentStr.split("/");
                Intent intent = new Intent();
                intent.setComponent(new android.content.ComponentName(parts[0], parts[1]));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            } catch (Exception ignored) {}
        }
        
        // 都不支持，打开应用详情
        openAppDetailSettings(context);
    }
    
    /**
     * 检测是否为鸿蒙系统
     */
    public static boolean isHarmonyOS() {
        try {
            Class<?> buildExClass = Class.forName("com.huawei.system.BuildEx");
            Object osBrand = buildExClass.getMethod("getOsBrand").invoke(null);
            return "Harmony".equalsIgnoreCase(String.valueOf(osBrand));
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 检测厂商
     */
    public static String getDeviceBrand() {
        return Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
    }
}
