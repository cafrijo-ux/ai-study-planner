package ai_study_planner;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 학습 분석 API.
 * - /analysis-summary               : 기존 화면용 전체 누적 분석
 * - /analysis-summary?scope=current : 현재 목표에 연결된 공부 기록만 분석
 *
 * 현재 프로젝트의 단일 사용자(user_id=1) 데모 구조를 따릅니다.
 * 읽기 전용이며 DB의 공부 기록이나 목표를 수정하지 않습니다.
 */
@RestController
public class StudyAnalysisController {

    private static final long DEMO_USER_ID = 1L;

    private final StudyLogRepository studyLogRepository;
    private final GoalRepository goalRepository;
    private final SubjectRepository subjectRepository;

    public StudyAnalysisController(StudyLogRepository studyLogRepository,
                                   GoalRepository goalRepository,
                                   SubjectRepository subjectRepository) {
        this.studyLogRepository = studyLogRepository;
        this.goalRepository = goalRepository;
        this.subjectRepository = subjectRepository;
    }

    @GetMapping("/analysis-summary")
    public Map<String, Object> getAnalysisSummary(
            @RequestParam(name = "scope", defaultValue = "all") String scope) {

        // 주소 복사 시 포함될 수 있는 앞뒤 공백과 대소문자 차이를 허용합니다.
        String normalizedScope = scope == null ? "all" : scope.strip().toLowerCase(Locale.ROOT);
        boolean currentOnly = "current".equals(normalizedScope) || GoalSelection.requestedId() != null;
        boolean all = "all".equals(normalizedScope);
        Map<String, Object> result = new HashMap<>();

        if (!currentOnly && !all) {
            // 전달된 값을 표시해 주소·파라미터 문제를 쉽게 진단할 수 있도록 합니다.
            result.put("message", "scope 값은 all 또는 current만 사용할 수 있습니다.");
            result.put("receivedScope", scope);
            return result;
        }

        // 다른 사용자의 공부 기록이 합산되지 않도록 데모 사용자만 조회합니다.
        List<StudyLog> userLogs = studyLogRepository.findAll().stream()
                .filter(log -> Objects.equals(log.getUserId(), DEMO_USER_ID))
                .toList();

        // findAll()의 반환 순서에 의존하지 않고 ID가 가장 큰 현재 목표를 선택합니다.
        Goal currentGoal = GoalSelection.goals(goalRepository.findAll()).stream()
                .filter(goal -> Objects.equals(goal.getUserId(), DEMO_USER_ID))
                .filter(goal -> goal.getGoalId() != null)
                .max(Comparator.comparing(Goal::getGoalId))
                .orElse(null);

        Set<Long> currentSubjectIds = new HashSet<>();
        if (currentGoal != null) {
            for (Subject subject : subjectRepository.findAll()) {
                if (Objects.equals(subject.getGoalId(), currentGoal.getGoalId())
                        && subject.getSubjectId() != null) {
                    currentSubjectIds.add(subject.getSubjectId());
                }
            }
        }

        List<StudyLog> currentLogs = userLogs.stream()
                .filter(log -> currentSubjectIds.contains(log.getSubjectId()))
                .toList();
        List<StudyLog> selectedLogs = currentOnly ? currentLogs : userLogs;

        result.put("scope", currentOnly ? "CURRENT_GOAL" : "ALL");
        result.put("goalId", currentGoal == null ? null : currentGoal.getGoalId());
        result.put("goalName", currentGoal == null ? null : currentGoal.getGoalName());
        result.put("allTimeRecordCount", userLogs.size());
        result.put("allTimeTotalStudyMinutes", sumStudyMinutes(userLogs));
        result.put("currentGoalRecordCount", currentLogs.size());
        result.put("currentGoalTotalStudyMinutes", sumStudyMinutes(currentLogs));

        int totalStudyMinutes = 0;
        int totalUnderstanding = 0;
        int totalFocus = 0;
        int understandingCount = 0;
        int focusCount = 0;
        int totalCorrect = 0;
        int totalWrong = 0;

        for (StudyLog log : selectedLogs) {
            totalStudyMinutes += nonnegative(log.getStudyMinutes());
            if (validScore(log.getUnderstandingScore())) {
                totalUnderstanding += log.getUnderstandingScore();
                understandingCount++;
            }
            if (validScore(log.getFocusScore())) {
                totalFocus += log.getFocusScore();
                focusCount++;
            }
            totalCorrect += nonnegative(log.getCorrectCount());
            totalWrong += nonnegative(log.getWrongCount());
        }

        Double averageUnderstanding = understandingCount == 0
                ? null : (double) totalUnderstanding / understandingCount;
        Double averageFocus = focusCount == 0
                ? null : (double) totalFocus / focusCount;

        // 기존 대시보드가 사용하는 필드 이름을 유지합니다.
        result.put("recordCount", selectedLogs.size());
        result.put("totalStudyMinutes", totalStudyMinutes);
        result.put("averageUnderstanding", averageUnderstanding);
        result.put("averageFocus", averageFocus);
        result.put("totalCorrect", totalCorrect);
        result.put("totalWrong", totalWrong);

        if (currentOnly && currentGoal == null) {
            result.put("message", "현재 학습 목표가 없습니다. 먼저 학습 목표를 등록하세요.");
            result.put("mainMessage", "현재 학습 목표가 없어 분석할 수 없습니다.");
        } else if (selectedLogs.isEmpty()) {
            result.put("message", currentOnly
                    ? "현재 학습 목표에 공부 기록이 없습니다."
                    : "아직 공부 기록이 없습니다.");
            result.put("mainMessage", "공부 기록을 입력하면 학습 분석 결과가 표시됩니다.");
        } else if (selectedLogs.size() < 3) {
            result.put("mainMessage",
                    "현재 학습 기록이 " + selectedLogs.size() +
                            "건으로 부족하여 학습 흐름을 정확하게 판단하기 어렵습니다. " +
                            "공부 기록을 더 입력해 주세요.");
        } else if (understandingCount == 0 && focusCount == 0
                && totalCorrect == 0 && totalWrong == 0) {
            result.put("mainMessage",
                    "이해도·집중도·문제풀이 측정 기록이 없어 학습 흐름을 판단할 수 없습니다. "
                            + "다음 공부 기록에 측정 항목을 입력해 주세요.");
        } else if (understandingCount > 0 && averageUnderstanding < 3) {
            result.put("mainMessage", "이해도가 낮아 개념 복습이 필요합니다.");
        } else if (focusCount > 0 && averageFocus < 3) {
            result.put("mainMessage", "집중도가 낮아 학습 시간대 조정이 필요합니다.");
        } else if (totalWrong > totalCorrect) {
            result.put("mainMessage", "오답 수가 많아 오답 정리 중심의 학습이 필요합니다.");
        } else {
            result.put("mainMessage", "현재 학습 흐름이 양호합니다.");
        }

        return result;
    }

    // 쿼리 파라미터 입력 문제와 관계없이 현재 목표만 조회할 수 있는 별도 주소입니다.
    @GetMapping("/analysis-summary/current")
    public Map<String, Object> getCurrentAnalysisSummary() {
        return getAnalysisSummary("current");
    }

    private int sumStudyMinutes(List<StudyLog> logs) {
        int total = 0;
        for (StudyLog log : logs) total += nonnegative(log.getStudyMinutes());
        return total;
    }

    private int nonnegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
    private boolean validScore(Integer value) { return value != null && value >= 1 && value <= 5; }
}
