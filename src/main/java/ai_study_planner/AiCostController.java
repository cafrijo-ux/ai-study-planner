package ai_study_planner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class AiCostController {
    @Autowired(required=false) AiCostProtection protection;
    @GetMapping("/ai-generation-policy") public Map<String,Object> policy(){return protection==null?Map.of("enabled",false,"schemaVerified",false,"version",1):protection.capabilities();}
    @GetMapping("/ai-generation-requests") public Map<String,Object> lookup(@RequestParam(name="feature")String f,@RequestParam(name="requestKey")String k){if(protection==null)throw new AiCostFailure(503,"AI_PROTECTION_DISABLED","새 비용 보호 원장은 비활성/미적용 상태입니다. 저장 이력을 확인하고 재호출하지 마세요.");return protection.lookup(f,GoalSelection.requestedId(),k);}
    static String key(){var a=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();return a instanceof org.springframework.web.context.request.ServletRequestAttributes r?r.getRequest().getHeader("Idempotency-Key"):null;}
}
