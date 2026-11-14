package ai_study_planner;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RestController
public class WeakSubjectController {

    // 현재 프로젝트는 로그인 전의 1인 시연용 구조입니다.
    private static final long DEMO_USER_ID = 1L;

    private final StudyLogRepository studyLogRepository;
    private final SubjectRepository subjectRepository;
    private final GoalRepository goalRepository;

    public WeakSubjectController(
            StudyLogRepository studyLogRepository,
            SubjectRepository subjectRepository,
            GoalRepository goalRepository
    ) {
        this.studyLogRepository = studyLogRepository;
        this.subjectRepository = subjectRepository;
        this.goalRepository = goalRepository;
    }

    @GetMapping("/weak-subject")
    public Map<String, Object> getWeakSubject() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "취약 과목 추천");
        result.put("scope", "CURRENT_GOAL");

        // findAll()의 순서는 보장되지 않으므로 목표 ID가 가장 큰 것을 현재 목표로 사용합니다.
        Goal currentGoal = GoalSelection.goals(goalRepository.findAll()).stream()
                .filter(goal -> Objects.equals(goal.getUserId(), DEMO_USER_ID))
                .filter(goal -> goal.getGoalId() != null)
                .max(Comparator.comparing(Goal::getGoalId))
                .orElse(null);

        if (currentGoal == null) {
            result.put("message", "현재 학습 목표가 없습니다. 먼저 학습 목표를 등록하세요.");
            result.put("analyzedSubjectCount", 0);
            return result;
        }

        Long goalId = currentGoal.getGoalId();
        result.put("goalId", goalId);
        result.put("goalName", currentGoal.getGoalName());

        // 같은 이름의 '운영체제'라도 이전 목표의 과목 ID는 분석하지 않습니다.
        Map<Long, Subject> currentSubjects = new LinkedHashMap<>();
        for (Subject subject : subjectRepository.findAll()) {
            if (Objects.equals(subject.getGoalId(), goalId)
                    && Objects.equals(subject.getUserId(), DEMO_USER_ID)
                    && subject.getSubjectId() != null) {
                currentSubjects.put(subject.getSubjectId(), subject);
            }
        }
        result.put("currentGoalSubjectCount", currentSubjects.size());

        if (currentSubjects.isEmpty()) {
            result.put("message", "현재 목표에 등록된 과목이 없습니다. 먼저 과목을 추가하세요.");
            result.put("analyzedSubjectCount", 0);
            return result;
        }

        // 현재 목표의 해당 사용자 기록만 모읍니다. 기록이 없는 과목을 0점으로 평가하지 않습니다.
        Map<Long, List<StudyLog>> groupedLogs = new LinkedHashMap<>();
        for (StudyLog log : studyLogRepository.findAll()) {
            if (Objects.equals(log.getUserId(), DEMO_USER_ID)
                    && currentSubjects.containsKey(log.getSubjectId())) {
                groupedLogs.computeIfAbsent(log.getSubjectId(), ignored -> new ArrayList<>()).add(log);
            }
        }
        result.put("analyzedSubjectCount", groupedLogs.size());
        result.put("subjectsWithoutLogs", currentSubjects.size() - groupedLogs.size());

        if (groupedLogs.isEmpty()) {
            result.put("message", "현재 목표의 공부 기록이 없어 취약 과목을 판단할 수 없습니다.");
            return result;
        }

        Long weakestSubjectId = null;
        double lowestScore = Double.MAX_VALUE;
        double selectedAverageUnderstanding = 0;
        double selectedAverageFocus = 0;
        double selectedCorrectRate = 0;
        int selectedTotalStudyMinutes = 0;
        int selectedRecordCount = 0;
        boolean selectedHasUnderstanding = false;
        boolean selectedHasFocus = false;
        boolean selectedHasQuestions = false;

        // 기존의 취약 점수 계산식은 유지합니다. 단, 측정되지 않은 지표를 0점으로 간주하지 않습니다.
        for (Map.Entry<Long, List<StudyLog>> entry : groupedLogs.entrySet()) {
            List<StudyLog> subjectLogs = entry.getValue();
            int totalUnderstanding = 0;
            int understandingCount = 0;
            int totalFocus = 0;
            int focusCount = 0;
            int totalCorrect = 0;
            int totalWrong = 0;
            int totalStudyMinutes = 0;

            for (StudyLog log : subjectLogs) {
                if (log.getUnderstandingScore() != null) {
                    totalUnderstanding += log.getUnderstandingScore();
                    understandingCount++;
                }
                if (log.getFocusScore() != null) {
                    totalFocus += log.getFocusScore();
                    focusCount++;
                }
                if (log.getCorrectCount() != null) {
                    totalCorrect += Math.max(0, log.getCorrectCount());
                }
                if (log.getWrongCount() != null) {
                    totalWrong += Math.max(0, log.getWrongCount());
                }
                if (log.getStudyMinutes() != null) {
                    totalStudyMinutes += Math.max(0, log.getStudyMinutes());
                }
            }

            double averageUnderstanding = understandingCount > 0
                    ? (double) totalUnderstanding / understandingCount : 0;
            double averageFocus = focusCount > 0
                    ? (double) totalFocus / focusCount : 0;
            int totalQuestions = totalCorrect + totalWrong;
            double correctRate = totalQuestions > 0
                    ? ((double) totalCorrect / totalQuestions) * 100 : 0;

            // 관측되지 않은 이해도/집중도/정답률은 중립값으로 순위를 계산합니다.
            // 응답의 실제 수치는 아래에서 "자료 없음"에 해당하면 null로 표시합니다.
            double weaknessScore = (understandingCount > 0 ? averageUnderstanding : 3.0) * 20
                    + (focusCount > 0 ? averageFocus : 3.0) * 15
                    + (totalQuestions > 0 ? correctRate : 70.0);

            if (weaknessScore < lowestScore) {
                lowestScore = weaknessScore;
                weakestSubjectId = entry.getKey();
                selectedAverageUnderstanding = averageUnderstanding;
                selectedAverageFocus = averageFocus;
                selectedCorrectRate = correctRate;
                selectedTotalStudyMinutes = totalStudyMinutes;
                selectedRecordCount = subjectLogs.size();
                selectedHasUnderstanding = understandingCount > 0;
                selectedHasFocus = focusCount > 0;
                selectedHasQuestions = totalQuestions > 0;
            }
        }

        Subject weakestSubject = currentSubjects.get(weakestSubjectId);
        result.put("subjectId", weakestSubjectId);
        result.put("weakSubjectName", weakestSubject.getSubjectName());
        result.put("difficulty", weakestSubject.getDifficulty());
        result.put("priorityLevel", weakestSubject.getPriorityLevel());
        result.put("currentConfidence", weakestSubject.getCurrentConfidence());
        result.put("recordCount", selectedRecordCount);
        result.put("totalStudyMinutes", selectedTotalStudyMinutes);
        result.put("averageUnderstanding", selectedHasUnderstanding ? selectedAverageUnderstanding : null);
        result.put("averageFocus", selectedHasFocus ? selectedAverageFocus : null);
        result.put("correctRate", selectedHasQuestions ? selectedCorrectRate : null);
        result.put("selectionNote", groupedLogs.size() == 1
                ? "현재 목표에서 공부 기록이 있는 과목은 1개이므로 과목 간 비교 결과가 아닙니다."
                : "현재 목표에서 기록이 있는 " + groupedLogs.size() + "개 과목을 비교했습니다.");

        if (selectedRecordCount < 3) {
            result.put("reason",
                    "현재 학습 기록이 " + selectedRecordCount
                            + "건으로 부족하여 학습 추세와 취약도를 확정하기 어렵습니다."
                            + (groupedLogs.size() == 1
                            ? " 다른 과목과의 비교도 아직 불가능합니다."
                            : ""));

            result.put("strategy",
                    "현재 기록에서 어려웠던 개념을 우선 복습하고, "
                            + "추가 학습을 진행하면서 이해도와 집중도를 기록하는 것이 좋습니다.");

            result.put("todayPlan",
                    "어려웠던 개념 복습 20분 → 쉬운 확인 문제 10분 → 이해도 기록");

        } else if (selectedHasUnderstanding && selectedAverageUnderstanding < 3) {
            result.put("reason",
                    "현재 기록에서 평균 이해도가 낮게 나타났습니다.");

            result.put("strategy",
                    "개념 복습을 먼저 진행하고 쉬운 예제로 이해 여부를 확인하세요.");

            result.put("todayPlan",
                    "개념 복습 30분 → 쉬운 문제풀이 20분 → 핵심 개념 정리 20분");

        } else if (selectedHasFocus && selectedAverageFocus < 3) {
            result.put("reason",
                    "현재 기록에서 평균 집중도가 낮게 나타났습니다.");

            result.put("strategy",
                    "짧은 시간 단위로 학습하고 집중하기 좋은 환경을 찾아보세요.");

            result.put("todayPlan",
                    "25분 집중 학습 → 5분 휴식 → 핵심 개념 복습");

        } else if (selectedHasQuestions && selectedCorrectRate < 60) {
            result.put("reason",
                    "기록된 문제풀이의 정답률이 낮아 관련 개념 복습이 필요합니다.");

            result.put("strategy",
                    "틀린 문제의 원인을 분류하고 관련 개념을 복습하세요.");

            result.put("todayPlan",
                    "오답 원인 정리 20분 → 개념 복습 30분 → 유사 문제풀이 30분");

        } else if (!selectedHasQuestions) {
            result.put("reason",
                    "문제풀이 기록이 없어 정답률을 평가할 수 없습니다.");

            result.put("strategy",
                    "현재 학습 흐름을 유지하면서 간단한 확인 문제를 풀고 결과를 기록하세요.");

            result.put("todayPlan",
                    "기존 학습 계획 수행 → 확인 문제풀이 20분 → 이해도 기록");

        } else {
            result.put("reason",
                    groupedLogs.size() == 1
                            ? "분석 가능한 과목이 하나이므로 과목 간 비교는 어렵습니다."
                            : "현재 기록에서 상대적으로 보완이 필요한 과목입니다.");

            result.put("strategy",
                    "현재 학습 흐름을 유지하면서 취약 개념을 주기적으로 복습하세요.");

            result.put("todayPlan",
                    "기존 학습 계획 수행 → 핵심 개념 복습 → 다음 단원 예습");
        }
        return result;
    }
}
