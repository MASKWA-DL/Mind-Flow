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

        // 锁屏恢复：捞回进度
        Executors.newSingleThreadExecutor().execute(() -> {
            FocusSession activeSession = sessionDao.getActiveSessionSync();
            if (activeSession != null) {
                currentSession = activeSession;
                new Handler(Looper.getMainLooper()).post(() -> {
                    distractionCount.setValue(activeSession.distractionCount);
                    interventionCount.setValue(activeSession.interventionCount);
                    isSessionActive.setValue(true);
                });
            }
        });
    }

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
        currentSession.distractionTimeSec = 0;
        currentSession.focusTimeSec = 0;
        currentSession.aiReport = "";

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                long id = sessionDao.insert(currentSession);
                currentSession.id = id;
            } catch (Exception e) {
                Log.e(TAG, "Insert 异常: ", e);
            }
        });

        distractionCount.setValue(0);
        interventionCount.setValue(0);
        focusScore.setValue(null);
        isSessionActive.setValue(true);
    }

    // 终极结束逻辑：不信内存，只认数据库！接收纯净的专注/分心秒数
    public void endSession(int actualMinutes, int distTimeSec, int focusTimeSec) {
        final int currentInterventions = interventionCount.getValue() != null ? interventionCount.getValue() : 0;

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                FocusSession dbSession = sessionDao.getActiveSessionSync();
                if (dbSession != null) {
                    dbSession.endTs = System.currentTimeMillis();
                    dbSession.actualMin = actualMinutes;
                    dbSession.isActive = false;
                    dbSession.interventionCount = currentInterventions;

                    // 记录真实的秒表时间
                    dbSession.distractionTimeSec = distTimeSec;
                    dbSession.focusTimeSec = focusTimeSec;

                    // 读取数据库中绝对真实的累加分心次数
                    int finalDistractions = dbSession.distractionCount;
                    int score = calculateFocusScore(actualMinutes, dbSession.plannedMin, finalDistractions);
                    dbSession.selfFocusScore = score;

                    sessionDao.update(dbSession);
                    Log.d(TAG, ">>> 数据库安全更新！实际专注时间: " + actualMinutes + " 分钟，总分心次数锁定: " + finalDistractions + "次");

                    new Handler(Looper.getMainLooper()).post(() -> {
                        focusScore.setValue(score);
                        isSessionActive.setValue(false);
                        currentSession = null;
                    });
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> isSessionActive.setValue(false));
                }
            } catch (Exception e) {
                Log.e(TAG, "Update 异常: ", e);
            }
        });
    }

    public long getCurrentSessionId() {
        if (currentSession != null) return currentSession.id;
        FocusSession active = sessionDao.getActiveSessionSync();
        return active != null ? active.id : -1;
    }

    public void saveAiReport(long sessionId, String aiReport) {
        if (sessionId == -1) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            FocusSession session = sessionDao.getSessionById(sessionId);
            if (session != null) {
                session.aiReport = aiReport;
                sessionDao.update(session);
            }
        });
    }

    // 🚨 核心修复：直接操作数据库累加分心次数，绝不丢失！
    public void recordDistraction() {
        Executors.newSingleThreadExecutor().execute(() -> {
            FocusSession activeFromDb = sessionDao.getActiveSessionSync();
            if (activeFromDb != null) {
                activeFromDb.distractionCount += 1;
                sessionDao.update(activeFromDb);

                int trueCount = activeFromDb.distractionCount;
                new Handler(Looper.getMainLooper()).post(() -> {
                    distractionCount.setValue(trueCount);
                });
                Log.d(TAG, ">>> 坚不可摧的累加！当前数据库分心次数: " + trueCount);
            }
        });
    }

    public void recordIntervention() {
        Integer count = interventionCount.getValue();
        interventionCount.setValue(count != null ? count + 1 : 1);
        if (currentSession != null) currentSession.interventionCount++;
    }

    private int calculateFocusScore(int actualMin, int plannedMin, Integer distractions) {
        float completionRate = plannedMin > 0 ? (float) actualMin / plannedMin : 1f;
        int distractionPenalty = distractions != null ? distractions : 0;
        float score = 5 * completionRate - distractionPenalty * 0.5f;
        score = Math.max(1, Math.min(5, score));
        return Math.round(score);
    }

    public LiveData<Integer> getDistractionCount() { return distractionCount; }
    public LiveData<Integer> getInterventionCount() { return interventionCount; }
    public LiveData<Integer> getFocusScore() { return focusScore; }
    public LiveData<Boolean> getIsSessionActive() { return isSessionActive; }
}