package ai_study_planner;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "study_logs")
public class StudyLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "subject_id")
    private Long subjectId;

    @Column(name = "study_date")
    private LocalDate studyDate;

    @Column(name = "study_minutes")
    private Integer studyMinutes;

    @Column(name = "study_content")
    private String studyContent;

    @Column(name = "understanding_score")
    private Integer understandingScore;

    @Column(name = "focus_score")
    private Integer focusScore;

    @Column(name = "fatigue_score")
    private Integer fatigueScore;

    @Column(name = "study_method")
    private String studyMethod;

    @Column(name = "total_questions")
    private Integer totalQuestions;

    @Column(name = "correct_count")
    private Integer correctCount;

    @Column(name = "wrong_count")
    private Integer wrongCount;

    @Column(name = "wrong_reason")
    private String wrongReason;

    @Column(name = "difficult_topic")
    private String difficultTopic;

    private String completed;

    private String memo;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getLogId() {
        return logId;
    }

    public void setLogId(Long logId) {
        this.logId = logId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public LocalDate getStudyDate() {
        return studyDate;
    }

    public void setStudyDate(LocalDate studyDate) {
        this.studyDate = studyDate;
    }

    public Integer getStudyMinutes() {
        return studyMinutes;
    }

    public void setStudyMinutes(Integer studyMinutes) {
        this.studyMinutes = studyMinutes;
    }

    public String getStudyContent() {
        return studyContent;
    }

    public void setStudyContent(String studyContent) {
        this.studyContent = studyContent;
    }

    public Integer getUnderstandingScore() {
        return understandingScore;
    }

    public void setUnderstandingScore(Integer understandingScore) {
        this.understandingScore = understandingScore;
    }

    public Integer getFocusScore() {
        return focusScore;
    }

    public void setFocusScore(Integer focusScore) {
        this.focusScore = focusScore;
    }

    public Integer getFatigueScore() {
        return fatigueScore;
    }

    public void setFatigueScore(Integer fatigueScore) {
        this.fatigueScore = fatigueScore;
    }

    public String getStudyMethod() {
        return studyMethod;
    }

    public void setStudyMethod(String studyMethod) {
        this.studyMethod = studyMethod;
    }

    public Integer getTotalQuestions() {
        return totalQuestions;
    }

    public void setTotalQuestions(Integer totalQuestions) {
        this.totalQuestions = totalQuestions;
    }

    public Integer getCorrectCount() {
        return correctCount;
    }

    public void setCorrectCount(Integer correctCount) {
        this.correctCount = correctCount;
    }

    public Integer getWrongCount() {
        return wrongCount;
    }

    public void setWrongCount(Integer wrongCount) {
        this.wrongCount = wrongCount;
    }

    public String getWrongReason() {
        return wrongReason;
    }

    public void setWrongReason(String wrongReason) {
        this.wrongReason = wrongReason;
    }

    public String getDifficultTopic() {
        return difficultTopic;
    }

    public void setDifficultTopic(String difficultTopic) {
        this.difficultTopic = difficultTopic;
    }

    public String getCompleted() {
        return completed;
    }

    public void setCompleted(String completed) {
        this.completed = completed;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}