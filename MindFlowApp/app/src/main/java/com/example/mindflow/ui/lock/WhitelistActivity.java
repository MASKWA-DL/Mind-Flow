package com.example.mindflow.ui.lock;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
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
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WhitelistActivity extends AppCompatActivity {

    private RecyclerView rvApps;
    private AppAdapter adapter;
    private List<AppInfo> installedApps = new ArrayList<>();
    private List<AppInfo> filteredApps = new ArrayList<>();  // 搜索过滤后的列表
    private Set<String> selectedApps = new HashSet<>();
    private PackageManager packageManager;
    private TextInputEditText etSearch;
    private Button btnSearch;
    private TextView tvLoading;

    private static final String PREFS_NAME = "MindFlowPrefs";
    private static final String KEY_WHITELIST = "whitelist";
    private static final String KEY_DEFAULT_COMM_WHITELIST_ADDED = "default_comm_whitelist_added";

    private Set<String> sanitizeWhitelist(Set<String> input) {
        Set<String> out = new HashSet<>();
        if (input == null) return out;

        for (String pkg : input) {
            if (pkg == null) continue;
            String p = pkg.trim();
            if (p.isEmpty()) continue;
            if (p.equals(getPackageName())) continue;
            // 过滤进程名形式：com.xxx:process
            if (p.contains(":")) continue;

            // 只允许可启动的“应用”（有 launcher intent）
            try {
                Intent launchIntent = packageManager.getLaunchIntentForPackage(p);
                if (launchIntent != null) {
                    out.add(p);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_whitelist);

        packageManager = getPackageManager();
        rvApps = findViewById(R.id.rvApps);
        rvApps.setLayoutManager(new LinearLayoutManager(this));

        tvLoading = findViewById(R.id.tvLoading);
        if (tvLoading != null) {
            tvLoading.setVisibility(View.VISIBLE);
        }
        if (rvApps != null) {
            rvApps.setVisibility(View.INVISIBLE);
        }

        // 1. 加载本地已保存的允许使用应用
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Set<String> savedWhitelist = prefs.getStringSet(KEY_WHITELIST, new HashSet<>());
        Set<String> sanitizedSaved = sanitizeWhitelist(savedWhitelist);

        // 首次进入：默认加入电话/短信相关应用到允许使用列表（默认打勾）
        boolean defaultAdded = prefs.getBoolean(KEY_DEFAULT_COMM_WHITELIST_ADDED, false);
        if (!defaultAdded) {
            HashSet<String> merged = new HashSet<>(sanitizedSaved);
            merged.add("com.android.dialer");
            merged.add("com.google.android.dialer");
            merged.add("com.samsung.android.dialer");
            merged.add("com.miui.dialer");
            merged.add("com.android.incallui");

            merged.add("com.android.mms");
            merged.add("com.android.messaging");
            merged.add("com.google.android.apps.messaging");
            merged.add("com.samsung.android.messaging");
            merged.add("com.huawei.message");

            // 只保留可启动应用包名
            Set<String> cleaned = sanitizeWhitelist(merged);

            prefs.edit()
                .putStringSet(KEY_WHITELIST, new HashSet<>(cleaned))
                .putBoolean(KEY_DEFAULT_COMM_WHITELIST_ADDED, true)
                .apply();
            selectedApps = cleaned;
        } else {
            selectedApps = sanitizedSaved;
        }

        // 2. 设置搜索框和搜索按钮
        etSearch = findViewById(R.id.etSearch);
        btnSearch = findViewById(R.id.btnSearch);

        // 搜索按钮点击
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> {
                String query = etSearch != null && etSearch.getText() != null ? 
                    etSearch.getText().toString() : "";
                filterApps(query);
            });
        }
        
        // 实时搜索（输入时过滤）
        if (etSearch != null) {
            etSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterApps(s.toString());
                }
                
                @Override
                public void afterTextChanged(android.text.Editable s) {}
            });
        }

        // 3. 异步扫描已安装应用，防止主线程卡顿
        loadAppsAsync();

        // 4. 保存按钮逻辑
        findViewById(R.id.btnSave).setOnClickListener(v -> {
            // 重要：必须创建新的HashSet，避免SharedPreferences的可变引用坑
            Set<String> saveSet = sanitizeWhitelist(selectedApps);
            prefs.edit().putStringSet(KEY_WHITELIST, new HashSet<>(saveSet)).apply();
            android.util.Log.d("WhitelistActivity", "允许使用应用已保存: " + saveSet.size() + " 个应用, 内容: " + saveSet);
            Toast.makeText(this, "允许使用应用已更新 (" + saveSet.size() + " 个应用)", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void loadAppsAsync() {
        new Thread(() -> {
            List<AppInfo> appList = new ArrayList<>();
            Set<String> addedPackages = new HashSet<>();

            try {
                // 方法1: 使用 getInstalledApplications 获取所有已安装应用
                // 使用 GET_META_DATA 标志以获取更完整的信息
                List<ApplicationInfo> allApps = packageManager.getInstalledApplications(
                    PackageManager.GET_META_DATA);
                
                for (ApplicationInfo appInfo : allApps) {
                    String packageName = appInfo.packageName;
                    
                    // 跳过自己和系统核心应用
                    if (packageName.equals(getPackageName()) || 
                        packageName.equals("android") ||
                        packageName.startsWith("com.android.internal")) {
                        continue;
                    }
                    
                    // 检查是否有启动器Activity（用户可以打开的应用）
                    Intent launchIntent = packageManager.getLaunchIntentForPackage(packageName);
                    if (launchIntent != null) {
                        addedPackages.add(packageName);
                        try {
                            String label = appInfo.loadLabel(packageManager).toString();
                            appList.add(new AppInfo(
                                    label,
                                    packageName,
                                    appInfo.loadIcon(packageManager)
                            ));
                        } catch (Exception e) {
                            // 加载图标失败，跳过
                        }
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("WhitelistActivity", "方法1失败: " + e.getMessage());
            }
            
            try {
                // 方法2: 使用 queryIntentActivities 获取所有带LAUNCHER的应用
                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                
                // Android 13+ 需要使用新的 API
                List<ResolveInfo> launcherApps;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    launcherApps = packageManager.queryIntentActivities(mainIntent, 
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
                                    ri.loadIcon(packageManager)
                            ));
                        } catch (Exception e) {
                            // 加载失败，跳过
                        }
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("WhitelistActivity", "方法2失败: " + e.getMessage());
            }
            
            try {
                // 方法3: 尝试获取所有包信息（某些设备上更全）
                List<android.content.pm.PackageInfo> packages = packageManager.getInstalledPackages(
                    PackageManager.GET_ACTIVITIES);
                
                for (android.content.pm.PackageInfo pkg : packages) {
                    String packageName = pkg.packageName;
                    if (!addedPackages.contains(packageName) && !packageName.equals(getPackageName())) {
                        // 检查是否可启动
                        Intent launchIntent = packageManager.getLaunchIntentForPackage(packageName);
                        if (launchIntent != null && pkg.applicationInfo != null) {
                            addedPackages.add(packageName);
                            try {
                                appList.add(new AppInfo(
                                        pkg.applicationInfo.loadLabel(packageManager).toString(),
                                        packageName,
                                        pkg.applicationInfo.loadIcon(packageManager)
                                ));
                            } catch (Exception e) {
                                // 加载失败，跳过
                            }
                        }
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("WhitelistActivity", "方法3失败: " + e.getMessage());
            }
            
            try {
                // 方法4: 使用 UsageStatsManager 获取最近使用的应用（适配国产ROM）
                UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
                if (usageStatsManager != null) {
                    long endTime = System.currentTimeMillis();
                    long startTime = endTime - 30L * 24 * 60 * 60 * 1000; // 最近30天
                    List<UsageStats> usageStatsList = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_MONTHLY, startTime, endTime);
                    
                    if (usageStatsList != null) {
                        for (UsageStats usageStats : usageStatsList) {
                            String packageName = usageStats.getPackageName();
                            if (!addedPackages.contains(packageName) && 
                                !packageName.equals(getPackageName()) &&
                                usageStats.getTotalTimeInForeground() > 0) {
                                try {
                                    ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                                    Intent launchIntent = packageManager.getLaunchIntentForPackage(packageName);
                                    if (launchIntent != null) {
                                        addedPackages.add(packageName);
                                        appList.add(new AppInfo(
                                            appInfo.loadLabel(packageManager).toString(),
                                            packageName,
                                            appInfo.loadIcon(packageManager)
                                        ));
                                    }
                                } catch (PackageManager.NameNotFoundException e) {
                                    // 应用已卸载，跳过
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("WhitelistActivity", "方法4(UsageStats)失败: " + e.getMessage());
            }
            
            // 方法5: 直接尝试加载常见应用（兜底方案）
            String[] commonApps = {
                "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim",
                "com.sina.weibo", "com.ss.android.ugc.aweme", "com.ss.android.article.news",
                "com.kuaishou.nebula", "com.smile.gifmaker", "tv.danmaku.bili",
                "com.netease.cloudmusic", "com.kugou.android", "com.tencent.qqmusic",
                "com.taobao.taobao", "com.jingdong.app.mall", "com.xingin.xhs",
                "com.zhihu.android", "com.douban.frodo", "com.alibaba.android.rimet",
                "com.tencent.wework", "com.tencent.tmgp.sgame", "com.tencent.ig",
                "com.miHoYo.Yuanshen", "com.youku.phone", "com.qiyi.video",
                "com.tencent.qqlive", "cn.wps.moffice_eng", "com.baidu.netdisk",
                "com.android.chrome", "com.UCMobile", "com.autonavi.minimap",
                "com.baidu.BaiduMap", "com.eg.android.AlipayGphone",
                "com.sankuai.meituan", "me.ele", "us.zoom.videomeetings",
                "com.tencent.meeting", "com.microsoft.teams"
            };

            for (String pkgName : commonApps) {
                if (!addedPackages.contains(pkgName)) {
                    try {
                        ApplicationInfo appInfo = packageManager.getApplicationInfo(pkgName, 0);
                        Intent launchIntent = packageManager.getLaunchIntentForPackage(pkgName);
                        if (launchIntent == null) {
                            continue;
                        }

                        addedPackages.add(pkgName);
                        appList.add(new AppInfo(
                            appInfo.loadLabel(packageManager).toString(),
                            pkgName,
                            appInfo.loadIcon(packageManager)
                        ));
                    } catch (PackageManager.NameNotFoundException e) {
                        // 应用未安装，跳过
                    } catch (Exception e) {
                        // 其他错误，跳过
                    }
                }
            }

            // 按名称排序（中文拼音排序）
            Collections.sort(appList, (a, b) -> {
                java.text.Collator collator = java.text.Collator.getInstance(java.util.Locale.CHINA);
                return collator.compare(a.label, b.label);
            });

            final int totalCount = appList.size();
            
            // 回到主线程更新 UI
            runOnUiThread(() -> {
                installedApps.clear();
                installedApps.addAll(appList);
                adapter = new AppAdapter(installedApps, selectedApps);
                rvApps.setAdapter(adapter);

                if (tvLoading != null) {
                    if (totalCount <= 0) {
                        tvLoading.setText("未找到应用");
                        tvLoading.setVisibility(View.VISIBLE);
                    } else {
                        tvLoading.setVisibility(View.GONE);
                    }
                }
                if (rvApps != null) {
                    rvApps.setVisibility(totalCount > 0 ? View.VISIBLE : View.INVISIBLE);
                }
                
                Toast.makeText(WhitelistActivity.this, 
                    "已加载 " + totalCount + " 个应用", 
                    Toast.LENGTH_SHORT).show();
            });
        }).start();
    }
    
    /**
     * 根据搜索关键词过滤应用列表
     */
    private void filterApps(String query) {
        if (adapter == null) return;
        
        filteredApps.clear();
        
        if (query == null || query.trim().isEmpty()) {
            // 没有搜索词，显示全部
            filteredApps.addAll(installedApps);
        } else {
            String lowerQuery = query.toLowerCase();
            for (AppInfo app : installedApps) {
                // 匹配应用名称或包名
                if (app.label.toLowerCase().contains(lowerQuery) ||
                    app.packageName.toLowerCase().contains(lowerQuery)) {
                    filteredApps.add(app);
                }
            }
        }
        
        adapter = new AppAdapter(filteredApps, selectedApps);
        rvApps.setAdapter(adapter);

        if (tvLoading != null) {
            tvLoading.setVisibility(View.GONE);
        }
        if (rvApps != null) {
            rvApps.setVisibility(View.VISIBLE);
        }
    }
}
