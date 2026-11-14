package ai_study_planner;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class AiCoachingController {

    private final StudyLogRepository studyLogRepository;
    private final GoalRepository goalRepository;
    private final SubjectRepository subjectRepository;

    public AiCoachingController(
            StudyLogRepository studyLogRepository,
            GoalRepository goalRepository,
            SubjectRepository subjectRepository
    ) {
        this.studyLogRepository = studyLogRepository;
        this.goalRepository = goalRepository;
        this.subjectRepository = subjectRepository;
    }

    @GetMapping("/ai-coaching")
    public Map<String, Object> getAiCoaching() {
        List<StudyLog> logs = GoalSelection.logs(studyLogRepository.findAll(), subjectRepository.findAll());
        List<Goal> goals = GoalSelection.goals(goalRepository.findAll());
        List<Subject> subjects = GoalSelection.subjects(subjectRepository.findAll());

        Map<String, Object> result = new HashMap<>();

        Goal latestGoal = null;
        Subject latestSubject = null;

        if (!goals.isEmpty()) {
            latestGoal = goals.get(goals.size() - 1);
        }

        if (!subjects.isEmpty()) {
            latestSubject = subjects.get(subjects.size() - 1);
        }

        result.put("coachingTitle", "오늘의 AI 학습 코칭");

        if (latestGoal != null) {
            result.put("goalName", latestGoal.getGoalName());
            result.put("goalType", latestGoal.getGoalType());
            result.put("examDate", latestGoal.getExamDate());
            result.put("targetScore", latestGoal.getTargetScore());
            result.put("currentLevel", latestGoal.getCurrentLevel());
            result.put("weakPoint", latestGoal.getWeakPoint());
            result.put("dailyStudyTime", latestGoal.getDailyStudyTime());
            result.put("preferredStudyMethod", latestGoal.getPreferredStudyMethod());
        }

        if (latestSubject != null) {
            result.put("subjectName", latestSubject.getSubjectName());
            result.put("difficulty", latestSubject.getDifficulty());
            result.put("priorityLevel", latestSubject.getPriorityLevel());
            result.put("currentConfidence", latestSubject.getCurrentConfidence());
        }

        if (logs.isEmpty()) {
            result.put("diagnosis", "아직 공부 기록이 없어 학습 목표 정보를 기준으로 초기 학습 계획을 생성합니다.");
            result.put("reason", "누적 학습 데이터가 부족하므로 목표, 현재 수준, 과목 난이도, 중요도, 자신감을 우선 반영합니다.");
            result.put("recommendation", "어렵고 중요도가 높은 과목부터 개념 학습을 시작하고, 짧은 문제풀이를 통해 이해도를 확인하는 방식이 좋습니다.");
            result.put("todayPlan", "개념 학습 40분 → 쉬운 문제풀이 30분 → 틀린 개념 정리 20분");
            return result;
        }

        int totalStudyMinutes = 0;
        int totalUnderstanding = 0;
        int totalFocus = 0;
        int totalCorrect = 0;
        int totalWrong = 0;

        String lastWrongReason = "";
        String lastDifficultTopic = "";

        for (StudyLog log : logs) {

            if (log.getStudyMinutes() != null) {
                totalStudyMinutes += log.getStudyMinutes();
            }

            if (log.getUnderstandingScore() != null) {
                totalUnderstanding += log.getUnderstandingScore();
            }

            if (log.getFocusScore() != null) {
                totalFocus += log.getFocusScore();
            }

            if (log.getCorrectCount() != null) {
                totalCorrect += log.getCorrectCount();
            }

            if (log.getWrongCount() != null) {
                totalWrong += log.getWrongCount();
            }

            if (log.getWrongReason() != null) {
                lastWrongReason = log.getWrongReason();
            }

            if (log.getDifficultTopic() != null) {
                lastDifficultTopic = log.getDifficultTopic();
            }
        }

        double averageUnderstanding = (double) totalUnderstanding / logs.size();
        double averageFocus = (double) totalFocus / logs.size();

        result.put("recordCount", logs.size());
        result.put("totalStudyMinutes", totalStudyMinutes);
        result.put("averageUnderstanding", averageUnderstanding);
        result.put("averageFocus", averageFocus);
        result.put("totalCorrect", totalCorrect);
        result.put("totalWrong", totalWrong);
        result.put("wrongReason", lastWrongReason);
        result.put("difficultTopic", lastDifficultTopic);

        if (averageUnderstanding < 3) {
            result.put("diagnosis", "현재 이해도가 낮아 개념 복습이 우선적으로 필요합니다.");
            result.put("reason", "최근 학습 기록에서 평균 이해도 점수가 낮게 나타났습니다. 특히 학습 목표에서 어렵거나 자신감이 낮은 과목은 개념부터 다시 정리해야 합니다.");
            result.put("recommendation", "어려웠던 개념을 짧게 정리한 뒤, 쉬운 예제 문제부터 다시 풀어보는 방식이 좋습니다.");
            result.put("todayPlan", "개념 복습 30분 → 쉬운 문제풀이 30분 → 오답 정리 20분");
        } else if (averageFocus < 3) {
            result.put("diagnosis", "집중도가 낮아 학습 시간대나 학습 환경을 조정할 필요가 있습니다.");
            result.put("reason", "공부 시간은 확보했지만 집중도가 낮으면 학습 효율이 떨어질 수 있습니다.");
            result.put("recommendation", "짧은 시간 단위로 공부를 나누고, 집중이 잘 되는 시간대에 중요도가 높은 과목을 배치하세요.");
            result.put("todayPlan", "25분 집중 학습 → 5분 휴식 → 핵심 개념 복습 → 짧은 문제풀이");
        } else if (totalWrong > totalCorrect) {
            result.put("diagnosis", "오답 수가 많아 오답 원인 분석이 필요합니다.");
            result.put("reason", "정답 수보다 오답 수가 많기 때문에 같은 실수를 반복할 가능성이 있습니다.");
            result.put("recommendation", "틀린 문제를 다시 풀기 전에 왜 틀렸는지 원인을 먼저 분류하세요.");
            result.put("todayPlan", "오답 원인 정리 20분 → 관련 개념 복습 30분 → 비슷한 문제 재풀이 30분");
        } else {
            result.put("diagnosis", "현재 학습 흐름은 비교적 양호합니다.");
            result.put("reason", "이해도와 집중도가 크게 낮지 않고, 오답 수도 관리 가능한 수준입니다.");
            result.put("recommendation", "현재 학습 방식을 유지하되, 시험 전에는 오답 정리와 복습 비중을 늘리는 것이 좋습니다.");
            result.put("todayPlan", "기존 계획 수행 → 오답 복습 → 다음 단원 예습");
        }

        return result;
    }
}
