package ai_study_planner;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 현재 학습 목표의 과목별 분석 API.
 * 기존 /subject-analysis 응답 구조를 유지해 dashboard.html과 호환됩니다.
 * 로그인 기능이 없는 현재 프로젝트에서는 user_id=1을 사용합니다.
 */
@RestController
public class SubjectAnalysisController {

    private static final long DEMO_USER_ID = 1L;

    private final StudyLogRepository studyLogRepository;
    private final SubjectRepository subjectRepository;
    private final GoalRepository goalRepository;

    public SubjectAnalysisController(
            StudyLogRepository studyLogRepository,
            SubjectRepository subjectRepository,
            GoalRepository goalRepository
    ) {
        this.studyLogRepository = studyLogRepository;
        this.subjectRepository = subjectRepository;
        this.goalRepository = goalRepository;
    }

    @GetMapping("/subject-analysis")
    public Map<String, Object> getSubjectAnalysis() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> subjectAnalysisList = new ArrayList<>();
        result.put("analysisTitle", "과목별 학습 분석");
        result.put("scope", "CURRENT_GOAL");
        result.put("subjectAnalysis", subjectAnalysisList);

        // 전체 목표 중 가장 최신 ID를 가진 '현재 사용자'의 목표만 사용합니다.
        Goal latestGoal = GoalSelection.goals(goalRepository.findAll()).stream()
                .filter(goal -> Objects.equals(goal.getUserId(), DEMO_USER_ID))
                .filter(goal -> goal.getGoalId() != null)
                .max(Comparator.comparing(Goal::getGoalId))
                .orElse(null);

        if (latestGoal == null) {
            result.put("message", "현재 학습 목표가 없습니다. 먼저 목표를 등록해 주세요.");
            return result;
        }

        Long goalId = latestGoal.getGoalId();
        result.put("goalId", goalId);
        result.put("goalName", latestGoal.getGoalName());

        // 과목명이 같아도 subject_id와 goal_id가 다르면 별개의 과목입니다.
        List<Subject> currentSubjects = subjectRepository.findAll().stream()
                .filter(subject -> Objects.equals(subject.getGoalId(), goalId))
                .filter(subject -> subject.getSubjectId() != null)
                .sorted(Comparator.comparing(Subject::getSubjectId))
                .toList();

        if (currentSubjects.isEmpty()) {
            result.put("message", "현재 목표에 등록된 과목이 없습니다.");
            return result;
        }

        Set<Long> currentSubjectIds = currentSubjects.stream()
                .map(Subject::getSubjectId)
                .collect(Collectors.toSet());

        // 현재 사용자 + 현재 목표 과목에 속한 기록만 집계합니다.
        Map<Long, List<StudyLog>> groupedLogs = new HashMap<>();
        for (StudyLog log : studyLogRepository.findAll()) {
            if (!Objects.equals(log.getUserId(), DEMO_USER_ID)) continue;
            Long subjectId = log.getSubjectId();
            if (!currentSubjectIds.contains(subjectId)) continue;
            groupedLogs.computeIfAbsent(subjectId, id -> new ArrayList<>()).add(log);
        }

