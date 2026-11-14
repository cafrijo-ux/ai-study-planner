package ai_study_planner;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
public class AutoRescheduleController {

    private final GoalRepository goalRepository;
    private final StudyLogRepository studyLogRepository;
    private final SubjectRepository subjectRepository;

    public AutoRescheduleController(
            GoalRepository goalRepository,
            StudyLogRepository studyLogRepository,
            SubjectRepository subjectRepository
    ) {
        this.goalRepository = goalRepository;
        this.studyLogRepository = studyLogRepository;
        this.subjectRepository = subjectRepository;
    }

    @GetMapping("/auto-reschedule")
    public Map<String, Object> getAutoReschedule() {
        List<Goal> goals = GoalSelection.goals(goalRepository.findAll());

        Map<String, Object> result = new HashMap<>();

        if (goals.isEmpty()) {
            result.put("message", "목표 정보가 없어 자동 재조정을 할 수 없습니다.");
            return result;
        }

        Goal latestGoal = goals.stream().max(Comparator.comparing(Goal::getGoalId)).orElseThrow();
        var subjectIds = GoalSelection.subjects(subjectRepository.findAll()).stream()
                .filter(s -> Objects.equals(s.getGoalId(), latestGoal.getGoalId()))
                .map(Subject::getSubjectId).collect(Collectors.toSet());
        List<StudyLog> logs = studyLogRepository.findAll().stream()
                .filter(l -> Objects.equals(l.getUserId(), 1L) && subjectIds.contains(l.getSubjectId()))
                .filter(l -> l.getStudyDate() != null).toList();
        if (logs.isEmpty()) {
            result.put("message", "공부 기록이 없어 자동 재조정을 할 수 없습니다.");
            result.put("recommendation", "먼저 공부 기록을 입력하세요.");
            return result;
        }

        StudyLog latestLog = logs.stream().max(Comparator.comparing(StudyLog::getStudyDate)
                .thenComparing(StudyLog::getLogId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow();

        int targetStudyTime = latestGoal.getDailyStudyTime() != null
                ? latestGoal.getDailyStudyTime()
                : 60;

        // Compare the daily target with all records on that day, not just the last entry.
        int actualStudyTime = (int) Math.min(Integer.MAX_VALUE, logs.stream()
                .filter(l -> latestLog.getStudyDate().equals(l.getStudyDate()))
                .mapToLong(l -> l.getStudyMinutes() == null ? 0 : Math.max(0, l.getStudyMinutes()))
                .sum());

        int understandingScore = latestLog.getUnderstandingScore() != null
                ? latestLog.getUnderstandingScore()
                : 0;

        int focusScore = latestLog.getFocusScore() != null
                ? latestLog.getFocusScore()
                : 0;

        String completed = latestLog.getCompleted() != null
                ? latestLog.getCompleted()
                : "미입력";

        boolean timeShortage = actualStudyTime < targetStudyTime * 0.7;
        boolean notCompleted = !"완료".equals(completed);
        boolean lowUnderstanding = understandingScore < 3;
        boolean lowFocus = focusScore < 3;

        result.put("title", "학습 계획 자동 재조정");
        result.put("goalName", latestGoal.getGoalName());
        result.put("targetStudyTime", targetStudyTime);
        result.put("actualStudyTime", actualStudyTime);
        result.put("completed", completed);
        result.put("understandingScore", understandingScore);
        result.put("focusScore", focusScore);

        if (timeShortage || notCompleted || lowUnderstanding || lowFocus) {
            result.put("needReschedule", true);
            result.put("status", "목표 미달로 판단되어 학습 계획을 재조정합니다.");

            StringBuilder reason = new StringBuilder();

            if (timeShortage) {
                reason.append("- 목표 공부 시간보다 실제 공부 시간이 부족합니다.\n");
            }

            if (notCompleted) {
                reason.append("- 계획한 학습을 완료하지 못했습니다.\n");
            }

            if (lowUnderstanding) {
                reason.append("- 이해도가 낮아 개념 복습이 필요합니다.\n");
            }

            if (lowFocus) {
                reason.append("- 집중도가 낮아 학습 시간 조정이 필요합니다.\n");
            }

            result.put("reason", reason.toString());

            if (lowUnderstanding) {
                result.put("rescheduledPlan",
                        "개념 복습 30분 → 쉬운 문제풀이 20분 → 오답 개념 정리 20분");
                result.put("strategy",
                        "이해도가 낮으므로 새로운 내용을 진행하기보다 기존 개념을 다시 정리하는 방식으로 계획을 조정합니다.");
            } else if (lowFocus) {
                result.put("rescheduledPlan",
                        "25분 집중 학습 → 5분 휴식 → 핵심 개념 복습 → 짧은 문제풀이");
                result.put("strategy",
                        "집중도가 낮으므로 긴 시간 공부보다 짧은 집중 학습 방식으로 계획을 조정합니다.");
            } else if (timeShortage) {
                result.put("rescheduledPlan",
                        "핵심 개념 20분 → 필수 문제 20분 → 남은 내용 다음 날로 이동");
                result.put("strategy",
                        "공부 시간이 부족했으므로 학습량을 줄이고 핵심 내용 위주로 계획을 재구성합니다.");
            } else {
                result.put("rescheduledPlan",
                        "미완료 내용 복습 30분 → 오답 정리 20분 → 다음 단원 예습 20분");
                result.put("strategy",
                        "완료하지 못한 내용을 다음 학습 계획에 우선 배치합니다.");
            }

        } else {
            result.put("needReschedule", false);
            result.put("status", "최근 학습 목표를 정상적으로 달성했습니다.");
            result.put("reason", "공부 시간, 완료 여부, 이해도, 집중도가 기준을 만족했습니다.");
            result.put("rescheduledPlan", "기존 학습 계획을 유지합니다.");
            result.put("strategy", "현재 학습 흐름을 유지하되, 오답 복습을 추가하면 좋습니다.");
        }

        return result;
    }
}
