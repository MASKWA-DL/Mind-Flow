package com.example.mindflow.ui.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.mindflow.R;
import com.example.mindflow.ui.lock.AppAdapter;
import com.example.mindflow.ui.lock.AppInfo;
import com.example.mindflow.utils.FocusModePreferences;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NotificationSourcesActivity extends AppCompatActivity {
    private RecyclerView rvApps;
    private AppAdapter adapter;
    private final List<AppInfo> installedApps = new ArrayList<>();
    private final List<AppInfo> filteredApps = new ArrayList<>();
    private Set<String> selectedApps = new HashSet<>();
    private PackageManager packageManager;
    private TextInputEditText etSearch;
    private Button btnSearch;
    private TextView tvLoading;

    private static final String PREFS_NAME = "MindFlowPrefs";
    private static final String KEY_DEFAULT_NOTIFICATION_ALLOWLIST_ADDED = "default_notification_allowlist_added";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_whitelist);

        packageManager = getPackageManager();
        bindViews();
        setupCopy();
        setupSavedSelection();
        setupSearch();
        setupSave();
        loadAppsAsync();
    }

    private void bindViews() {
        rvApps = findViewById(R.id.rvApps);
        rvApps.setLayoutManager(new LinearLayoutManager(this));

        tvLoading = findViewById(R.id.tvLoading);
        if (tvLoading != null) {
            tvLoading.setVisibility(View.VISIBLE);
        }
        if (rvApps != null) {
            rvApps.setVisibility(View.INVISIBLE);
        }

        etSearch = findViewById(R.id.etSearch);
        btnSearch = findViewById(R.id.btnSearch);
    }

    private void setupCopy() {
        TextView tvTitle = findViewById(R.id.tvTitle);
        TextView tvSubtitle = findViewById(R.id.tvSubtitle);
        Button btnSave = findViewById(R.id.btnSave);

        if (tvTitle != null) {
            tvTitle.setText("通知来源管理");
        }
        if (tvSubtitle != null) {
            tvSubtitle.setText("勾选的应用在专注期间仍允许发通知，其余普通通知会被自动清除");
        }
        if (btnSave != null) {
            btnSave.setText("保存通知配置");
        }
    }

    private void setupSavedSelection() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        selectedApps = sanitizePackages(FocusModePreferences.getNotificationAllowlist(this));

        boolean defaultAdded = prefs.getBoolean(KEY_DEFAULT_NOTIFICATION_ALLOWLIST_ADDED, false);
        if (!defaultAdded) {
            HashSet<String> merged = new HashSet<>(selectedApps);
            Collections.addAll(
                    merged,
                    "com.android.dialer",
                    "com.google.android.dialer",
                    "com.samsung.android.dialer",
                    "com.miui.dialer",
                    "com.android.incallui",
                    "com.android.mms",
                    "com.android.messaging",
                    "com.google.android.apps.messaging",
                    "com.samsung.android.messaging",
                    "com.huawei.message",
                    "com.android.deskclock",
                    "com.google.android.deskclock");
            selectedApps = sanitizePackages(merged);
            FocusModePreferences.setNotificationAllowlist(this, selectedApps);
            prefs.edit().putBoolean(KEY_DEFAULT_NOTIFICATION_ALLOWLIST_ADDED, true).apply();
        }
    }

    private void setupSearch() {
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> {
                String query = etSearch != null && etSearch.getText() != null
                        ? etSearch.getText().toString()
                        : "";
                filterApps(query);
            });
        }

        if (etSearch != null) {
            etSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterApps(s.toString());
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                }
            });
        }
    }

    private void setupSave() {
        findViewById(R.id.btnSave).setOnClickListener(v -> {
            Set<String> saveSet = sanitizePackages(selectedApps);
            FocusModePreferences.setNotificationAllowlist(this, saveSet);
            Toast.makeText(this, "允许通知来源已更新 (" + saveSet.size() + " 个应用)", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private Set<String> sanitizePackages(Set<String> input) {
        Set<String> out = new HashSet<>();
        if (input == null) {
            return out;
        }

        for (String pkg : input) {
            if (pkg == null) {
                continue;
            }
            String trimmed = pkg.trim();
            if (trimmed.isEmpty() || trimmed.equals(getPackageName()) || trimmed.contains(":")) {
                continue;
            }
            try {
                Intent launchIntent = packageManager.getLaunchIntentForPackage(trimmed);
                if (launchIntent != null) {
                    out.add(trimmed);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private void loadAppsAsync() {
        new Thread(() -> {
            List<AppInfo> appList = new ArrayList<>();
            Set<String> addedPackages = new HashSet<>();

            try {
                List<ApplicationInfo> allApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
                for (ApplicationInfo appInfo : allApps) {
                    String packageName = appInfo.packageName;
                    if (packageName.equals(getPackageName()) || packageName.equals("android")
                            || packageName.startsWith("com.android.internal")) {
                        continue;
                    }
                    Intent launchIntent = packageManager.getLaunchIntentForPackage(packageName);
                    if (launchIntent != null) {
                        addedPackages.add(packageName);
                        try {
                            String label = appInfo.loadLabel(packageManager).toString();
                            appList.add(new AppInfo(label, packageName, appInfo.loadIcon(packageManager)));
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> launcherApps;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    launcherApps = packageManager.queryIntentActivities(
                            mainIntent,
                            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL));
                } else {
                    launcherApps = packageManager.queryIntentActivities(mainIntent, 0);
                }

                for (ResolveInfo ri : launcherApps) {
                    String packageName = ri.activityInfo.packageName;
                    if (!addedPackages.contains(packageName) && !packageName.equals(getPackageName())) {
                        addedPackages.add(packageName);
                        try {
                            appList.add(new AppInfo(
                                    ri.loadLabel(packageManager).toString(),
                                    packageName,
                                    ri.loadIcon(packageManager)));
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            appList.sort((a, b) -> a.label.compareToIgnoreCase(b.label));

            runOnUiThread(() -> {
                installedApps.clear();
                installedApps.addAll(appList);
                filteredApps.clear();
                filteredApps.addAll(appList);

                adapter = new AppAdapter(filteredApps, selectedApps);
                rvApps.setAdapter(adapter);

                if (tvLoading != null) {
                    tvLoading.setVisibility(View.GONE);
                }
                if (rvApps != null) {
                    rvApps.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    private void filterApps(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        filteredApps.clear();
        if (normalized.isEmpty()) {
            filteredApps.addAll(installedApps);
        } else {
            for (AppInfo app : installedApps) {
                if (app.label.toLowerCase().contains(normalized)
                        || app.packageName.toLowerCase().contains(normalized)) {
                    filteredApps.add(app);
                }
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }
}
