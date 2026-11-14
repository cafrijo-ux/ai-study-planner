package ai_study_planner;

import org.springframework.stereotype.Component;
import java.util.Map;
import static ai_study_planner.AiResponse.*;

/** Non-transactional boundary: retains a paid answer even when database commit fails. Never retries AI. */
@Component
public class AiResponseRecorder {
    private final AiResponseService service;
    public AiResponseRecorder(AiResponseService service){this.service=service;}
    public void requireGoal(Long goalId){service.requireGoal(goalId);}
    public void record(Map<String,Object> result, Feature feature, String requestedProvider,
                       Long goalId, StudyQuestion question) {
        String key=feature==Feature.QUESTION ? "aiAdvice" : "llmResponse";
        if(!(result.get(key) instanceof String body) || body.isBlank()) return;
        Source source=switch(String.valueOf(result.get("responseSource"))) {
            case "provider" -> Source.PROVIDER; case "rule" -> Source.RULE; default -> Source.UNKNOWN;
        };
        String actual=source==Source.PROVIDER
                ? ("gemini".equalsIgnoreCase(requestedProvider) ? "gemini" : "openai") : null;
        String model=source==Source.RULE ? "rule-based" : text(result.getOrDefault("responseModel",result.get("model")));
        try {
            AiResponse saved=service.save(goalId,feature,requestedProvider,actual,model,source,body,
                    result.get("responseComplete") instanceof Boolean b ? b : null,question);
            result.put("responseSaved",true); result.put("responseId",saved.getResponseId());
            result.put("responseCreatedAt",saved.getCreatedAt());
            if(question!=null) {
                result.put("saved",true); result.put("questionId",saved.getQuestionId());
                result.put("createdAt",question.getCreatedAt());
            }
        } catch(RuntimeException failure) {
            result.put("responseSaved",false);
            if(question!=null) result.put("saved",false);
            result.put("saveWarning","답변은 생성됐지만 기록 저장에 실패했습니다. 현재 답변을 보관하세요. 저장을 위해 새 API 호출을 하지 마세요.");
        }
    }
    private static String text(Object value){return value instanceof String s ? s : null;}
}
