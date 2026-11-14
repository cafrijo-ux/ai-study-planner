package ai_study_planner;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class GoalRegistrationService {
    private final GoalRepository goals;
    private final SubjectRepository subjects;
    private final JdbcTemplate jdbc;

    public GoalRegistrationService(GoalRepository goals, SubjectRepository subjects, JdbcTemplate jdbc) {
        this.goals = goals; this.subjects = subjects; this.jdbc = jdbc;
    }

    public record Registration(Goal goal, Subject subject) {}

    // 예외를 삼키지 않습니다. flush/commit 실패도 컨트롤러에 도달하기 전에 전체 롤백됩니다.
    @Transactional
    public Map<String, Object> register(Registration request) {
        if (request == null || request.goal() == null || request.subject() == null) bad("목표와 필수 과목을 함께 입력하세요.");
        Goal goal = request.goal(); Subject subject = request.subject();
        validateGoal(goal); validateSubject(subject);
        if (subject.getGoalId() != null) bad("새 목표 등록에는 기존 목표 번호를 지정할 수 없습니다.");
        lockUser();
        // 같은 사용자의 동시 요청을 직렬화하고 전체 입력이 같은 완성된 등록을 재사용합니다.
        // DB 데이터로 확인하므로 응답 유실 후 재시도나 서버 재시작에도 중복을 만들지 않습니다.
        for (Goal existing : goals.findByUserId(1L)) {
            if (!sameGoal(goal, existing)) continue;
            List<Subject> existingSubjects = subjects.findByUserIdAndGoalId(1L, existing.getGoalId());
            for (Subject existingSubject : existingSubjects)
                if (sameSubject(subject, existingSubject)) return result(existing, existingSubject, true);
        }
        goal.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")));
        Goal saved = goals.saveAndFlush(goal);
        subject.setUserId(1L); subject.setGoalId(saved.getGoalId());
        subject.setCreatedAt(goal.getCreatedAt());
        Subject savedSubject = subjects.saveAndFlush(subject);
        return result(saved, savedSubject, false);
    }

    @Transactional
    public Map<String, Object> addSubject(long goalId, Subject subject) {
        validateSubject(subject);
        if (subject.getGoalId() != null && subject.getGoalId() != goalId) bad("목표 번호가 일치하지 않습니다.");
        lockUser();
        Goal goal = goals.findById(goalId).filter(g -> Objects.equals(g.getUserId(), 1L))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "선택한 목표를 찾을 수 없습니다."));
        for (Subject existing : subjects.findByUserIdAndGoalId(1L, goalId)) {
            if (sameSubject(subject, existing)) return result(goal, existing, true);
            if (Objects.equals(existing.getSubjectName(), subject.getSubjectName()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 이름의 과목이 이미 있습니다. 기존 과목 설정을 확인하세요.");
        }
        subject.setUserId(1L); subject.setGoalId(goalId);
        subject.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")));
        return result(goal, subjects.saveAndFlush(subject), false);
    }

    private void lockUser() {
        if (jdbc.queryForList("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE", Long.class, 1L).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "등록할 사용자 정보를 찾을 수 없습니다.");
    }
    private static Map<String, Object> result(Goal goal, Subject subject, boolean duplicate) {
        return Map.of("saved", true, "goal", goal, "subject", subject, "duplicate", duplicate);
    }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static void required(String text, int max, String label) {
        if (text == null || text.isBlank() || text.length() > max) bad(label + "을(를) 1~" + max + "자 이내로 입력하세요.");
    }
    private static void validateGoal(Goal g) {
        if (g.getGoalId() != null || g.getCreatedAt() != null) bad("새 목표의 번호와 생성 시각은 서버가 설정합니다.");
        if (!Objects.equals(g.getUserId(), 1L)) bad("사용자 정보가 올바르지 않습니다.");
        required(g.getGoalName(), 100, "목표명"); required(g.getGoalType(), 50, "목표 유형");
        required(g.getTargetScore(), 50, "목표 점수"); required(g.getCurrentLevel(), 50, "현재 수준");
        required(g.getWeakPoint(), 100, "취약 부분"); required(g.getPreferredStudyMethod(), 100, "선호 학습 방법");
        if (g.getExamDate() == null) bad("목표 마감일을 입력하세요.");
        if (g.getDailyStudyTime() == null || g.getDailyStudyTime() < 1 || g.getDailyStudyTime() > 1440) bad("하루 공부 시간은 1~1440분이어야 합니다.");
    }
    private static void validateSubject(Subject s) {
        if (s == null) bad("필수 과목을 입력하세요.");
        if (s.getSubjectId() != null || s.getCreatedAt() != null) bad("새 과목의 번호와 생성 시각은 서버가 설정합니다.");
        if (s.getUserId() != null && !Objects.equals(s.getUserId(), 1L)) bad("사용자 정보가 올바르지 않습니다.");
        required(s.getSubjectName(), 100, "과목명"); required(s.getDifficulty(), 50, "난이도");
        required(s.getPriorityLevel(), 50, "중요도"); required(s.getCurrentConfidence(), 50, "자신감");
        if (!List.of("쉬움", "보통", "어려움").contains(s.getDifficulty())
                || !List.of("낮음", "보통", "높음").contains(s.getPriorityLevel())
                || !List.of("자신 없음", "보통", "자신 있음", "낮음", "높음").contains(s.getCurrentConfidence())) bad("과목 선택값이 올바르지 않습니다.");
    }
    private static boolean sameGoal(Goal a, Goal b) {
        return Objects.equals(a.getGoalName(), b.getGoalName()) && Objects.equals(a.getGoalType(), b.getGoalType())
            && Objects.equals(a.getExamDate(), b.getExamDate()) && Objects.equals(a.getTargetScore(), b.getTargetScore())
            && Objects.equals(a.getCurrentLevel(), b.getCurrentLevel()) && Objects.equals(a.getWeakPoint(), b.getWeakPoint())
            && Objects.equals(a.getDailyStudyTime(), b.getDailyStudyTime()) && Objects.equals(a.getPreferredStudyMethod(), b.getPreferredStudyMethod());
    }
    private static boolean sameSubject(Subject a, Subject b) {
        return Objects.equals(a.getSubjectName(), b.getSubjectName()) && Objects.equals(a.getDifficulty(), b.getDifficulty())
            && Objects.equals(a.getPriorityLevel(), b.getPriorityLevel()) && Objects.equals(a.getCurrentConfidence(), b.getCurrentConfidence());
    }
}
