
package ai_study_planner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisRegressionTests {

    private StudyLogRepository logs;
    private SubjectRepository subjects;
    private GoalRepository goals;
    private StudyLog studyLog;

    private Map<String, Object> personalFeedback() {
        StudyQuestionRepository questions = mock(StudyQuestionRepository.class);
        when(questions.findAll()).thenReturn(List.of());
        return new PersonalFeedbackController(goals, subjects, logs, questions).getPersonalFeedback();
    }

    @Test void personalFeedbackDoesNotInventMissingMeasurements() {
        when(studyLog.getUnderstandingScore()).thenReturn(null);
        when(studyLog.getFocusScore()).thenReturn(null);
        var data = personalFeedback();
        assertNull(data.get("averageUnderstanding"));
        assertNull(data.get("averageFocus"));
        assertNull(data.get("correctRate"));
        assertTrue(data.get("personalFeedback").toString().contains("정보 없음"));
        assertFalse(data.get("personalFeedback").toString().contains("0.0%"));
    }

    @Test void personalFeedbackAveragesOnlyMeasuredRecords() {
        StudyLog missing = mock(StudyLog.class);
        when(missing.getUserId()).thenReturn(1L);
        when(missing.getSubjectId()).thenReturn(1L);
        when(missing.getUnderstandingScore()).thenReturn(null);
        when(missing.getFocusScore()).thenReturn(null);
        when(logs.findAll()).thenReturn(List.of(studyLog, missing));
        var data = personalFeedback();
        assertEquals(3.0, data.get("averageUnderstanding"));
        assertEquals(3.0, data.get("averageFocus"));
        assertNull(data.get("correctRate"));
    }

    @Test void personalFeedbackWarnsForObservedZeroPercent() {
        when(studyLog.getWrongCount()).thenReturn(10);
        var data = personalFeedback();
        assertEquals(0.0, data.get("correctRate"));
        assertTrue(data.get("personalFeedback").toString().contains("정답률이 낮"));
    }

    @Test void personalFeedbackDoesNotRequireUnobservedWrongAnswerReview() {
        when(studyLog.getUnderstandingScore()).thenReturn(2);
        var data = personalFeedback();
        String text = data.get("personalFeedback").toString();
        assertTrue(text.contains("이해도가 낮"));
        assertTrue(text.contains("추세를 확정하기 어렵"));
        assertFalse(text.contains("오답 정리 20분"));
    }

    @Test void promptPreviewKeepsMissingMeasurementsUnknown() {
        when(studyLog.getUnderstandingScore()).thenReturn(null);
        when(studyLog.getFocusScore()).thenReturn(null);
        var data = new LlmPromptController(goals, subjects, logs).getLlmPromptPreview();
        assertNull(data.get("correctRate"));
        assertNull(data.get("averageUnderstanding"));
        assertNull(data.get("averageFocus"));
        assertTrue(data.get("prompt").toString().contains("정답률: 정보 없음"));
        assertFalse(data.get("prompt").toString().contains("0.0%"));
    }

    @Test void promptPreviewKeepsMeasuredZeroPercent() {
        when(studyLog.getWrongCount()).thenReturn(10);
        var data = new LlmPromptController(goals, subjects, logs).getLlmPromptPreview();
        assertEquals(0.0, data.get("correctRate"));
        assertEquals(3.0, data.get("averageUnderstanding"));
        assertTrue(data.get("prompt").toString().contains("정답률: 0.0%"));
    }

    @BeforeEach
    void setUp() {
        logs = mock(StudyLogRepository.class);
        subjects = mock(SubjectRepository.class);
        goals = mock(GoalRepository.class);

        Goal goal = mock(Goal.class);
        when(goal.getGoalId()).thenReturn(1L);
        when(goal.getUserId()).thenReturn(1L);
        when(goal.getGoalName()).thenReturn("운영체제 테스트");

        Subject subject = mock(Subject.class);
        when(subject.getSubjectId()).thenReturn(1L);
        when(subject.getGoalId()).thenReturn(1L);
        when(subject.getUserId()).thenReturn(1L);
        when(subject.getSubjectName()).thenReturn("운영체제");

        studyLog = mock(StudyLog.class);
        when(studyLog.getUserId()).thenReturn(1L);
        when(studyLog.getSubjectId()).thenReturn(1L);
        when(studyLog.getStudyMinutes()).thenReturn(40);
        when(studyLog.getUnderstandingScore()).thenReturn(3);
        when(studyLog.getFocusScore()).thenReturn(3);
        when(studyLog.getCorrectCount()).thenReturn(0);
        when(studyLog.getWrongCount()).thenReturn(0);

        when(goals.findAll()).thenReturn(List.of(goal));
        when(subjects.findAll()).thenReturn(List.of(subject));
        when(logs.findAll()).thenReturn(List.of(studyLog));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> subjectResult() {
        SubjectAnalysisController controller =
                new SubjectAnalysisController(logs, subjects, goals);

        Map<String, Object> response =
                controller.getSubjectAnalysis();

        List<Map<String, Object>> items =
                (List<Map<String, Object>>) response.get("subjectAnalysis");

        assertEquals(1, items.size());
        return items.get(0);
    }

    @Test
    void noQuestionsMeansNoCorrectRate() {
        Map<String, Object> result = subjectResult();

        assertNull(result.get("correctRate"));
        assertEquals(0, result.get("totalCorrect"));
        assertEquals(0, result.get("totalWrong"));
    }

    @Test
    void zeroCorrectAnswersStillMeansZeroPercent() {
        when(studyLog.getWrongCount()).thenReturn(10);

        Map<String, Object> result = subjectResult();

        assertEquals(
                0.0,
                ((Number) result.get("correctRate")).doubleValue(),
                0.001
        );
    }

    @Test
    void oneStudyRecordRequiresMoreData() {
        StudyAnalysisController controller =
                new StudyAnalysisController(logs, goals, subjects);

        Map<String, Object> result =
                controller.getAnalysisSummary("current");

        assertEquals(1, result.get("recordCount"));
        assertEquals(40, result.get("totalStudyMinutes"));

        String message = (String) result.get("mainMessage");
        assertTrue(message.contains("부족"));
    }

    @Test
    void weakSubjectDoesNotInventWrongAnswers() {
        WeakSubjectController controller =
                new WeakSubjectController(logs, subjects, goals);

        Map<String, Object> result = controller.getWeakSubject();

        assertEquals(1, result.get("recordCount"));
        assertNull(result.get("correctRate"));

        String reason = (String) result.get("reason");
        String strategy = (String) result.get("strategy");

        assertTrue(reason.contains("부족"));
        assertFalse(strategy.contains("오답 정리"));
    }

    private String summaryMessage() {
        return (String) new StudyAnalysisController(logs, goals, subjects)
                .getAnalysisSummary("current").get("mainMessage");
    }

    private void threeRecords() {
        when(logs.findAll()).thenReturn(List.of(studyLog, studyLog, studyLog));
    }

    @Test
    void threeRecordsWithoutMeasurementsCannotBeHealthyOrLow() {
        threeRecords();
        when(studyLog.getUnderstandingScore()).thenReturn(null);
        when(studyLog.getFocusScore()).thenReturn(null);
        String message = summaryMessage();
        assertTrue(message.contains("측정 기록이 없어"));
        assertFalse(message.contains("양호"));
        assertFalse(message.contains("낮"));
        assertFalse(subjectResult().get("analysisMessage").toString().contains("양호"));
    }

    @Test
    void lowUnderstandingAlsoExplainsSmallSample() {
        when(studyLog.getUnderstandingScore()).thenReturn(2);
        String message = subjectResult().get("analysisMessage").toString();
        assertTrue(message.contains("이해도가 낮"));
        assertTrue(message.contains("1건으로 부족"));
        assertTrue(message.contains("추세를 확정하기 어렵"));
    }

    @Test
    void lowFocusAlsoExplainsSmallSample() {
        when(studyLog.getFocusScore()).thenReturn(2);
        String message = subjectResult().get("analysisMessage").toString();
        assertTrue(message.contains("집중도가 낮"));
        assertTrue(message.contains("1건으로 부족"));
        assertTrue(message.contains("추세를 확정하기 어렵"));
    }

    @Test
    void understandingOnlyDoesNotEvaluateFocus() {
        threeRecords();
        when(studyLog.getFocusScore()).thenReturn(null);
        String message = subjectResult().get("analysisMessage").toString();
        assertTrue(message.contains("이해도는 양호"));
        assertTrue(message.contains("집중도는 측정 기록이 없어 평가하지 않습니다"));
        assertFalse(message.contains("이해도와 집중도는 양호"));
    }

    @Test
    void focusOnlyDoesNotEvaluateUnderstanding() {
        threeRecords();
        when(studyLog.getUnderstandingScore()).thenReturn(null);
        String message = subjectResult().get("analysisMessage").toString();
        assertTrue(message.contains("집중도는 양호"));
        assertTrue(message.contains("이해도는 측정 기록이 없어 평가하지 않습니다"));
    }

    @Test
    void questionsOnlyDoNotInventScoreAssessment() {
        threeRecords();
        when(studyLog.getUnderstandingScore()).thenReturn(null);
        when(studyLog.getFocusScore()).thenReturn(null);
        when(studyLog.getCorrectCount()).thenReturn(8);
        when(studyLog.getWrongCount()).thenReturn(2);
        Map<String, Object> result = subjectResult();
        assertEquals(80.0, result.get("correctRate"));
        assertTrue(result.get("analysisMessage").toString().contains("평가하지 않습니다"));
        assertFalse(result.get("analysisMessage").toString().contains("양호"));
    }

    @Test
    void summaryKeepsRealLowUnderstandingWarning() {
        threeRecords();
        when(studyLog.getUnderstandingScore()).thenReturn(2);
        assertTrue(summaryMessage().contains("이해도가 낮"));
    }

    @Test
    void summaryKeepsRealLowFocusWarning() {
        threeRecords();
        when(studyLog.getFocusScore()).thenReturn(2);
        assertTrue(summaryMessage().contains("집중도가 낮"));
    }

    @Test
    void healthyMeasuredScoresKeepExistingAssessment() {
        threeRecords();
        assertTrue(summaryMessage().contains("양호"));
        assertTrue(subjectResult().get("analysisMessage").toString().contains("이해도와 집중도는 양호"));
    }

    @Test
    void explicitGoalAndExistingResponseFieldsArePreserved() {
        Goal first = goals.findAll().get(0);
        Goal later = mock(Goal.class);
        when(later.getGoalId()).thenReturn(3L);
        when(later.getUserId()).thenReturn(1L);
        when(goals.findAll()).thenReturn(List.of(first, later));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("goalId", "1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            Map<String, Object> summary = new StudyAnalysisController(logs, goals, subjects)
                    .getAnalysisSummary("all");
            assertEquals(1L, summary.get("goalId"));
            assertEquals("CURRENT_GOAL", summary.get("scope"));
            assertEquals(40, summary.get("totalStudyMinutes"));
            Map<String, Object> subject = subjectResult();
            assertEquals(1L, subject.get("goalId"));
            assertEquals(Set.of("goalId", "subjectId", "subjectName", "difficulty", "priorityLevel",
                    "currentConfidence", "recordCount", "totalStudyMinutes", "averageUnderstanding",
                    "averageFocus", "totalCorrect", "totalWrong", "correctRate", "analysisMessage"),
                    subject.keySet());
            verify(logs, never()).save(any());
            verify(subjects, never()).save(any());
            verify(goals, never()).save(any());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
