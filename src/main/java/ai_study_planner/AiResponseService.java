package ai_study_planner;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.PageRequest;
import java.util.List;
import java.util.Objects;

@Service
public class AiResponseService {
    private final AiResponseRepository responses;
    private final StudyQuestionRepository questions;
    private final GoalRepository goals;
    public AiResponseService(AiResponseRepository responses, StudyQuestionRepository questions, GoalRepository goals) {
        this.responses=responses; this.questions=questions; this.goals=goals;
    }
    // Preserve the existing authenticated DEMO_OWNER -> user 1 mapping; never accept userId from clients.
    long owner() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !authentication.isAuthenticated() || authentication.getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().equals("ROLE_DEMO_OWNER")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"접근 권한이 필요합니다.");
        return 1L;
    }
    public void requireGoal(Long goalId) {
        long userId=owner();
        if(goalId!=null && (goalId<=0 || goals.findById(goalId)
                .filter(g -> Objects.equals(g.getUserId(),userId)).isEmpty()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"선택한 목표를 찾을 수 없습니다.");
    }
    /** Called only AFTER transport completes. Flush/commit failures propagate to the caller's warning boundary. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiResponse save(Long goalId, AiResponse.Feature feature, String requestedProvider,
                           String actualProvider, String model, AiResponse.Source source,
                           String body, Boolean complete, StudyQuestion question) {
        long userId=owner(); requireGoal(goalId);
        if(body==null || body.isBlank()) throw new IllegalArgumentException("Empty response");
        Long questionId=null;
        if(question!=null) {
            if(feature!=AiResponse.Feature.QUESTION || question.getQuestionId()!=null)
                throw new IllegalArgumentException("Only new questions may be stored");
            question.setUserId(userId);
            questionId=questions.saveAndFlush(question).getQuestionId();
        }
        return responses.saveAndFlush(new AiResponse(userId,goalId,questionId,feature,requestedProvider,
                source==AiResponse.Source.PROVIDER ? actualProvider : null,
                source==AiResponse.Source.UNKNOWN ? null : model,source,body,complete));
    }
    @Transactional(readOnly = true)
    public List<AiResponse> history(Long goalId, AiResponse.Feature feature) {
        requireGoal(goalId);
        return responses.history(owner(),goalId,feature,PageRequest.of(0,20));
    }
    @Transactional(readOnly = true)
    public AiResponse find(long id) {
        long userId=owner();
        AiResponse response=responses.findById(id).filter(a -> Objects.equals(a.getUserId(),userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"저장 응답을 찾을 수 없습니다."));
        requireGoal(response.getGoalId());
        return response;
    }
}
