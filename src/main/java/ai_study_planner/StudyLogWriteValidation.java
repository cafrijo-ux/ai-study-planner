package ai_study_planner;

final class StudyLogWriteValidation {
    private StudyLogWriteValidation() {}
    static String error(StudyLog studyLog) {
        if (studyLog == null) return "공부 기록이 없습니다.";

        if (studyLog.getUserId() == null) {
            return "사용자 정보가 없습니다.";
        }

        if (studyLog.getSubjectId() == null) {
            return "과목을 선택해야 합니다.";
        }

        if (studyLog.getStudyDate() == null) {
            return "공부 날짜를 입력해야 합니다.";
        }

        if (studyLog.getStudyMinutes() == null || studyLog.getStudyMinutes() <= 0) {
            return "공부 시간은 1분 이상이어야 합니다.";
        }

        if (studyLog.getStudyContent() == null || studyLog.getStudyContent().isBlank()) {
            return "공부 내용을 입력해야 합니다.";
        }

        if (studyLog.getUnderstandingScore() == null
                || studyLog.getUnderstandingScore() < 1
                || studyLog.getUnderstandingScore() > 5) {
            return "이해도는 1점에서 5점 사이여야 합니다.";
        }

        if (studyLog.getFocusScore() == null
                || studyLog.getFocusScore() < 1
                || studyLog.getFocusScore() > 5) {
            return "집중도는 1점에서 5점 사이여야 합니다.";
        }

        if (studyLog.getFatigueScore() == null
                || studyLog.getFatigueScore() < 1
                || studyLog.getFatigueScore() > 5) {
            return "피로도는 1점에서 5점 사이여야 합니다.";
        }

        int totalQuestions = studyLog.getTotalQuestions() != null ? studyLog.getTotalQuestions() : 0;
        int correctCount = studyLog.getCorrectCount() != null ? studyLog.getCorrectCount() : 0;
        int wrongCount = studyLog.getWrongCount() != null ? studyLog.getWrongCount() : 0;

        if (totalQuestions < 0 || correctCount < 0 || wrongCount < 0) {
            return "문제 수, 정답 수, 오답 수는 음수가 될 수 없습니다.";
        }

        if ((long) correctCount + wrongCount > totalQuestions) {
            return "정답 수와 오답 수의 합은 전체 문제 수보다 클 수 없습니다.";
        }

        return null;
    }
}
