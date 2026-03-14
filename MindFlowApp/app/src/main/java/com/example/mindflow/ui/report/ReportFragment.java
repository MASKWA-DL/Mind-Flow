package com.example.mindflow.ui.report;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.mindflow.R;
import com.example.mindflow.databinding.FragmentReportBinding;
import com.example.mindflow.model.FocusSession;
import com.example.mindflow.network.GlmApiService;
import com.example.mindflow.service.DistractionManager;

import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.PercentFormatter;

// 引入折线图需要的包
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ReportFragment extends Fragment {

    private FragmentReportBinding binding;
    private ReportViewModel viewModel;
    private DistractionManager distractionManager;
    private SessionAdapter sessionAdapter;

    private String currentPeriodLabel = "今日";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable aiSummaryTimeoutRunnable;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentReportBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this).get(ReportViewModel.class);
        distractionManager = new DistractionManager(requireContext());

        setupUI();
        setupPieChartBasic(); // 名字没改，但里面配置了饼图和折线图
        observeData();
        loadAiLog();


    }

    private void setupUI() {
        binding.chipToday.setOnClickListener(v -> {
            currentPeriodLabel = "今日";
            viewModel.loadData(ReportViewModel.Period.TODAY);
        });
        binding.chipWeek.setOnClickListener(v -> {
            currentPeriodLabel = "本周";
            viewModel.loadData(ReportViewModel.Period.WEEK);
        });
        binding.chipMonth.setOnClickListener(v -> {
            currentPeriodLabel = "本月";
            viewModel.loadData(ReportViewModel.Period.MONTH);
        });
        binding.chipYear.setOnClickListener(v -> {
            currentPeriodLabel = "本年";
            viewModel.loadData(ReportViewModel.Period.YEAR);
        });

        sessionAdapter = new SessionAdapter();
        binding.sessionRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.sessionRecyclerView.setAdapter(sessionAdapter);

        binding.btnGenerateSummary.setOnClickListener(v -> generateAiSummary());

        binding.btnClearAiLog.setOnClickListener(v -> {
            distractionManager.clearAiRecognitionLog();
            loadAiLog();
            android.widget.Toast.makeText(requireContext(), "日志已清空", android.widget.Toast.LENGTH_SHORT).show();
        });

        binding.scrollAiLog.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
    }

    // 🚨 修复一：替换图表初始化（加入折线图基础配置）
    private void setupPieChartBasic() {
        // 1. 设置饼图
        binding.pieChart.setUsePercentValues(true);
        binding.pieChart.getDescription().setEnabled(false);
        binding.pieChart.setExtraOffsets(5, 10, 5, 5);
        binding.pieChart.setDragDecelerationFrictionCoef(0.95f);
        binding.pieChart.setDrawHoleEnabled(true);
        binding.pieChart.setHoleColor(Color.WHITE);
        binding.pieChart.setTransparentCircleColor(Color.WHITE);
        binding.pieChart.setTransparentCircleAlpha(110);
        binding.pieChart.setHoleRadius(50f);
        binding.pieChart.setTransparentCircleRadius(55f);
        binding.pieChart.setDrawCenterText(true);
        binding.pieChart.setCenterText("时效\n占比");
        binding.pieChart.setCenterTextSize(16f);
        binding.pieChart.setRotationAngle(0);
        binding.pieChart.setRotationEnabled(true);
        binding.pieChart.setHighlightPerTapEnabled(true);
        binding.pieChart.getLegend().setEnabled(true);

        // 2. 完美替换：折线图初始配置
        binding.lineChart.getDescription().setEnabled(false);
        binding.lineChart.setDrawGridBackground(false);
        binding.lineChart.getAxisRight().setEnabled(false); // 隐藏右侧 Y 轴

        XAxis xAxis = binding.lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setGranularity(1f); // 保证缩放时 X 轴标签不重复
    }

    private void loadAiLog() {
        if (binding == null) return;
        String logContent = distractionManager.getAiRecognitionLog();
        int logCount = distractionManager.getAiRecognitionLogCount();
        binding.tvAiLogCount.setText(logCount + "条");
        binding.tvAiLog.setText(logCount > 0 ? logContent : "暂无AI识别记录");
    }

    // 🚨 修复二：观察数据变化时，切换成 lineChart
    private void observeData() {
        viewModel.getTotalInterventions().observe(getViewLifecycleOwner(), count -> {
            binding.tvTotalInterventions.setText(String.valueOf(count != null ? count : 0));
        });

        viewModel.getChartData().observe(getViewLifecycleOwner(), chartPoints -> {
            if ("今日".equals(currentPeriodLabel)) {
                binding.pieChart.setVisibility(View.VISIBLE);
                binding.lineChart.setVisibility(View.GONE);
                binding.tvChartTitle.setText("今日时间分配占比");
            } else {
                binding.pieChart.setVisibility(View.GONE);
                binding.lineChart.setVisibility(View.VISIBLE);
                binding.tvChartTitle.setText(currentPeriodLabel + "专注时长趋势");
                renderLineChart(chartPoints); // 调用画折线图的方法
            }
        });

        viewModel.getSessionList().observe(getViewLifecycleOwner(), sessions -> {
            if (sessions == null || sessions.isEmpty()) {
                binding.tvSessionListTitle.setVisibility(View.GONE);
                binding.sessionRecyclerView.setVisibility(View.GONE);
                binding.pieChart.clear();

                binding.tvTotalMinutes.setText("0");
                binding.tvPositiveFeedback.setText("--");
            } else {
                binding.tvSessionListTitle.setVisibility(View.VISIBLE);
                binding.sessionRecyclerView.setVisibility(View.VISIBLE);
                sessionAdapter.setSessions(sessions);

                long totalFocusSec = 0;
                long totalDistSec = 0;
                for (FocusSession s : sessions) {
                    totalFocusSec += s.focusTimeSec;
                    totalDistSec += s.distractionTimeSec;
                }

                int pureFocusMinutes = (int) (totalFocusSec / 60);
                binding.tvTotalMinutes.setText(String.valueOf(pureFocusMinutes));

                if (totalFocusSec + totalDistSec == 0) {
                    binding.tvPositiveFeedback.setText("--");
                } else {
                    float rate = (float) totalFocusSec / (totalFocusSec + totalDistSec) * 100;
                    binding.tvPositiveFeedback.setText(String.format("%.0f%%", rate));
                }

                if ("今日".equals(currentPeriodLabel)) {
                    updatePieChart(sessions);
                }
            }
        });
    }

    private void updatePieChart(List<FocusSession> sessions) {
        long totalFocusSec = 0;
        long totalDistSec = 0;

        for (FocusSession s : sessions) {
            totalFocusSec += s.focusTimeSec;
            totalDistSec += s.distractionTimeSec;
        }

        if (totalFocusSec == 0 && totalDistSec == 0) {
            binding.pieChart.clear();
            return;
        }

        List<PieEntry> entries = new ArrayList<>();

        if (totalFocusSec > 0) {
            entries.add(new PieEntry(totalFocusSec, "专注区"));
        }
        if (totalDistSec > 0) {
            entries.add(new PieEntry(totalDistSec, "游离区"));
        }

        PieDataSet dataSet = new PieDataSet(entries, "");
        dataSet.setSliceSpace(3f);
        dataSet.setSelectionShift(5f);

        ArrayList<Integer> colors = new ArrayList<>();
        colors.add(getResources().getColor(R.color.primary, null));
        colors.add(getResources().getColor(R.color.warning, null));
        dataSet.setColors(colors);

        PieData data = new PieData(dataSet);
        data.setValueFormatter(new PercentFormatter(binding.pieChart));
        data.setValueTextSize(14f);
        data.setValueTextColor(Color.WHITE);

        binding.pieChart.setData(data);
        binding.pieChart.highlightValues(null);
        binding.pieChart.animateY(1400);
        binding.pieChart.invalidate();
    }

    private void updateFocusRate() {
        Integer minutes = viewModel.getTotalMinutes().getValue();
        Integer interventions = viewModel.getTotalInterventions().getValue();
        if (minutes != null && minutes > 0) {
            int distractions = interventions != null ? interventions : 0;
            float rate = Math.max(0, 100 - distractions * 5);
            binding.tvPositiveFeedback.setText(String.format("%.0f%%", rate));
        } else {
            binding.tvPositiveFeedback.setText("--");
        }
    }

    // 🚨 修复三：删掉 renderBarChart，替换为超帅的平滑曲线图渲染逻辑
    private void renderLineChart(List<ReportViewModel.ChartPoint> chartPoints) {
        if (chartPoints == null || chartPoints.isEmpty()) {
            binding.lineChart.clear();
            return;
        }

        List<Entry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        float maxValue = 0f;

        // 遍历收集数据，找最大值
        for (int i = 0; i < chartPoints.size(); i++) {
            float val = chartPoints.get(i).value;
            entries.add(new Entry(i, val));
            labels.add(chartPoints.get(i).label);
            if (val > maxValue) {
                maxValue = val;
            }
        }

        LineDataSet dataSet = new LineDataSet(entries, "净专注(分钟)");
        int primaryColor = getResources().getColor(R.color.primary, null);

        dataSet.setColor(primaryColor);
        dataSet.setCircleColor(primaryColor);
        dataSet.setLineWidth(2.5f);
        dataSet.setCircleRadius(4f);
        dataSet.setDrawValues(false);      // 隐藏节点数字，以免拥挤
        dataSet.setMode(LineDataSet.Mode.HORIZONTAL_BEZIER); // 开启水平贝塞尔曲线（平滑且绝对精准，绝不超过真实最高点）

        // 底部渐变填充
        dataSet.setDrawFilled(true);
        dataSet.setFillColor(primaryColor);
        dataSet.setFillAlpha(40);

        LineData lineData = new LineData(dataSet);
        binding.lineChart.setData(lineData);

        XAxis xAxis = binding.lineChart.getXAxis();
        xAxis.setValueFormatter(new IndexAxisValueFormatter(labels));
        if (labels.size() > 12) {
            xAxis.setLabelCount(6, false);
        } else {
            xAxis.setLabelCount(labels.size(), false);
        }

        // 动态留白逻辑：最大值提 20%
        YAxis yAxisLeft = binding.lineChart.getAxisLeft();
        yAxisLeft.setAxisMinimum(0f);
        if (maxValue > 0) {
            yAxisLeft.setAxisMaximum(maxValue * 1.2f);
        } else {
            yAxisLeft.setAxisMaximum(10f); // 无数据时默认高度
        }

        binding.lineChart.animateX(1000);
        binding.lineChart.invalidate();
    }

    private void generateAiSummary() {
        Integer minutes = viewModel.getTotalMinutes().getValue();
        Integer interventions = viewModel.getTotalInterventions().getValue();
        String history = viewModel.getDistractionHistory().getValue();

        int focusMinutes = minutes != null ? minutes : 0;
        int distractionCount = interventions != null ? interventions : 0;

        if (focusMinutes == 0 && distractionCount == 0) {
            binding.tvAiSummary.setText("暂无数据，开始专注后再来生成报告吧！");
            return;
        }

        binding.btnGenerateSummary.setEnabled(false);
        binding.tvAiSummary.setText("AI 正在生成报告...");
        GlmApiService.resetCancelState();

        if (aiSummaryTimeoutRunnable != null) mainHandler.removeCallbacks(aiSummaryTimeoutRunnable);
        aiSummaryTimeoutRunnable = () -> {
            if (binding != null && !binding.btnGenerateSummary.isEnabled()) {
                binding.tvAiSummary.setText("生成超时，请检查网络后重试");
                binding.btnGenerateSummary.setEnabled(true);
            }
        };
        mainHandler.postDelayed(aiSummaryTimeoutRunnable, 35000);

        SharedPreferences prefs = requireContext().getSharedPreferences("MindFlowPrefs", Context.MODE_PRIVATE);
        String goal = prefs.getString("confirmed_goal", "");
        if (goal == null || goal.trim().isEmpty()) goal = "（未设置）";

        String aiLog = distractionManager != null ? distractionManager.getAiRecognitionLog() : "";
        if (aiLog == null) aiLog = "";
        aiLog = aiLog.trim();
        if (aiLog.length() > 600) aiLog = aiLog.substring(aiLog.length() - 600);

        String focusRateText;
        if (focusMinutes > 0) {
            float rate = Math.max(0f, 1f - (distractionCount * 0.05f));
            int percent = Math.max(0, Math.min(100, Math.round(rate * 100f)));
            focusRateText = percent + "%";
        } else {
            focusRateText = "--";
        }

        String historyText = (history != null && !history.trim().isEmpty()) ? history.trim() : "无";
        String aiLogText = !aiLog.isEmpty() ? aiLog : "无";

        String prompt = "你是一个专注助手，负责根据分心记录给出简短复盘与建议。\n\n" +
                "用户目标：" + goal + "\n" +
                "周期：" + currentPeriodLabel + "\n" +
                "专注时长：" + focusMinutes + " 分钟\n" +
                "分心次数：" + distractionCount + " 次\n" +
                "估算专注率：" + focusRateText + "\n\n" +
                "分心记录：" + historyText + "\n" +
                "AI识别日志：" + aiLogText + "\n\n" +
                "请输出一段中文总结（150字以内），包含：\n" +
                "1. 简单评价专注情况\n" +
                "2. 主要分心点（1-2个）\n" +
                "3. 2条可执行建议\n" +
                "不要使用markdown，不要使用表情符号。";

        GlmApiService.analyzeText(prompt, new GlmApiService.AiCallback() {
            @Override
            public void onSuccess(String result) {
                mainHandler.removeCallbacks(aiSummaryTimeoutRunnable);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (binding != null) {
                        binding.tvAiSummary.setText(result);
                        binding.btnGenerateSummary.setEnabled(true);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                mainHandler.removeCallbacks(aiSummaryTimeoutRunnable);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (binding != null) {
                        binding.tvAiSummary.setText("生成失败：" + error + "\n请检查网络后重试");
                        binding.btnGenerateSummary.setEnabled(true);
                    }
                });
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        loadAiLog();

        // 🚨 终极修复：彻底干掉系统的自动记忆！
        // 每次进入报告页，强行把高亮按钮按在“今日”上，并且只加载今日数据！
        if (binding != null) {
            binding.chipGroupPeriod.check(R.id.chipToday); // UI 强制选中“今日”
            currentPeriodLabel = "今日";                     // 逻辑强制重置为“今日”
        }

        if (viewModel != null) {
            viewModel.loadData(ReportViewModel.Period.TODAY); // 永远只画饼图作为开局
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (aiSummaryTimeoutRunnable != null) {
            mainHandler.removeCallbacks(aiSummaryTimeoutRunnable);
            aiSummaryTimeoutRunnable = null;
        }
        binding = null;
    }

    private String formatSeconds(int totalSecs) {
        int m = totalSecs / 60;
        int s = totalSecs % 60;
        if (m > 0) return m + "分 " + s + "秒";
        return s + "秒";
    }

    private void showSessionDetailDialog(FocusSession session) {
        long totalActiveSec = session.focusTimeSec + session.distractionTimeSec;
        String rateStr = totalActiveSec == 0 ? "0%" : String.format("%.0f%%", (float)session.focusTimeSec / totalActiveSec * 100);

        String report = (session.aiReport == null || session.aiReport.trim().isEmpty()) ? "暂无AI分析报告" : session.aiReport;

        String detailMessage = "🎯 专注目标：" + (session.goalText != null ? session.goalText : "无") + "\n\n" +
                "✅ AI 净专注：" + formatSeconds(session.focusTimeSec) + "\n" +
                "⏳ AI 游离时长：" + formatSeconds(session.distractionTimeSec) + "\n" +
                "⚠️ 分心记录次数：" + session.distractionCount + " 次\n" +
                "📊 净专注率：" + rateStr + "\n\n" +
                "🤖 专属 AI 报告：\n" + report;

        new AlertDialog.Builder(requireContext())
                .setTitle("专注详情记录")
                .setMessage(detailMessage)
                .setPositiveButton("知道了", null)
                .show();
    }

    private class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.ViewHolder> {
        private List<FocusSession> sessionList = new ArrayList<>();
        private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

        public void setSessions(List<FocusSession> sessions) {
            this.sessionList = sessions;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_focus_session, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            FocusSession session = sessionList.get(position);
            holder.tvGoal.setText(session.goalText != null && !session.goalText.isEmpty() ? session.goalText : "无特定目标");
            holder.tvTime.setText(timeFormat.format(new Date(session.startTs)) + " - " + timeFormat.format(new Date(session.endTs)));

            int m = session.focusTimeSec / 60;
            int s = session.focusTimeSec % 60;
            if (m > 0) {
                holder.tvDuration.setText("净专注 " + m + "分" + s + "秒");
            } else {
                holder.tvDuration.setText("净专注 " + s + "秒");
            }

            holder.tvDistractions.setText("分心: " + session.distractionCount);
            holder.itemView.setOnClickListener(v -> showSessionDetailDialog(session));
        }

        @Override
        public int getItemCount() { return sessionList.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvGoal, tvTime, tvDuration, tvDistractions;
            ViewHolder(View itemView) {
                super(itemView);
                tvGoal = itemView.findViewById(R.id.tvSessionGoal);
                tvTime = itemView.findViewById(R.id.tvSessionTime);
                tvDuration = itemView.findViewById(R.id.tvSessionDuration);
                tvDistractions = itemView.findViewById(R.id.tvDistractionCount);
            }
        }
    }
}