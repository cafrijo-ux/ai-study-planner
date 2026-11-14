package ai_study_planner;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "study_questions")
public class StudyQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_id")
    private Long questionId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "subject")
    private String subject;

    @Column(name = "situation")
    private String situation;

    @Column(name = "understanding")
    private String understanding;

    @Column(name = "question", columnDefinition = "TEXT")
    private String question;

    @Column(name = "ai_advice", columnDefinition = "TEXT")
    private String aiAdvice;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getSituation() {
        return situation;
    }

    public void setSituation(String situation) {
        this.situation = situation;
    }

    public String getUnderstanding() {
        return understanding;
    }

    public void setUnderstanding(String understanding) {
        this.understanding = understanding;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAiAdvice() {
        return aiAdvice;
    }

    public void setAiAdvice(String aiAdvice) {
        this.aiAdvice = aiAdvice;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}