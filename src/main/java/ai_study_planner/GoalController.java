package ai_study_planner;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class GoalController {

    private final GoalRepository goalRepository;

    public GoalController(GoalRepository goalRepository) {
        this.goalRepository = goalRepository;
    }

    @GetMapping("/goals")
    public List<Goal> getGoals() {
        return goalRepository.findAll().stream().filter(row -> java.util.Objects.equals(row.getUserId(), 1L)).toList();
    }

    @PostMapping("/goals")
    public ResponseEntity<?> saveGoal(@RequestBody Goal goal) {
        Map<String, Object> error = new HashMap<>();

        if (goal.getUserId() == null) {
            error.put("message", "사용자 정보가 없습니다.");
            return ResponseEntity.badRequest().body(error);
        }

        if (goal.getGoalName() == null || goal.getGoalName().trim().isEmpty()) {
            error.put("message", "목표명을 입력해야 합니다.");
            return ResponseEntity.badRequest().body(error);
        }

        if (goal.getGoalType() == null || goal.getGoalType().trim().isEmpty()) {
            error.put("message", "목표 유형을 입력해야 합니다.");
            return ResponseEntity.badRequest().body(error);
        }

        if (goal.getExamDate() == null) {
            error.put("message", "시험일 또는 목표 마감일을 입력해야 합니다.");
            return ResponseEntity.badRequest().body(error);
        }

        if (goal.getTargetScore() == null || goal.getTargetScore().trim().isEmpty()) {
            error.put("message", "목표 점수를 입력해야 합니다.");
            return ResponseEntity.badRequest().body(error);
        }

        if (goal.getCurrentLevel() == null || goal.getCurrentLevel().trim().isEmpty()) {
            error.put("message", "현재 수준을 입력해야 합니다.");
            return ResponseEntity.badRequest().body(error);
        }

        if (goal.getWeakPoint() == null || goal.getWeakPoint().trim().isEmpty()) {
            error.put("message", "취약 부분을 입력해야 합니다.");
            return ResponseEntity.badRequest().body(error);
        }

        if (goal.getDailyStudyTime() == null || goal.getDailyStudyTime() <= 0) {
            error.put("message", "하루 공부 가능 시간은 1분 이상이어야 합니다.");
            return ResponseEntity.badRequest().body(error);
        }

        if (goal.getPreferredStudyMethod() == null || goal.getPreferredStudyMethod().trim().isEmpty()) {
            error.put("message", "선호 학습 방법을 입력해야 합니다.");
            return ResponseEntity.badRequest().body(error);
        }

        Goal savedGoal = goalRepository.save(goal);
        return ResponseEntity.ok(savedGoal);
    }
}