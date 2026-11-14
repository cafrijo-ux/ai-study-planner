package ai_study_planner;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class SubjectController {

    private final SubjectRepository subjectRepository;

    public SubjectController(SubjectRepository subjectRepository) {
        this.subjectRepository = subjectRepository;
    }

    @GetMapping("/subjects")
    public List<Subject> getSubjects() {
        return GoalSelection.subjects(subjectRepository.findAll());
    }

    @PostMapping("/subjects")
    public ResponseEntity<?> saveSubject(@RequestBody Subject subject) {
        Map<String, Object> error = new HashMap<>();

        if (subject.getUserId() == null) {
            error.put("message", "사용자 정보가 없습니다.");
            return ResponseEntity.badRequest().body(error);
        }

        if (subject.getGoalId() == null) {
            error.put("message", "연결된 학습 목표 정보가 없습니다.");
            return ResponseEntity.badRequest().body(error);
        }

        if (subject.getSubjectName() == null || subject.getSubjectName().trim().isEmpty()) {
            error.put("message", "과목명을 입력해야 합니다.");
            return ResponseEntity.badRequest().body(error);
        }

        if (subject.getDifficulty() == null || subject.getDifficulty().trim().isEmpty()) {
            error.put("message", "과목 난이도를 선택해야 합니다.");
            return ResponseEntity.badRequest().body(error);
        }

        if (subject.getPriorityLevel() == null || subject.getPriorityLevel().trim().isEmpty()) {
            error.put("message", "우선순위를 선택해야 합니다.");
            return ResponseEntity.badRequest().body(error);
        }

        if (subject.getCurrentConfidence() == null || subject.getCurrentConfidence().trim().isEmpty()) {
            error.put("message", "현재 자신감을 선택해야 합니다.");
            return ResponseEntity.badRequest().body(error);
        }

        Subject savedSubject = subjectRepository.save(subject);
        return ResponseEntity.ok(savedSubject);
    }
}
