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
        setupPieChartBasic();
        observeData();
        loadAiLog();

        viewModel.loadData(ReportViewModel.Period.TODAY);
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

    // 初始化饼图基本属性
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

        // 2. 设置柱状图 (修复没有初始化的报错)
        binding.barChart.getDescription().setEnabled(false);
        binding.barChart.setDrawGridBackground(false);
        binding.barChart.getXAxis().setPosition(com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM);
        binding.barChart.getXAxis().setDrawGridLines(false);
        binding.barChart.getAxisRight().setEnabled(false);
    }

    private void loadAiLog() {
        if (binding == null) return;
        String logContent = distractionManager.getAiRecognitionLog();
        int logCount = distractionManager.getAiRecognitionLogCount();
        binding.tvAiLogCount.setText(logCount + "条");
        binding.tvAiLog.setText(logCount > 0 ? logContent : "暂无AI识别记录");
    }

    private void observeData() {
        // 🚨 核心修复 1：把原来 viewModel.getTotalMinutes().observe 那段代码删掉或注释掉，
        // 因为那是读挂钟时间的，我们现在要在下面自己算“纯净时间”！

        viewModel.getTotalInterventions().observe(getViewLifecycleOwner(), count -> {
            binding.tvTotalInterventions.setText(String.valueOf(count != null ? count : 0));
        });

        viewModel.getChartData().observe(getViewLifecycleOwner(), chartPoints -> {
            if ("今日".equals(currentPeriodLabel)) {
                binding.pieChart.setVisibility(View.VISIBLE);
                binding.barChart.setVisibility(View.GONE);
                binding.tvChartTitle.setText("今日时间分配占比");
            } else {
                binding.pieChart.setVisibility(View.GONE);
                binding.barChart.setVisibility(View.VISIBLE);
                binding.tvChartTitle.setText(currentPeriodLabel + "专注时长分布");
                renderBarChart(chartPoints);
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

                // 🚨 核心修复 2：亲自计算绝对纯净的 AI 专注/分心总时长
                long totalFocusSec = 0;
                long totalDistSec = 0;
                for (FocusSession s : sessions) {
                    totalFocusSec += s.focusTimeSec;
                    totalDistSec += s.distractionTimeSec;
                }

                // 强制更新顶部大字号卡片：“纯净专注分钟数”
                int pureFocusMinutes = (int) (totalFocusSec / 60);
                binding.tvTotalMinutes.setText(String.valueOf(pureFocusMinutes));

                // 强制更新右上角卡片：“真实专注率”
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

    // 渲染饼图逻辑
    // 渲染饼图逻辑
    private void updatePieChart(List<FocusSession> sessions) {
        long totalFocusSec = 0;
        long totalDistSec = 0;

        for (FocusSession s : sessions) {
            totalFocusSec += s.focusTimeSec;
            totalDistSec += s.distractionTimeSec;
        }

        // 如果都没时间，清空图表
        if (totalFocusSec == 0 && totalDistSec == 0) {
            binding.pieChart.clear(); // 这里改成了 pieChart
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
        data.setValueFormatter(new PercentFormatter(binding.pieChart)); // 改成了 pieChart
        data.setValueTextSize(14f);
        data.setValueTextColor(Color.WHITE);

        binding.pieChart.setData(data); // 改成了 pieChart
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

    private void renderBarChart(List<com.example.mindflow.ui.report.ReportViewModel.ChartPoint> chartPoints) {
        if (chartPoints == null || chartPoints.isEmpty()) {
            binding.barChart.clear();
            return;
        }
        List<com.github.mikephil.charting.data.BarEntry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < chartPoints.size(); i++) {
            entries.add(new com.github.mikephil.charting.data.BarEntry(i, chartPoints.get(i).value));
            labels.add(chartPoints.get(i).label);
        }
        com.github.mikephil.charting.data.BarDataSet dataSet = new com.github.mikephil.charting.data.BarDataSet(entries, "分钟");
        dataSet.setColor(getResources().getColor(R.color.primary, null));
        com.github.mikephil.charting.data.BarData barData = new com.github.mikephil.charting.data.BarData(dataSet);
        binding.barChart.setData(barData);
        binding.barChart.getXAxis().setValueFormatter(new com.github.mikephil.charting.formatter.IndexAxisValueFormatter(labels));
        binding.barChart.animateY(800);
        binding.barChart.invalidate();
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
        if (viewModel != null) {
            if ("今日".equals(currentPeriodLabel)) viewModel.loadData(ReportViewModel.Period.TODAY);
            else if ("本周".equals(currentPeriodLabel)) viewModel.loadData(ReportViewModel.Period.WEEK);
            else if ("本月".equals(currentPeriodLabel)) viewModel.loadData(ReportViewModel.Period.MONTH);
            else if ("本年".equals(currentPeriodLabel)) viewModel.loadData(ReportViewModel.Period.YEAR);
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

    // 将秒转换成分秒格式 (加在这个方法上面)
    private String formatSeconds(int totalSecs) {
        int m = totalSecs / 60;
        int s = totalSecs % 60;
        if (m > 0) return m + "分 " + s + "秒";
        return s + "秒";
    }

    private void showSessionDetailDialog(FocusSession session) {
        long totalActiveSec = session.focusTimeSec + session.distractionTimeSec;
        String rateStr = totalActiveSec == 0 ? "0%" : String.format("%.0f%%", (float)session.focusTimeSec / totalActiveSec * 100);

        String report = session.aiReport;
        if (report == null || report.trim().isEmpty()) {
            report = "AI 报告还在生成中，或者由于网络原因未成功生成。请稍后再来看看哦！";
        }

        String detailMessage = "🎯 专注目标：" + (session.goalText != null ? session.goalText : "无特定目标") + "\n\n" +
                "⏱️ 挂钟耗时：" + session.actualMin + " 分钟 (含锁屏)\n" +
                "✅ 净专注区：" + formatSeconds(session.focusTimeSec) + "\n" +
                "⏳ 游离时长：" + formatSeconds(session.distractionTimeSec) + "\n" +
                "⚠️ 分心次数：" + session.distractionCount + " 次\n" +
                "📊 纯净专注率：" + rateStr + "\n\n" +
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

            // 🚨 核心修复 3：列表展示纯净专注时间，而不是糊弄人的挂钟时间
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