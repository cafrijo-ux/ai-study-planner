package ai_study_planner.security;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
@RestControllerAdvice
public class SafeApiErrors {
    @ExceptionHandler(ai_study_planner.AiCostFailure.class) public ResponseEntity<?> aiCost(ai_study_planner.AiCostFailure e){return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage()));}
    @ExceptionHandler(ai_study_planner.StudyLogIdempotencyFailure.class) public ResponseEntity<?> idempotency(ai_study_planner.StudyLogIdempotencyFailure e){return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage()));}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<?> database(DataAccessException e){
        return ResponseEntity.internalServerError().body(Map.of("message","처리를 완료하지 못했습니다. 입력 내용을 유지하고 잠시 후 다시 시도해 주세요."));
    }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> rejected(ResponseStatusException e){
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message","요청 대상 또는 접근 권한을 확인해 주세요."));
    }
}
