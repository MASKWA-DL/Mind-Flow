package com.example.mindflow.ui.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.mindflow.R;
import com.example.mindflow.databinding.FragmentSettingsBinding;
import com.example.mindflow.utils.FocusModePreferences;
import com.example.mindflow.utils.PermissionHelper;
import com.example.mindflow.auth.AuthManager;
import com.example.mindflow.auth.UserProfile;
import com.example.mindflow.sync.CloudSyncService;
import com.example.mindflow.ui.auth.LoginActivity;

import java.util.Set;

/**
 * 设置Fragment
 * 管理核心权限和白名单
 */
public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupUI();
        checkPermissions();
        updateUserInfo();
    }

    @Override
    public void onResume() {
        super.onResume();
        checkPermissions();
        updateUserInfo();
    }

    private void setupUI() {
        // 无障碍服务设置
        binding.settingAccessibility.setOnClickListener(v -> openAccessibilitySettings());
        binding.switchAccessibility.setOnClickListener(v -> openAccessibilitySettings());

        // 悬浮窗权限设置
        binding.settingOverlay.setOnClickListener(v -> openOverlaySettings());
        binding.switchOverlay.setOnClickListener(v -> openOverlaySettings());
        
        // 勿扰模式权限设置
        binding.settingDnd.setOnClickListener(v -> openDndSettings());
        binding.switchDnd.setOnClickListener(v -> openDndSettings());

        // 通知访问权限设置
        binding.settingNotificationAccess.setOnClickListener(v -> openNotificationAccessSettings());
        binding.switchNotificationAccess.setOnClickListener(v -> openNotificationAccessSettings());

        // 白名单管理
        binding.settingWhitelist.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), com.example.mindflow.ui.lock.WhitelistActivity.class);
            startActivity(intent);
        });

        binding.settingNotificationSources.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), NotificationSourcesActivity.class);
            startActivity(intent);
        });

        setupAiSensitivity();
        
        // 账户相关设置
        setupAccountSettings();
    }

    private void setupAiSensitivity() {
        int saved = FocusModePreferences.getAiAuditSensitivity(requireContext());
        binding.sliderAiSensitivity.setValue(saved);
        updateAiSensitivityText(saved);

        binding.sliderAiSensitivity.addOnChangeListener((slider, value, fromUser) -> {
            int rounded = Math.round(value);
            FocusModePreferences.setAiAuditSensitivity(requireContext(), rounded);
            updateAiSensitivityText(rounded);
        });

        int lockDurationSeconds = FocusModePreferences.getLockDurationSeconds(requireContext());
        binding.sliderLockDuration.setValue(lockDurationSeconds);
        updateLockDurationText(lockDurationSeconds);

        binding.sliderLockDuration.addOnChangeListener((slider, value, fromUser) -> {
            int seconds = Math.round(value);
            FocusModePreferences.setLockDurationSeconds(requireContext(), seconds);
            updateLockDurationText(seconds);
        });

        boolean debugEnabled = FocusModePreferences.isAiDebugModeEnabled(requireContext());
        binding.switchAiDebugMode.setChecked(debugEnabled);
        updateAiDebugModeText(debugEnabled);

        binding.settingAiDebugMode.setOnClickListener(v ->
            binding.switchAiDebugMode.setChecked(!binding.switchAiDebugMode.isChecked()));

        binding.switchAiDebugMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FocusModePreferences.setAiDebugModeEnabled(requireContext(), isChecked);
            updateAiDebugModeText(isChecked);
        });
    }

    private void updateAiSensitivityText(int value) {
        String level;
        if (value <= 30) {
            level = "宽松";
        } else if (value >= 70) {
            level = "严格";
        } else {
            level = "中等";
        }
        binding.tvAiSensitivityValue.setText(level + "（" + value + "）");
    }

    private void updateAiDebugModeText(boolean enabled) {
        binding.tvAiDebugStatus.setText(enabled
            ? "开启（显示判对/判错与调试入口）"
            : "关闭（默认）");
    }

    private void updateLockDurationText(int seconds) {
        int minutes = seconds / 60;
        int remainSeconds = seconds % 60;
        if (remainSeconds == 0) {
            binding.tvLockDurationValue.setText(minutes + "分钟（" + seconds + "秒）");
        } else {
            binding.tvLockDurationValue.setText(minutes + "分" + remainSeconds + "秒（" + seconds + "秒）");
        }
    }
    
    private void setupAccountSettings() {
        // 用户信息点击 - 跳转登录
        binding.settingUserInfo.setOnClickListener(v -> {
            AuthManager authManager = AuthManager.getInstance(requireContext());
            if (!authManager.isLoggedIn()) {
                Intent intent = new Intent(requireContext(), LoginActivity.class);
                intent.putExtra(LoginActivity.EXTRA_FORCE_LOGIN, true);
                startActivity(intent);
            }
        });
        
        // 同步数据
        binding.settingSyncData.setOnClickListener(v -> {
            AuthManager authManager = AuthManager.getInstance(requireContext());
            if (!authManager.isLoggedIn()) {
                Toast.makeText(requireContext(), "请先登录", Toast.LENGTH_SHORT).show();
                return;
            }
            
            Toast.makeText(requireContext(), "正在同步...", Toast.LENGTH_SHORT).show();
            CloudSyncService.getInstance(requireContext()).syncAll(new CloudSyncService.SyncCallback() {
                @Override
                public void onSuccess(String message) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> 
                            Toast.makeText(requireContext(), "同步成功", Toast.LENGTH_SHORT).show());
                    }
                }
                
                @Override
                public void onError(String error) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> 
                            Toast.makeText(requireContext(), "同步失败: " + error, Toast.LENGTH_SHORT).show());
                    }
                }
            });
        });
        
        // 退出登录
        binding.settingLogout.setOnClickListener(v -> {
            AuthManager authManager = AuthManager.getInstance(requireContext());
            if (!authManager.isLoggedIn()) {
                Toast.makeText(requireContext(), "当前未登录", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 确认退出
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("退出登录")
                .setMessage("确定要退出登录吗？本地数据将保留。")
                .setPositiveButton("退出", (dialog, which) -> {
                    authManager.logout();
                    authManager.setOfflineMode(false);
                    updateUserInfo();
                    Toast.makeText(requireContext(), "已退出登录", Toast.LENGTH_SHORT).show();

                    Intent intent = new Intent(requireContext(), LoginActivity.class);
                    intent.putExtra(LoginActivity.EXTRA_FORCE_LOGIN, true);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                })
                .setNegativeButton("取消", null)
                .show();
        });
    }
    
    private void updateUserInfo() {
        AuthManager authManager = AuthManager.getInstance(requireContext());
        
        if (authManager.isLoggedIn()) {
            UserProfile user = authManager.getCurrentUser();
            if (user != null) {
                binding.tvUserDisplayName.setText(user.username != null ? user.username : "用户");
                binding.tvUserEmail.setText(user.email != null ? user.email : "");
            }
        } else if (authManager.isOfflineMode()) {
            binding.tvUserDisplayName.setText("离线模式");
            binding.tvUserEmail.setText("点击登录以同步数据");
        } else {
            binding.tvUserDisplayName.setText("未登录");
            binding.tvUserEmail.setText("点击登录以同步数据");
        }
    }

    private void checkPermissions() {
        // 检查无障碍服务
        boolean hasAccessibility = PermissionHelper.isAccessibilityServiceEnabled(requireContext());
        binding.switchAccessibility.setChecked(hasAccessibility);
        binding.tvAccessibilityStatus.setText(hasAccessibility ? "已开启" : "未开启（用于监控前台应用）");

        // 检查悬浮窗权限
        boolean hasOverlay = PermissionHelper.hasOverlayPermission(requireContext());
        binding.switchOverlay.setChecked(hasOverlay);
        binding.tvOverlayStatus.setText(hasOverlay ? "已授权" : "未授权（用于显示锁定界面）");
        
        // 检查勿扰模式权限
        boolean hasDnd = PermissionHelper.hasDndAccessPermission(requireContext());
        binding.switchDnd.setChecked(hasDnd);
        binding.tvDndStatus.setText(hasDnd ? "已授权（当无法精确拦截通知时可作为兜底）" : "未授权（缺少通知访问权限时可作为兜底）");

        boolean hasNotificationAccess = PermissionHelper.hasNotificationListenerPermission(requireContext());
        binding.switchNotificationAccess.setChecked(hasNotificationAccess);
        binding.tvNotificationAccessStatus.setText(
            hasNotificationAccess ? "已授权（专注时可按来源屏蔽普通通知）" : "未授权（用于专注时按来源屏蔽普通通知）");

        Set<String> notificationSources = FocusModePreferences.getNotificationAllowlist(requireContext());
        binding.tvNotificationSourcesStatus.setText(
            notificationSources.isEmpty()
                ? "尚未配置允许来源，系统会只保留关键通知"
                : "已配置 " + notificationSources.size() + " 个允许通知来源");
    }

    private void openAccessibilitySettings() {
        PermissionHelper.openAccessibilityServiceDetails(requireContext());
    }

    private void openOverlaySettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + requireContext().getPackageName()));
        startActivity(intent);
    }
    
    private void openDndSettings() {
        PermissionHelper.requestDndAccessPermission(requireContext());
    }

    private void openNotificationAccessSettings() {
        PermissionHelper.openNotificationListenerSettings(requireContext());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
