package ai_study_planner;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
public class GoalRegistrationController {
    private final GoalRegistrationService registration;
    public GoalRegistrationController(GoalRegistrationService registration) { this.registration = registration; }

    @PostMapping("/goal-registrations")
    public Map<String, Object> register(@RequestBody GoalRegistrationService.Registration request) {
        return registration.register(request);
    }
    @PostMapping("/goals/{goalId}/subjects")
    public Map<String, Object> addSubject(@PathVariable("goalId") long goalId, @RequestBody Subject subject) {
        return registration.addSubject(goalId, subject);
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> invalid(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", ex.getReason() == null ? "요청을 확인하세요." : ex.getReason()));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> storageFailure(DataAccessException ex) {
        // DB 오류 메시지(SQL/연결 정보)를 응답에 노출하지 않습니다.
        return ResponseEntity.internalServerError().body(Map.of("message", "등록을 완료하지 못했습니다. 입력 내용을 유지하고 다시 시도하세요."));
    }
}
