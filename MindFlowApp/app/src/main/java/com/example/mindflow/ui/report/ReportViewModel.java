package com.example.mindflow.ui.report;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.mindflow.database.MindFlowDatabase;
import com.example.mindflow.database.dao.FocusSessionDao;
import com.example.mindflow.database.dao.InterventionDao;
import com.example.mindflow.database.dao.LabelWindowDao;
import com.example.mindflow.model.FocusSession;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Report ViewModel
 * 管理报告页面的数据加载和统计
 */
public class ReportViewModel extends AndroidViewModel {

    // 新增 YEAR，支持你的“每年趋势”需求
    public enum Period {
        TODAY, WEEK, MONTH, YEAR
    }

    public static class CognitiveDistribution {
        public float deepFocus;
        public float lightFocus;
        public float leisure;
        public float highStress;
        public float relaxed;
    }

    // 新增：图表数据点封装类
    public static class ChartPoint {
        public String label; // X轴标签
        public float value;  // Y轴数值（专注时长）
        public ChartPoint(String label, float value) {
            this.label = label;
            this.value = value;
        }
    }

    private final FocusSessionDao sessionDao;
    private final InterventionDao interventionDao;
    private final LabelWindowDao labelDao;

    // 原有 LiveData
    private final MutableLiveData<Integer> totalMinutes = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> totalInterventions = new MutableLiveData<>(0);
    private final MutableLiveData<Float> positiveFeedbackRate = new MutableLiveData<>(0f);
    private final MutableLiveData<CognitiveDistribution> cognitiveDistribution = new MutableLiveData<>();
    private final MutableLiveData<String> distractionHistory = new MutableLiveData<>("");

    // 新增 LiveData：单次专注列表（主要用于 TODAY）
    private final MutableLiveData<List<FocusSession>> sessionList = new MutableLiveData<>();

    // 新增 LiveData：图表趋势数据
    private final MutableLiveData<List<ChartPoint>> chartData = new MutableLiveData<>();

    public ReportViewModel(@NonNull Application application) {
        super(application);

        MindFlowDatabase db = MindFlowDatabase.getInstance(application);
        sessionDao = db.focusSessionDao();
        interventionDao = db.interventionDao();
        labelDao = db.labelWindowDao();
    }

    public void loadData(Period period) {
        long startTime = getStartTime(period);
        long endTime = System.currentTimeMillis();

        Executors.newSingleThreadExecutor().execute(() -> {
            // === 原有逻辑：加载总体统计数据 ===
            int dbMinutes = sessionDao.getTotalFocusMinutesSince(startTime);

            SharedPreferences prefs = getApplication().getSharedPreferences("MindFlowPrefs", Context.MODE_PRIVATE);
            int currentSessionMinutes = prefs.getInt("current_session_minutes", 0);
            int currentDistractions = prefs.getInt("current_distractions", 0);
            String currentHistory = prefs.getString("current_distraction_history", "");

            totalMinutes.postValue(dbMinutes + currentSessionMinutes);
            int dbReminders = interventionDao.getReminderCountSince(startTime);
            totalInterventions.postValue(dbReminders);
            distractionHistory.postValue(currentHistory);

            int totalMins = dbMinutes + currentSessionMinutes;
            int totalDist = dbReminders + currentDistractions; // 加上当前的更精确
            if (totalMins > 0) {
                float rate = Math.max(0, (100f - totalDist * 5f) / 100f);
                positiveFeedbackRate.postValue(rate);
            } else {
                positiveFeedbackRate.postValue(0f);
            }

            List<LabelWindowDao.CognitiveStateCount> stateCounts = labelDao.getCognitiveStateDistribution(startTime);
            CognitiveDistribution distribution = new CognitiveDistribution();
            int total = 0;
            for (LabelWindowDao.CognitiveStateCount count : stateCounts) total += count.count;

            if (total > 0) {
                for (LabelWindowDao.CognitiveStateCount count : stateCounts) {
                    float percent = (float) count.count / total;
                    switch (count.cognitiveState) {
                        case "深度专注": distribution.deepFocus = percent; break;
                        case "轻度专注": distribution.lightFocus = percent; break;
                        case "休闲刷屏": distribution.leisure = percent; break;
                        case "高压忙乱": distribution.highStress = percent; break;
                        case "放松休息": distribution.relaxed = percent; break;
                    }
                }
            }
            cognitiveDistribution.postValue(distribution);

            // === 新增逻辑：加载单次记录与图表趋势 ===
            List<FocusSession> sessions = sessionDao.getSessionsInRange(startTime, endTime);

            // 只有选择“今天”时，才在底部展示每一次的详细列表
            if (period == Period.TODAY) {
                sessionList.postValue(sessions);
            } else {
                sessionList.postValue(new ArrayList<>());
            }

            // 处理并发布图表数据
            List<ChartPoint> points = processChartData(sessions, period);
            chartData.postValue(points);
        });
    }

