package ai_study_planner;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class PersonalFeedbackController {

    private final GoalRepository goalRepository;
    private final SubjectRepository subjectRepository;
    private final StudyLogRepository studyLogRepository;
    private final StudyQuestionRepository studyQuestionRepository;

    public PersonalFeedbackController(
            GoalRepository goalRepository,
            SubjectRepository subjectRepository,
            StudyLogRepository studyLogRepository,
            StudyQuestionRepository studyQuestionRepository
    ) {
        this.goalRepository = goalRepository;
        this.subjectRepository = subjectRepository;
        this.studyLogRepository = studyLogRepository;
        this.studyQuestionRepository = studyQuestionRepository;
    }

    @GetMapping("/personal-feedback")
    public Map<String, Object> getPersonalFeedback() {
        Map<String, Object> result = new HashMap<>();

        List<Goal> goals = GoalSelection.goals(goalRepository.findAll());
        List<Subject> subjects = GoalSelection.subjects(subjectRepository.findAll());
        List<StudyLog> logs = GoalSelection.logs(studyLogRepository.findAll(), subjectRepository.findAll());
        List<StudyQuestion> questions = studyQuestionRepository.findAll().stream().filter(q -> java.util.Objects.equals(q.getUserId(), 1L)).toList();

        if (goals.isEmpty()) {
            result.put("message", "학습 목표 정보가 없어 개인화 피드백을 생성할 수 없습니다.");
            result.put("recommendation", "먼저 학습 목표 설정을 입력하세요.");
            return result;
        }

        Goal latestGoal = goals.get(goals.size() - 1);

        result.put("title", "개인화 종합 피드백");
        result.put("goalName", latestGoal.getGoalName());
        result.put("currentLevel", latestGoal.getCurrentLevel());
        result.put("weakPoint", latestGoal.getWeakPoint());
        result.put("dailyStudyTime", latestGoal.getDailyStudyTime());

        if (!subjects.isEmpty()) {
            Subject latestSubject = subjects.get(subjects.size() - 1);
            result.put("subjectName", latestSubject.getSubjectName());
            result.put("difficulty", latestSubject.getDifficulty());
            result.put("priorityLevel", latestSubject.getPriorityLevel());
            result.put("currentConfidence", latestSubject.getCurrentConfidence());
        } else {
            result.put("subjectName", "미등록");
        }

        int totalStudyMinutes = 0;
        int totalUnderstanding = 0;
        int totalFocus = 0;
        int understandingCount = 0;
        int focusCount = 0;
        int totalCorrect = 0;
        int totalWrong = 0;

        for (StudyLog log : logs) {
            totalStudyMinutes += log.getStudyMinutes() != null ? Math.max(log.getStudyMinutes(), 0) : 0;
            if (log.getUnderstandingScore() != null) {
                totalUnderstanding += log.getUnderstandingScore();
                understandingCount++;
            }
            if (log.getFocusScore() != null) {
                totalFocus += log.getFocusScore();
                focusCount++;
            }
            totalCorrect += log.getCorrectCount() != null ? log.getCorrectCount() : 0;
            totalWrong += log.getWrongCount() != null ? log.getWrongCount() : 0;
        }

        Double averageUnderstanding = understandingCount == 0 ? null : (double) totalUnderstanding / understandingCount;
        Double averageFocus = focusCount == 0 ? null : (double) totalFocus / focusCount;
        int totalQuestions = totalCorrect + totalWrong;
        Double correctRate = totalQuestions == 0 ? null : (double) totalCorrect / totalQuestions * 100;

        result.put("totalStudyMinutes", totalStudyMinutes);
        result.put("averageUnderstanding", averageUnderstanding == null ? null : Math.round(averageUnderstanding * 10) / 10.0);
        result.put("averageFocus", averageFocus == null ? null : Math.round(averageFocus * 10) / 10.0);
        result.put("correctRate", correctRate == null ? null : Math.round(correctRate * 10) / 10.0);
        result.put("questionCount", questions.size());

        StringBuilder feedback = new StringBuilder();

        feedback.append("[종합 분석]\n");

        if (logs.isEmpty()) {
            feedback.append("아직 공부 기록이 부족하여 정확한 학습 패턴 분석은 어렵습니다.\n");
            feedback.append("현재는 목표 정보와 과목 정보를 기준으로 기본 피드백을 제공합니다.\n\n");
        } else {
            feedback.append("현재까지 총 ").append(totalStudyMinutes).append("분 공부했습니다.\n");
            feedback.append("평균 이해도: ").append(averageUnderstanding == null ? "정보 없음" : String.format("%.1f/5", averageUnderstanding)).append("\n");
            feedback.append("평균 집중도: ").append(averageFocus == null ? "정보 없음" : String.format("%.1f/5", averageFocus)).append("\n");
            feedback.append("문제 정답률: ").append(correctRate == null ? "정보 없음" : String.format("%.1f%%", correctRate)).append("\n\n");
            if (logs.size() < 3) feedback.append("기록이 부족해 학습 추세를 확정하기 어렵습니다.\n");
        }

        feedback.append("[강점]\n");

        if (averageFocus != null && averageFocus >= 4) {
            feedback.append("집중도가 높은 편이므로 현재 학습 리듬은 잘 유지되고 있습니다.\n");
        } else if (totalStudyMinutes >= 120) {
            feedback.append("누적 공부 시간이 어느 정도 쌓이고 있어 학습 습관 형성이 진행되고 있습니다.\n");
        } else {
            feedback.append("아직 기록은 적지만 목표를 설정하고 학습을 시작했다는 점이 긍정적입니다.\n");
        }

        feedback.append("\n[보완할 점]\n");

        if (averageUnderstanding != null && averageUnderstanding < 3) {
            feedback.append("이해도가 낮은 편이므로 새로운 내용을 빠르게 넘어가기보다 개념 복습이 필요합니다.\n");
        }

        if (averageFocus != null && averageFocus < 3) {
            feedback.append("집중도가 낮은 편이므로 긴 시간 공부보다 짧은 집중 학습 방식이 적합합니다.\n");
        }

        if (correctRate != null && correctRate < 60) {
            feedback.append("정답률이 낮으므로 오답 원인을 기록하고 비슷한 유형을 반복하는 것이 좋습니다.\n");
        }

        if (questions.size() > 0) {
            feedback.append("공부 중 질문 기록이 있으므로 헷갈린 개념을 따로 복습하는 과정이 필요합니다.\n");
        }

        feedback.append("\n[다음 학습 추천]\n");

        if (averageUnderstanding != null && averageUnderstanding < 3) {
            feedback.append("개념 복습 후 쉬운 예제를 통해 이해한 내용을 확인하세요.");
        } else if (averageFocus != null && averageFocus < 3) {
            feedback.append("오늘은 25분 집중 학습 → 5분 휴식 방식으로 공부 시간을 나누는 것이 좋습니다.");
        } else if (correctRate != null && correctRate < 60) {
            feedback.append("오늘은 새로운 단원보다 오답 문제를 다시 풀고 틀린 이유를 정리하세요.");
        } else {
            feedback.append("공부 기록과 측정 지표를 꾸준히 쌓고, 어려운 개념은 질문 기능을 활용하세요.");
        }

        result.put("personalFeedback", feedback.toString());

        return result;
    }
}
