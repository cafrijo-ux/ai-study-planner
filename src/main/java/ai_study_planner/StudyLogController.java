package ai_study_planner;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class StudyLogController {

    private final StudyLogRepository studyLogRepository;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private StudyLogIdempotencyService idempotency;

    public StudyLogController(StudyLogRepository studyLogRepository) {
        this.studyLogRepository = studyLogRepository;
    }

    @GetMapping("/study-logs")
    public List<StudyLog> getStudyLogs() {
        return studyLogRepository.findAll().stream().filter(row -> java.util.Objects.equals(row.getUserId(), 1L)).toList();
    }

    @PostMapping("/study-logs")
    public ResponseEntity<?> saveStudyLog(@RequestBody StudyLog studyLog) {
        String error = StudyLogWriteValidation.error(studyLog);
        if (error != null) return ResponseEntity.badRequest().body(Map.of("message", error));
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        String key=attributes instanceof org.springframework.web.context.request.ServletRequestAttributes a ? a.getRequest().getHeader("Idempotency-Key") : null;
        if(idempotency!=null && (key!=null || idempotency.requireKey())) {
            var result=idempotency.save(actor(),GoalSelection.requestedId(),key,studyLog);
            return ResponseEntity.ok().header("Idempotency-Status",result.replayed()?"REPLAYED":"CREATED").header("Idempotency-Mode","PERSISTENT").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(result.json());
        }
        StudyLog savedLog = studyLogRepository.save(studyLog);
        return ResponseEntity.ok().header("Idempotency-Mode",idempotency==null?"DISABLED":"BYPASSED_MISSING_KEY").body(savedLog);
    }
    @GetMapping("/study-log-idempotency")
    public Map<String,Object> idempotencyCapabilities(){return Map.of("enabled",idempotency!=null,"requireKey",idempotency!=null&&idempotency.requireKey(),"schemaVerified",false,"version",1,"lookupPath","/study-log-requests");}
    @GetMapping("/study-log-requests")
    public ResponseEntity<?> requestResult(@RequestParam(name="requestKey") String requestKey){
        if(idempotency==null)throw new StudyLogIdempotencyFailure(503,"IDEMPOTENCY_DISABLED","서버 영속 멱등성이 비활성 상태입니다. 기존 공부 기록을 확인하세요.");
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(idempotency.lookup(actor(),GoalSelection.requestedId(),requestKey));
    }
    private long actor(){var a=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();if(a==null||!a.isAuthenticated()||a.getAuthorities().stream().noneMatch(x->x.getAuthority().equals("ROLE_DEMO_OWNER")))throw new StudyLogIdempotencyFailure(401,"AUTH_REQUIRED","로그인이 필요합니다.");return 1L;}
}
