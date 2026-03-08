package com.example.mindflow.ui.session;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.mindflow.database.MindFlowDatabase;
import com.example.mindflow.database.dao.FocusSessionDao;
import com.example.mindflow.model.FocusSession;

import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Session ViewModel
 * 管理专注会话的状态和业务逻辑
 */
public class SessionViewModel extends AndroidViewModel {

    private static final String TAG = "MindFlowDB";
    private final FocusSessionDao sessionDao;
    private FocusSession currentSession;

    private final MutableLiveData<Integer> distractionCount = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> interventionCount = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> focusScore = new MutableLiveData<>(null);
    private final MutableLiveData<Boolean> isSessionActive = new MutableLiveData<>(false);

    public SessionViewModel(@NonNull Application application) {
        super(application);
        MindFlowDatabase db = MindFlowDatabase.getInstance(application);
        sessionDao = db.focusSessionDao();

        // 核心修复：如果因为锁屏、切后台导致 ViewModel 被系统销毁重建，
        // 我们在它刚诞生时，立刻去数据库把之前进行中的任务捞回来，恢复记忆！
        Executors.newSingleThreadExecutor().execute(() -> {
            FocusSession activeSession = sessionDao.getActiveSessionSync();
            if (activeSession != null) {
                currentSession = activeSession;
                // 切回主线程更新 UI 绑定的 LiveData
                new Handler(Looper.getMainLooper()).post(() -> {
                    distractionCount.setValue(activeSession.distractionCount);
                    interventionCount.setValue(activeSession.interventionCount);
                    isSessionActive.setValue(true);
                    Log.d(TAG, "ViewModel 重建，已从数据库恢复未完成的专注记录！分心次数：" + activeSession.distractionCount);
                });
            }
        });
    }

    /**
     * 开始新的专注会话
     */
    public void startSession(int plannedMinutes, String goalText) {
        currentSession = new FocusSession();
        currentSession.sessionId = UUID.randomUUID().toString();
        currentSession.sessionType = "focus";
        currentSession.goalText = goalText;
        currentSession.plannedMin = plannedMinutes;
        currentSession.startTs = System.currentTimeMillis();
        currentSession.isActive = true;
        currentSession.distractionCount = 0;
        currentSession.interventionCount = 0;
        currentSession.aiReport = ""; // 初始化 AI 报告为空

        // 插入数据库
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                long id = sessionDao.insert(currentSession);
                currentSession.id = id;
                Log.d(TAG, ">>> Insert 成功！生成的 ID 是: " + id);
            } catch (Exception e) {
                Log.e(TAG, ">>> Insert 彻底失败，原因在这里: ", e);
            }
        });

        // 重置前端状态
        distractionCount.setValue(0);
        interventionCount.setValue(0);
        focusScore.setValue(null);
        isSessionActive.setValue(true);
    }

    /**
     * 结束当前专注会话
     */
    public void endSession(int actualMinutes, int exactDistractions, int distTimeSec) {
        final int currentInterventions = interventionCount.getValue() != null ? interventionCount.getValue() : 0;

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                FocusSession sessionToUpdate = currentSession;
                if (sessionToUpdate == null) {
                    sessionToUpdate = sessionDao.getActiveSessionSync();
                }

                if (sessionToUpdate != null) {
                    sessionToUpdate.endTs = System.currentTimeMillis();
                    sessionToUpdate.actualMin = actualMinutes;
                    sessionToUpdate.isActive = false;
                    sessionToUpdate.interventionCount = currentInterventions;

                    // 存入最真实的分心次数和新加的【分心时长】
                    sessionToUpdate.distractionCount = exactDistractions;
                    sessionToUpdate.distractionTimeSec = distTimeSec;

                    int score = calculateFocusScore(actualMinutes, sessionToUpdate.plannedMin, exactDistractions);
                    sessionToUpdate.selfFocusScore = score;

                    sessionDao.update(sessionToUpdate);
                    Log.d(TAG, ">>> Update 成功！实际专注时间: " + actualMinutes + " 分钟，分心秒数：" + distTimeSec);

                    new Handler(Looper.getMainLooper()).post(() -> {
                        focusScore.setValue(score);
                        isSessionActive.setValue(false);
                        currentSession = null;
                    });
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> isSessionActive.setValue(false));
                }
            } catch (Exception e) {
                Log.e(TAG, ">>> Update 彻底抛出异常报错了: ", e);
            }
        });
    }

    /**
     * 获取当前正在进行的 Session ID，给 AI 报告挂载用
     */
    public long getCurrentSessionId() {
        if (currentSession != null) return currentSession.id;
        FocusSession active = sessionDao.getActiveSessionSync();
        return active != null ? active.id : -1;
    }

    /**
     * 专门用于接收 AI 分析结果并存入数据库
     */
    public void saveAiReport(long sessionId, String aiReport) {
        if (sessionId == -1) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            FocusSession session = sessionDao.getSessionById(sessionId);
            if (session != null) {
                session.aiReport = aiReport;
                sessionDao.update(session);
                Log.d(TAG, ">>> AI 报告已成功存入数据库！SessionID: " + sessionId);
            }
        });
    }

    /**
     * 记录一次分心事件
     * 核心修复：每次分心，立刻存入数据库，绝不信任易挥发的内存！
     */
    public void recordDistraction() {
        // 1. 先更新 UI 上的数字（为了反馈及时）
        Integer count = distractionCount.getValue();
        int nextCount = (count != null ? count + 1 : 1);
        distractionCount.setValue(nextCount);

        // 2. 核心修复：直接去数据库里“取出来，加1，再存回去”
        Executors.newSingleThreadExecutor().execute(() -> {
            FocusSession active = sessionDao.getActiveSessionSync();
            if (active != null) {
                // 关键点：基于数据库现有的数字累加，而不是基于内存
                int updatedCount = active.distractionCount + 1;
                active.distractionCount = updatedCount;
                sessionDao.update(active);
                Log.d(TAG, ">>> 数据库分心次数已安全累加至: " + updatedCount);
            }
        });
    }

    /**
     * 记录一次干预事件
     */
    public void recordIntervention() {
        Integer count = interventionCount.getValue();
        interventionCount.setValue(count != null ? count + 1 : 1);
        if (currentSession != null) {
            currentSession.interventionCount++;
        }
    }

    /**
     * 简单的评分算法
     */
    private int calculateFocusScore(int actualMin, int plannedMin, Integer distractions) {
        float completionRate = plannedMin > 0 ? (float) actualMin / plannedMin : 1f;
        int distractionPenalty = distractions != null ? distractions : 0;
        float score = 5 * completionRate - distractionPenalty * 0.5f;
        score = Math.max(1, Math.min(5, score));
        return Math.round(score);
    }

    // --- Getters ---
    public LiveData<Integer> getDistractionCount() { return distractionCount; }
    public LiveData<Integer> getInterventionCount() { return interventionCount; }
    public LiveData<Integer> getFocusScore() { return focusScore; }
    public LiveData<Boolean> getIsSessionActive() { return isSessionActive; }
}