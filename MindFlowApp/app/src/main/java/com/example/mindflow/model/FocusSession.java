package com.example.mindflow.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 专注会话实体
 * 对应 sessions.csv 表结构
 */
@Entity(tableName = "focus_sessions")
public class FocusSession {
    @PrimaryKey(autoGenerate = true)
    public long id;
    
    public String sessionId;
    public String sessionType;      // learning, work, etc.
    public String goalText;          // 专注目标文本
    public int plannedMin;           // 计划时长(分钟)
    public int actualMin;            // 实际时长(分钟)
    public long startTs;             // 开始时间戳(UTC毫秒)
    public long endTs;               // 结束时间戳(UTC毫秒)
    public int distractionCount;     // 分心次数
    public int interventionCount;    // 干预次数
    public int selfFocusScore;       // 自评专注度(1-5)
    public int selfFatigueScore;     // 自评疲劳度(1-5)
    public boolean isActive;         // 会话是否进行中

    // === 新增：专属 AI 分析报告 ===
    public String aiReport;

    public int distractionTimeSec;
    public FocusSession() {}
}
