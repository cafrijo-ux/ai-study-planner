package ai_study_planner;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class InitialCoachingController {

    private final GoalRepository goalRepository;
    private final SubjectRepository subjectRepository;
    private final StudyLogRepository studyLogRepository;

    public InitialCoachingController(
            GoalRepository goalRepository,
            SubjectRepository subjectRepository,
            StudyLogRepository studyLogRepository
    ) {
        this.goalRepository = goalRepository;
        this.subjectRepository = subjectRepository;
        this.studyLogRepository = studyLogRepository;
    }

    @GetMapping("/initial-coaching")
    public Map<String, Object> getInitialCoaching() {
        Map<String, Object> result = new HashMap<>();

        List<Goal> goals = GoalSelection.goals(goalRepository.findAll());
        List<Subject> subjects = GoalSelection.subjects(subjectRepository.findAll());
        List<StudyLog> logs = GoalSelection.logs(studyLogRepository.findAll(), subjectRepository.findAll());

        if (goals.isEmpty()) {
            result.put("message", "학습 목표 정보가 없어 초기 코칭을 생성할 수 없습니다.");
            result.put("recommendation", "먼저 학습 목표 설정 화면에서 목표와 과목 정보를 입력하세요.");
            return result;
        }

        Goal latestGoal = goals.get(goals.size() - 1);

        result.put("title", "초기 학습 코칭");
        result.put("goalName", latestGoal.getGoalName());
        result.put("currentLevel", latestGoal.getCurrentLevel());
        result.put("weakPoint", latestGoal.getWeakPoint());
        result.put("dailyStudyTime", latestGoal.getDailyStudyTime());

        if (subjects.isEmpty()) {
            result.put("subjectName", "미등록");
            result.put("coachingType", "목표 기반 기본 코칭");
        } else {
            Subject latestSubject = subjects.get(subjects.size() - 1);
            result.put("subjectName", latestSubject.getSubjectName());
            result.put("difficulty", latestSubject.getDifficulty());
            result.put("priorityLevel", latestSubject.getPriorityLevel());
            result.put("currentConfidence", latestSubject.getCurrentConfidence());
            result.put("coachingType", "목표 + 과목 정보 기반 초기 코칭");
        }

        if (logs.isEmpty()) {
            result.put("dataStatus", "공부 기록 부족");
            result.put("analysisAvailable", false);
            result.put("mainAdvice",
                    "아직 공부 기록이 부족하므로 정확한 분석보다는 목표와 현재 수준을 기준으로 기본 학습 계획을 제공합니다.");
        } else {
            result.put("dataStatus", "공부 기록 존재");
            result.put("analysisAvailable", true);
            result.put("mainAdvice",
                    "공부 기록이 존재하므로 학습 분석 결과와 함께 개인화된 코칭을 제공할 수 있습니다.");
        }

        StringBuilder plan = new StringBuilder();

        plan.append("[초기 학습 방향]\n");

        String level = latestGoal.getCurrentLevel() != null ? latestGoal.getCurrentLevel() : "";
        String weakPoint = latestGoal.getWeakPoint() != null ? latestGoal.getWeakPoint() : "";

        if (level.contains("하") || level.contains("낮")) {
            plan.append("현재 수준이 낮은 편이므로 개념 이해를 우선해야 합니다.\n");
            plan.append("처음부터 어려운 문제를 풀기보다 기본 개념 정리와 쉬운 예제 풀이를 먼저 진행하세요.\n\n");
        } else if (level.contains("중") || level.contains("보통")) {
            plan.append("기본 개념은 어느 정도 알고 있는 상태로 보고, 개념 복습과 문제풀이를 함께 진행하는 것이 좋습니다.\n\n");
        } else {
            plan.append("현재 수준이 높은 편이라면 기본 복습 후 응용 문제와 오답 분석 중심으로 공부하는 것이 좋습니다.\n\n");
        }

        plan.append("[추천 공부 순서]\n");
        plan.append("1. 오늘 공부할 단원 정하기\n");
        plan.append("2. 핵심 개념 20~30분 정리\n");
        plan.append("3. 쉬운 문제 풀이\n");
        plan.append("4. 틀린 문제의 오답 원인 기록\n");
        plan.append("5. 공부 기록 입력 후 AI 코칭 확인\n\n");

        plan.append("[주의할 점]\n");
        plan.append("취약 부분: ").append(weakPoint.isEmpty() ? "아직 구체적으로 입력되지 않았습니다." : weakPoint).append("\n");
        plan.append("초기에는 많은 양을 공부하기보다 꾸준히 기록을 쌓는 것이 중요합니다.");

        result.put("initialPlan", plan.toString());

        return result;
    }
}
