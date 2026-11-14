package ai_study_planner;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "goals")
public class Goal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long goalId;

    private Long userId;

    private String goalName;

    private String goalType;

    private LocalDate examDate;

    private String targetScore;

    private String currentLevel;

    private String weakPoint;

    private Integer dailyStudyTime;

    private String preferredStudyMethod;

    private LocalDateTime createdAt;

    public Long getGoalId() {
        return goalId;
    }

    public void setGoalId(Long goalId) {
        this.goalId = goalId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getGoalName() {
        return goalName;
    }

    public void setGoalName(String goalName) {
        this.goalName = goalName;
    }

    public String getGoalType() {
        return goalType;
    }

    public void setGoalType(String goalType) {
        this.goalType = goalType;
    }

    public LocalDate getExamDate() {
        return examDate;
    }

    public void setExamDate(LocalDate examDate) {
        this.examDate = examDate;
    }

    public String getTargetScore() {
        return targetScore;
    }

    public void setTargetScore(String targetScore) {
        this.targetScore = targetScore;
    }

    public String getCurrentLevel() {
        return currentLevel;
    }

    public void setCurrentLevel(String currentLevel) {
        this.currentLevel = currentLevel;
    }

    public String getWeakPoint() {
        return weakPoint;
    }

    public void setWeakPoint(String weakPoint) {
        this.weakPoint = weakPoint;
    }

    public Integer getDailyStudyTime() {
        return dailyStudyTime;
    }

    public void setDailyStudyTime(Integer dailyStudyTime) {
        this.dailyStudyTime = dailyStudyTime;
    }

    public String getPreferredStudyMethod() {
        return preferredStudyMethod;
    }

    public void setPreferredStudyMethod(String preferredStudyMethod) {
        this.preferredStudyMethod = preferredStudyMethod;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}