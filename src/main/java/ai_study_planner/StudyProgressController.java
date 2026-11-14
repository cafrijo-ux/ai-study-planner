package ai_study_planner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 오늘의 계획 대비 실제 학습 달성률 API.
 * 현재 프로젝트의 단일 사용자(user_id=1) 데모 구조를 따릅니다.
 * 기존 StudyPlanController와 study_schedule_settings 테이블을 재사용합니다.
 */
@RestController
public class StudyProgressController {

    private static final long DEMO_USER_ID = 1L;
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter SLOT_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbcTemplate;
    private final StudyPlanController studyPlanController;

    public StudyProgressController(JdbcTemplate jdbcTemplate,
                                   StudyPlanController studyPlanController) {
        this.jdbcTemplate = jdbcTemplate;
        this.studyPlanController = studyPlanController;
    }

    @GetMapping("/study-progress")
    public Map<String, Object> getTodayProgress() {
        Map<String, Object> result = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(KOREA);
        result.put("studyDate", today.toString());

        // 이전 목표에 속한 과목의 공부 기록은 합산하지 않습니다.
        List<Long> goalIds = GoalSelection.goalIds(jdbcTemplate, DEMO_USER_ID);
        if (goalIds.isEmpty()) {
            result.put("message", "학습 목표가 없습니다. 먼저 목표를 등록하세요.");
            return result;
        }
        Long goalId = goalIds.get(0);
        result.put("goalId", goalId);

        String startTime = null;
        String endTime = null;
        String busyStart = null;
        String busyEnd = null;

        // 같은 날짜에 저장한 16번 카드의 시간 설정을 사용합니다.
        List<Map<String, Object>> settings = jdbcTemplate.queryForList(
                "SELECT start_time, end_time, busy_start, busy_end " +
                "FROM study_schedule_settings WHERE user_id = ? AND schedule_date = ? LIMIT 1",
                DEMO_USER_ID, today);
        if (!settings.isEmpty()) {
            Map<String, Object> setting = settings.get(0);
            startTime = clock(setting.get("start_time"));
            endTime = clock(setting.get("end_time"));
            busyStart = clock(setting.get("busy_start"));
            busyEnd = clock(setting.get("busy_end"));
        }
        result.put("savedScheduleUsed", !settings.isEmpty());

        Map<String, Object> plan = studyPlanController.getStudyPlan(
                startTime, endTime, busyStart, busyEnd);
        if (plan.containsKey("message")) {
            result.put("message", "학습 계획을 계산할 수 없습니다: " + plan.get("message"));
            return result;
        }
        if (!Boolean.TRUE.equals(plan.get("isScheduleValid"))) {
            result.put("message", "시간표 검증이 완료되지 않았습니다. 시간표 설정을 확인하세요.");
            result.put("scheduleValidationMessage", plan.get("scheduleValidationMessage"));
            return result;
        }

        int plannedMinutes = integer(plan.get("studyMinutes"));  // 휴식 제외
        if (plannedMinutes <= 0) {
            result.put("message", "오늘 계획된 공부 시간이 없습니다.");
            return result;
        }

        Long actualValue = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(l.study_minutes), 0) " +
                "FROM study_logs l " +
                "JOIN subjects s ON s.subject_id = l.subject_id " +
                "WHERE l.user_id = ? AND s.goal_id = ? AND l.study_date = ?",
                Long.class, DEMO_USER_ID, goalId, today);
        int actualMinutes = actualValue == null ? 0 :
                (int) Math.min(Integer.MAX_VALUE, Math.max(0L, actualValue));
        Long recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM study_logs l JOIN subjects s ON s.subject_id = l.subject_id "
                + "WHERE l.user_id = ? AND s.goal_id = ? AND l.study_date = ? AND l.study_minutes > 0",
                Long.class, DEMO_USER_ID, goalId, today);
        boolean hasRecord = recordCount != null && recordCount > 0;

        List<Map<String, Object>> bySubject = jdbcTemplate.queryForList(
                "SELECT s.subject_id AS subjectId, s.subject_name AS subjectName, " +
                "COALESCE(SUM(l.study_minutes), 0) AS actualMinutes " +
                "FROM subjects s LEFT JOIN study_logs l " +
                "ON s.subject_id = l.subject_id AND l.user_id = ? AND l.study_date = ? " +
                "WHERE s.goal_id = ? " +
                "GROUP BY s.subject_id, s.subject_name ORDER BY s.subject_id",
                DEMO_USER_ID, today, goalId);

        int remainingMinutes = Math.max(0, plannedMinutes - actualMinutes);
        double achievementRate = Math.round(actualMinutes * 1000.0 / plannedMinutes) / 10.0;
        String endLabel = String.valueOf(plan.get("scheduleEnd"));
        boolean scheduleFinished;
        try {
            scheduleFinished = !LocalDateTime.now(KOREA)
                    .isBefore(LocalDateTime.parse(endLabel, SLOT_DATE_TIME));
        } catch (java.time.DateTimeException ex) {
            result.put("message", "저장된 계획의 종료 시각을 확인해 주세요.");
            return result;
        }
        boolean completed = actualMinutes >= plannedMinutes;
        String status = completed ? "목표 달성" : !scheduleFinished ? "학습 진행 중"
                : !hasRecord ? "기록 확인 필요" : "목표 미달";

        // 학습 시간대 종료 후에만 미달 시간을 다음 학습일의 우선 학습량으로 제안합니다.
        // 다음 날 가용 시간을 초과해 공부 시간을 추가하지 않습니다.
        int confirmedShortfall = scheduleFinished && hasRecord ? remainingMinutes : 0;
        long daysToExam = 7;
        Object examDateValue = plan.get("examDate");
        try {
            if (examDateValue != null) {
                LocalDate examDate = LocalDate.parse(examDateValue.toString());
                daysToExam = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(today.plusDays(1), examDate));
            }
        } catch (java.time.DateTimeException ex) {
            // 과거 저장 자료에 시험일이 없거나 올바르지 않으면 기존 7일 기준을 유지합니다.
        }
        int spreadDays = (int) Math.min(7, daysToExam);
        int nextDayPriorityMinutes = confirmedShortfall == 0 ? 0
                : Math.min(plannedMinutes, (int) ((confirmedShortfall + (long) spreadDays - 1) / spreadDays));
        int unassignedShortfallMinutes = Math.max(0, confirmedShortfall - nextDayPriorityMinutes);

        result.put("plannedStudyMinutes", plannedMinutes);
        result.put("actualStudyMinutes", actualMinutes);
        result.put("remainingStudyMinutes", remainingMinutes);
        result.put("achievementRate", achievementRate);
        result.put("scheduleEnd", endLabel);
        result.put("scheduleFinished", scheduleFinished);
        result.put("status", status);
        result.put("recordStatus", hasRecord ? "RECORDED" : scheduleFinished ? "UNRECORDED" : "IN_PROGRESS");
        result.put("hasStudyRecord", hasRecord);
        result.put("bySubject", bySubject);
        result.put("confirmedShortfallMinutes", confirmedShortfall);
        result.put("suggestedNextDayPriorityMinutes", nextDayPriorityMinutes);
        result.put("unassignedShortfallMinutes", unassignedShortfallMinutes);
        // 재조정은 새로운 DB 행을 쓰는 방식이 아니라, 시간표 조회 때 자동 계산하여 반영합니다.
        result.put("rescheduleApplied", plan.get("rescheduleApplied"));
        result.put("carryoverMinutesAppliedToday", plan.get("carryoverMinutes"));
        result.put("rescheduleSaved", false);

        if (completed) {
            result.put("adjustmentMessage", "오늘의 공부 시간 목표를 달성했습니다. 남은 시간은 복습에 활용하세요.");
        } else if (!scheduleFinished) {
            result.put("adjustmentMessage", "아직 오늘의 공부 시간대가 끝나지 않았습니다. " +
                    "남은 " + remainingMinutes + "분은 진행 중인 목표이며, 미달로 확정하지 않습니다.");
        } else if (!hasRecord) {
            result.put("adjustmentMessage", "공부 시간대는 종료되었지만 기록이 없습니다. "
                    + "실제로 공부하지 않았다고 판단하지 않으며, 기록 입력 후 부족분을 계산합니다.");
        } else {
            result.put("adjustmentMessage", "입력된 기록 기준 미달 시간은 " + confirmedShortfall + "분입니다. " +
                    "다음 학습일의 시간표를 조회하면, 시험일까지 남은 기간과 가용 공부 시간을 고려해 " +
                    "미완료 학습을 먼저 자동 배정합니다. 다음 날 보충 학습 예상량은 약 " +
                    nextDayPriorityMinutes + "분입니다. (이미 이월된 과제에 따라 달라질 수 있습니다.)");
        }
        result.put("notice", "현재 목표와 오늘 날짜 기준입니다. " +
                "미완료 학습은 공부 종료 후 다음 날 /study-plan 조회 시 시간표에 자동 배정됩니다. " +
                "별도의 재배치 DB 저장은 없으며, 이전 학습일의 저장된 시간표와 공부 기록을 다시 계산합니다.");
        return result;
    }

    private String clock(Object value) {
        if (value == null) return null;
        String text = value.toString();
        return text.length() >= 5 ? text.substring(0, 5) : text;
    }

    private int integer(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }
}
