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
        binding.trendChart.setUsePercentValues(true);
        binding.trendChart.getDescription().setEnabled(false);
        binding.trendChart.setExtraOffsets(5, 10, 5, 5);

        binding.trendChart.setDragDecelerationFrictionCoef(0.95f);

        binding.trendChart.setDrawHoleEnabled(true);
        binding.trendChart.setHoleColor(Color.WHITE);
        binding.trendChart.setTransparentCircleColor(Color.WHITE);
        binding.trendChart.setTransparentCircleAlpha(110);

        binding.trendChart.setHoleRadius(50f);
        binding.trendChart.setTransparentCircleRadius(55f);

        binding.trendChart.setDrawCenterText(true);
        binding.trendChart.setCenterText("时效\n占比");
        binding.trendChart.setCenterTextSize(16f);

        binding.trendChart.setRotationAngle(0);
        binding.trendChart.setRotationEnabled(true);
        binding.trendChart.setHighlightPerTapEnabled(true);

        binding.trendChart.getLegend().setEnabled(true);
    }

    private void loadAiLog() {
        if (binding == null) return;
        String logContent = distractionManager.getAiRecognitionLog();
        int logCount = distractionManager.getAiRecognitionLogCount();
        binding.tvAiLogCount.setText(logCount + "条");
        binding.tvAiLog.setText(logCount > 0 ? logContent : "暂无AI识别记录");
    }

    private void observeData() {
        viewModel.getTotalMinutes().observe(getViewLifecycleOwner(), minutes -> {
            binding.tvTotalMinutes.setText(String.valueOf(minutes != null ? minutes : 0));
            updateFocusRate();
        });

        viewModel.getTotalInterventions().observe(getViewLifecycleOwner(), count -> {
            binding.tvTotalInterventions.setText(String.valueOf(count != null ? count : 0));
            updateFocusRate();
        });

        viewModel.getPositiveFeedbackRate().observe(getViewLifecycleOwner(), rate -> {
            if (rate != null && rate > 0) {
                binding.tvPositiveFeedback.setText(String.format("%.0f%%", rate * 100));
            }
        });

        viewModel.getDistractionHistory().observe(getViewLifecycleOwner(), history -> {
            if (history != null && !history.isEmpty()) {
                binding.tvDistractionList.setText(history);
            } else {
                binding.tvDistractionList.setText("暂无分心记录，继续保持！");
            }
        });

        // 监听列表变化，同时更新列表和饼图
        viewModel.getSessionList().observe(getViewLifecycleOwner(), sessions -> {
            if (sessions == null || sessions.isEmpty()) {
                binding.tvSessionListTitle.setVisibility(View.GONE);
                binding.sessionRecyclerView.setVisibility(View.GONE);
                binding.trendChart.clear();
                binding.trendChart.setNoDataText("暂无专注数据，饼图睡着了");
            } else {
                binding.tvSessionListTitle.setVisibility(View.VISIBLE);
                binding.sessionRecyclerView.setVisibility(View.VISIBLE);
                sessionAdapter.setSessions(sessions);

                // 根据当前列表数据计算专注和分心的时间比例并画图
                updatePieChart(sessions);
            }
        });
    }

    // 渲染饼图逻辑
    private void updatePieChart(List<FocusSession> sessions) {
        long totalFocusSec = 0;
        long totalDistSec = 0;

        for (FocusSession s : sessions) {
            totalFocusSec += (s.actualMin * 60L); // 专注时间转为秒
            totalDistSec += s.distractionTimeSec; // 分心时间（秒）
        }

        // 如果都没时间，清空图表
        if (totalFocusSec == 0 && totalDistSec == 0) {
            binding.trendChart.clear();
            return;
        }

        List<PieEntry> entries = new ArrayList<>();

        // 加入专注时间
        if (totalFocusSec > 0) {
            entries.add(new PieEntry(totalFocusSec, "专注区"));
        }
        // 加入分心时间
        if (totalDistSec > 0) {
            entries.add(new PieEntry(totalDistSec, "游离区"));
        }

        PieDataSet dataSet = new PieDataSet(entries, "");
        dataSet.setSliceSpace(3f);
        dataSet.setSelectionShift(5f);

        // 设置专属配色
        ArrayList<Integer> colors = new ArrayList<>();
        colors.add(getResources().getColor(R.color.primary, null)); // 蓝色代表专注
        colors.add(getResources().getColor(R.color.warning, null)); // 橙色/红色代表分心
        dataSet.setColors(colors);

        PieData data = new PieData(dataSet);
        data.setValueFormatter(new PercentFormatter(binding.trendChart));
        data.setValueTextSize(14f);
        data.setValueTextColor(Color.WHITE);

        binding.trendChart.setData(data);
        binding.trendChart.highlightValues(null); // 撤销所有高亮
        binding.trendChart.animateY(1400); // 添加超帅的弹入动画
        binding.trendChart.invalidate();
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

    private void showSessionDetailDialog(FocusSession session) {
        float rate = Math.max(0, 100 - session.distractionCount * 5);
        String rateStr = String.format("%.0f%%", rate);

        String report = session.aiReport;
        if (report == null || report.trim().isEmpty()) {
            report = "AI 报告还在生成中，或者由于网络原因未成功生成。请稍后再来看看哦！";
        }

        String detailMessage = "🎯 专注目标：" + (session.goalText != null ? session.goalText : "无特定目标") + "\n\n" +
                "⏱️ 专注时长：" + session.actualMin + " 分钟\n" +
                "⏳ 分心时长：" + session.distractionTimeSec + " 秒\n" +
                "⚠️ 分心次数：" + session.distractionCount + " 次\n" +
                "📊 专注率：" + rateStr + "\n\n" +
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

            String startTime = timeFormat.format(new Date(session.startTs));
            String endTime = timeFormat.format(new Date(session.endTs));
            holder.tvTime.setText(startTime + " - " + endTime);

            holder.tvDuration.setText(session.actualMin + " min");
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