        // 기존 동작처럼 기록이 있는 과목만 분석 목록에 표시합니다.
        // 분석 데이터가 없는 과목이 0점/0%로 오해되지 않도록 제외합니다.
        for (Subject subject : currentSubjects) {
            Long subjectId = subject.getSubjectId();
            List<StudyLog> subjectLogs = groupedLogs.get(subjectId);
            if (subjectLogs == null || subjectLogs.isEmpty()) continue;

            int recordCount = subjectLogs.size();
            int totalStudyMinutes = 0;
            int totalUnderstanding = 0;
            int totalFocus = 0;
            int understandingCount = 0;
            int focusCount = 0;
            int totalCorrect = 0;
            int totalWrong = 0;

            for (StudyLog log : subjectLogs) {
                totalStudyMinutes += nonnegative(log.getStudyMinutes());

                if (log.getUnderstandingScore() != null && log.getUnderstandingScore() >= 1 && log.getUnderstandingScore() <= 5) {
                    totalUnderstanding += log.getUnderstandingScore();
                    understandingCount++;
                }
                if (log.getFocusScore() != null && log.getFocusScore() >= 1 && log.getFocusScore() <= 5) {
                    totalFocus += log.getFocusScore();
                    focusCount++;
                }
                totalCorrect += nonnegative(log.getCorrectCount());
                totalWrong += nonnegative(log.getWrongCount());
            }

            Double averageUnderstanding = understandingCount > 0
                    ? (double) totalUnderstanding / understandingCount : null;
            Double averageFocus = focusCount > 0
                    ? (double) totalFocus / focusCount : null;
            int totalQuestions = totalCorrect + totalWrong;

            Double correctRate = totalQuestions > 0
                    ? ((double) totalCorrect / totalQuestions) * 100
                    : null;

            String analysisMessage;
            if (understandingCount > 0 && averageUnderstanding < 3) {
                analysisMessage = "이 과목은 평균 이해도가 낮아 개념 복습이 우선적으로 필요합니다.";
            } else if (focusCount > 0 && averageFocus < 3) {
                analysisMessage = "이 과목은 집중도가 낮게 나타나 학습 시간대나 환경 조정이 필요합니다.";
            } else if (totalQuestions > 0 && totalWrong > totalCorrect) {
                analysisMessage = "이 과목은 오답 수가 많아 오답 원인 분석과 반복 복습이 필요합니다.";
            } else if (understandingCount == 0 && focusCount == 0 && totalQuestions == 0) {
                analysisMessage = "분석할 이해도·집중도·정답률 데이터가 부족합니다. 다음 기록에 해당 항목을 입력해 주세요.";

            } else if (recordCount < 3) {
                analysisMessage = "현재 기록의 측정값을 참고하되 추가 관찰이 필요합니다.";
            } else if (understandingCount > 0 && focusCount > 0) {
                analysisMessage = "현재 기록에서 이해도와 집중도는 양호한 편입니다. "
                        + "지속적인 학습 추세는 추가 기록을 통해 확인해야 합니다.";
            } else if (understandingCount > 0) {
                analysisMessage = "현재 기록에서 이해도는 양호한 편입니다. "
                        + "집중도는 측정 기록이 없어 평가하지 않습니다.";
            } else if (focusCount > 0) {
                analysisMessage = "현재 기록에서 집중도는 양호한 편입니다. "
                        + "이해도는 측정 기록이 없어 평가하지 않습니다.";
            } else {
                analysisMessage = "기록된 문제풀이 결과를 참고해 학습을 이어가세요. "
                        + "이해도와 집중도는 측정 기록이 없어 평가하지 않습니다.";
            }
            // 적은 기록 수의 한계는 낮은 점수 안내와 함께 표시합니다.
            if (recordCount < 3) {
                analysisMessage += " 현재 학습 기록이 " + recordCount
                        + "건으로 부족하여 학습 추세를 확정하기 어렵습니다. "
                        + "공부 기록을 더 입력해 주세요.";
            }

            Map<String, Object> subjectResult = new LinkedHashMap<>();
            subjectResult.put("goalId", goalId);
            subjectResult.put("subjectId", subjectId);
            subjectResult.put("subjectName", subject.getSubjectName());
            subjectResult.put("difficulty", subject.getDifficulty());
            subjectResult.put("priorityLevel", subject.getPriorityLevel());
            subjectResult.put("currentConfidence", subject.getCurrentConfidence());
            subjectResult.put("recordCount", recordCount);
            subjectResult.put("totalStudyMinutes", totalStudyMinutes);
            subjectResult.put("averageUnderstanding", averageUnderstanding);
            subjectResult.put("averageFocus", averageFocus);
            subjectResult.put("totalCorrect", totalCorrect);
            subjectResult.put("totalWrong", totalWrong);
            subjectResult.put("correctRate", correctRate);
            subjectResult.put("analysisMessage", analysisMessage);

            subjectAnalysisList.add(subjectResult);
        }

        result.put("analyzedSubjectCount", subjectAnalysisList.size());
        if (subjectAnalysisList.isEmpty()) {
            result.put("message", "현재 목표의 공부 기록이 아직 없습니다. 공부 기록을 입력하면 분석이 표시됩니다.");
        }
        return result;
    }

    private int nonnegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
