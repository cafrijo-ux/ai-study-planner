package ai_study_planner;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
public class LlmPromptController {

    private final GoalRepository goalRepository;
    private final SubjectRepository subjectRepository;
    private final StudyLogRepository studyLogRepository;

    public LlmPromptController(
            GoalRepository goalRepository,
            SubjectRepository subjectRepository,
            StudyLogRepository studyLogRepository
    ) {
        this.goalRepository = goalRepository;
        this.subjectRepository = subjectRepository;
        this.studyLogRepository = studyLogRepository;
    }

    @GetMapping("/llm-prompt-preview")
    public Map<String, Object> getLlmPromptPreview() {
        List<Goal> goals = GoalSelection.goals(goalRepository.findAll());
        List<Subject> subjects = GoalSelection.subjects(subjectRepository.findAll());
        List<StudyLog> logs = GoalSelection.logs(studyLogRepository.findAll(), subjectRepository.findAll());

        Map<String, Object> result = new HashMap<>();

        if (goals.isEmpty() || subjects.isEmpty()) {
            result.put("message", "목표 또는 과목 정보가 부족하여 LLM 프롬프트를 생성할 수 없습니다.");
            return result;
        }

        Goal latestGoal = goals.get(goals.size() - 1);
        Subject latestSubject = subjects.get(subjects.size() - 1);

        int totalStudyMinutes = 0;
        int totalUnderstanding = 0;
        int totalFocus = 0;
        int understandingCount = 0;
        int focusCount = 0;
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
                understandingCount++;
            }

            if (log.getFocusScore() != null) {
                totalFocus += log.getFocusScore();
                focusCount++;
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

        Double averageUnderstanding = understandingCount > 0
                ? (double) totalUnderstanding / understandingCount
                : null;

        Double averageFocus = focusCount > 0
                ? (double) totalFocus / focusCount
                : null;

        int totalQuestions = totalCorrect + totalWrong;

        Double correctRate = totalQuestions > 0
                ? ((double) totalCorrect / totalQuestions) * 100
                : null;

        String prompt =
                "너는 개인 맞춤형 학습 코치야.\n\n" +

                        "아래 사용자의 학습 데이터를 바탕으로 구체적인 공부 피드백을 작성해줘.\n" +
                        "단순히 '열심히 하세요'처럼 말하지 말고, 원인 분석과 실행 가능한 계획을 포함해줘.\n\n" +

                        "[사용자 목표 정보]\n" +
                        "- 목표명: " + latestGoal.getGoalName() + "\n" +
                        "- 목표 유형: " + latestGoal.getGoalType() + "\n" +
                        "- 시험일 또는 마감일: " + latestGoal.getExamDate() + "\n" +
                        "- 목표 점수: " + latestGoal.getTargetScore() + "\n" +
                        "- 현재 수준: " + latestGoal.getCurrentLevel() + "\n" +
                        "- 약한 부분: " + latestGoal.getWeakPoint() + "\n" +
                        "- 하루 공부 가능 시간: " + latestGoal.getDailyStudyTime() + "분\n" +
                        "- 선호 공부 방식: " + latestGoal.getPreferredStudyMethod() + "\n\n" +

                        "[과목 정보]\n" +
                        "- 과목명: " + latestSubject.getSubjectName() + "\n" +
                        "- 난이도: " + latestSubject.getDifficulty() + "\n" +
                        "- 중요도: " + latestSubject.getPriorityLevel() + "\n" +
                        "- 현재 자신감: " + latestSubject.getCurrentConfidence() + "\n\n" +

                        "[최근 학습 기록 분석]\n" +
                        "- 총 공부 시간: " + totalStudyMinutes + "분\n" +
                        "- 평균 이해도: " + (averageUnderstanding == null ? "정보 없음" : String.format("%.1f/5", averageUnderstanding)) + "\n" +
                        "- 평균 집중도: " + (averageFocus == null ? "정보 없음" : String.format("%.1f/5", averageFocus)) + "\n" +
                        "- 정답 수: " + totalCorrect + "\n" +
                        "- 오답 수: " + totalWrong + "\n" +
                        "- 정답률: " + (correctRate == null ? "정보 없음" : String.format("%.1f%%", correctRate)) + "\n" +
                        "- 최근 오답 원인: " + lastWrongReason + "\n" +
                        "- 최근 어려웠던 개념: " + lastDifficultTopic + "\n\n" +

                        "[요청]\n" +
                        "정보 없음은 0점이나 미공부를 의미하지 않습니다. 없는 오답 기록이나 성과를 만들어내지 마세요.\n" +
                        "1. 현재 학습 상태를 진단해줘.\n" +
                        "2. 가장 큰 문제 원인을 설명해줘.\n" +
                        "3. 오늘 공부 순서를 제안해줘.\n" +
                        "4. 시간 배분을 구체적으로 제시해줘.\n" +
                        "5. 오답과 복습 전략을 제안해줘.\n" +
                        "6. 학습자가 바로 실천할 수 있는 문장으로 작성해줘.";

        result.put("title", "LLM 프롬프트 미리보기");
        result.put("goalName", latestGoal.getGoalName());
        result.put("subjectName", latestSubject.getSubjectName());
        result.put("averageUnderstanding", averageUnderstanding);
        result.put("averageFocus", averageFocus);
        result.put("correctRate", correctRate);
        result.put("prompt", prompt);

        return result;
    }
}
