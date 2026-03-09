package com.example.mindflow.ui.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.mindflow.databinding.FragmentSettingsBinding;
import com.example.mindflow.utils.FocusModePreferences;
import com.example.mindflow.utils.PermissionHelper;

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
    }

    @Override
    public void onResume() {
        super.onResume();
        checkPermissions();
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