    // === 新增：图表数据聚合引擎 ===
    private List<ChartPoint> processChartData(List<FocusSession> sessions, Period period) {
        List<ChartPoint> points = new ArrayList<>();
        Calendar calendar = Calendar.getInstance();

        if (period == Period.WEEK) {
            float[] weekMinutes = new float[7];
            for (FocusSession session : sessions) {
                calendar.setTimeInMillis(session.startTs);
                int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
                int index = (dayOfWeek == Calendar.SUNDAY) ? 6 : dayOfWeek - 2;
                weekMinutes[index] += session.actualMin;
            }
            String[] days = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            for (int i = 0; i < 7; i++) {
                points.add(new ChartPoint(days[i], weekMinutes[i]));
            }
        } else if (period == Period.MONTH) {
            // 本月趋势：假设按 4 周划分展示，或按天展示。这里为了直观，按前、中、下旬简化，或你可以在UI端实现滑动条。
            // 这里为了通用性，先累加一个总数，实际开发中可以根据图表库按天绘制30个点。
            float total = 0;
            for (FocusSession s : sessions) total += s.actualMin;
            points.add(new ChartPoint("本月汇总", total));
        } else if (period == Period.YEAR) {
            float[] monthMinutes = new float[12];
            for (FocusSession session : sessions) {
                calendar.setTimeInMillis(session.startTs);
                int month = calendar.get(Calendar.MONTH);
                monthMinutes[month] += session.actualMin;
            }
            for (int i = 0; i < 12; i++) {
                points.add(new ChartPoint((i + 1) + "月", monthMinutes[i]));
            }
        } else if (period == Period.TODAY) {
            float total = 0;
            for (FocusSession s : sessions) total += s.actualMin;
            points.add(new ChartPoint("今日总计", total));
        }

        return points;
    }

    private long getStartTime(Period period) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        switch (period) {
            case WEEK:
                calendar.setFirstDayOfWeek(Calendar.MONDAY);
                calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
                break;
            case MONTH:
                calendar.set(Calendar.DAY_OF_MONTH, 1);
                break;
            case YEAR: // 新增逻辑
                calendar.set(Calendar.MONTH, Calendar.JANUARY);
                calendar.set(Calendar.DAY_OF_MONTH, 1);
                break;
            case TODAY:
            default:
                break;
        }

        return calendar.getTimeInMillis();
    }

    // --- Getters ---
    public LiveData<Integer> getTotalMinutes() { return totalMinutes; }
    public LiveData<Integer> getTotalInterventions() { return totalInterventions; }
    public LiveData<Float> getPositiveFeedbackRate() { return positiveFeedbackRate; }
    public LiveData<CognitiveDistribution> getCognitiveStateDistribution() { return cognitiveDistribution; }
    public LiveData<String> getDistractionHistory() { return distractionHistory; }

    // 新增 Getters
    public LiveData<List<FocusSession>> getSessionList() { return sessionList; }
    public LiveData<List<ChartPoint>> getChartData() { return chartData; }

    public void setDistractionHistory(String history) {
        distractionHistory.postValue(history);
    }
}