package ai_study_planner;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Immutable response snapshot; does not reference or modify saved study plans. */
@Entity
@Table(name = "ai_responses", indexes = {
    @Index(name = "idx_ai_response_user_feature", columnList = "user_id,feature,created_at"),
    @Index(name = "idx_ai_response_user_goal", columnList = "user_id,goal_id,created_at")
})
public class AiResponse {
    public enum Feature { DAILY_COACHING, QUESTION, WEEKLY_COACHING }
    public enum Source { PROVIDER, RULE, UNKNOWN }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "response_id") private Long responseId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "goal_id") private Long goalId;
    @Column(name = "question_id") private Long questionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32, columnDefinition = "VARCHAR(32)") private Feature feature;
    @Column(name = "requested_provider", length = 32) private String requestedProvider;
    @Column(name = "actual_provider", length = 32) private String actualProvider;
    @Column(length = 150) private String model;
    @Enumerated(EnumType.STRING) @Column(name = "response_source", nullable = false, length = 16, columnDefinition = "VARCHAR(16)") private Source responseSource;
    @Column(name = "response_body", nullable = false, columnDefinition = "MEDIUMTEXT") private String responseBody;
    @Column(name = "response_complete") private Boolean responseComplete;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

    protected AiResponse() {}
    AiResponse(Long userId, Long goalId, Long questionId, Feature feature, String requestedProvider,
               String actualProvider, String model, Source source, String body, Boolean complete) {
        this.userId=userId; this.goalId=goalId; this.questionId=questionId; this.feature=feature;
        this.requestedProvider=requestedProvider; this.actualProvider=actualProvider; this.model=model;
        this.responseSource=source; this.responseBody=body; this.responseComplete=complete;
        this.createdAt=LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"));
    }
    public Long getResponseId(){return responseId;}
    public Long getUserId(){return userId;}
    public Long getGoalId(){return goalId;}
    public Long getQuestionId(){return questionId;}
    public Feature getFeature(){return feature;}
    public String getRequestedProvider(){return requestedProvider;}
    public String getActualProvider(){return actualProvider;}
    public String getModel(){return model;}
    public Source getResponseSource(){return responseSource;}
    public String getResponseBody(){return responseBody;}
    public Boolean getResponseComplete(){return responseComplete;}
    public LocalDateTime getCreatedAt(){return createdAt;}
}
