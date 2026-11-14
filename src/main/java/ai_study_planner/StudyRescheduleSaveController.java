package ai_study_planner;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * '내일 시간표 저장'은 서버가 직접 생성한 검증된 계획만 저장합니다.
 * 임의의 클라이언트 계획 JSON을 받지 않아 부정확한 시간이 저장되는 것을 막습니다.
 */
@RestController
public class StudyRescheduleSaveController {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbcTemplate;
    private final StudyPlanController studyPlanController;
    private final StudySavedPlanService savedPlanService;

    public StudyRescheduleSaveController(JdbcTemplate jdbcTemplate,
                                         StudyPlanController studyPlanController,
                                         StudySavedPlanService savedPlanService) {
        this.jdbcTemplate = jdbcTemplate;
        this.studyPlanController = studyPlanController;
        this.savedPlanService = savedPlanService;
    }

    @PostMapping("/study-reschedule-save")
    public ResponseEntity<Map<String, Object>> saveTomorrow() {
        LocalDate sourceDate = LocalDate.now(KOREA);
        LocalDate planDate = sourceDate.plusDays(1);

        List<Map<String, Object>> sourceSettings = jdbcTemplate.queryForList(
                "SELECT end_time FROM study_schedule_settings WHERE user_id = ? AND schedule_date = ? LIMIT 1",
                StudySavedPlanService.USER_ID, Date.valueOf(sourceDate));
        if (sourceSettings.isEmpty()) {
            return ResponseEntity.badRequest().body(message(
                    "먼저 학습 계획 탭에서 오늘의 시간표를 MySQL에 저장해 주세요."));
        }
        LocalTime endClock = LocalTime.parse(sourceSettings.get(0).get("end_time").toString());
        boolean sourceFinished = !LocalDateTime.now(KOREA)
                .isBefore(LocalDateTime.of(sourceDate, endClock));

        // 요청 시마다 최신 공부 기록과 저장된 시간 설정으로 다시 계산합니다.
        Map<String, Object> preview = studyPlanController.previewTomorrowPlan();
        if (preview.containsKey("message")) {
            return ResponseEntity.badRequest().body(message(String.valueOf(preview.get("message"))));
        }
        if (!Boolean.TRUE.equals(preview.get("isScheduleValid"))
                || !Boolean.FALSE.equals(preview.get("hasConflict"))) {
            return ResponseEntity.badRequest().body(message("시간표 충돌 검사에 실패해 저장하지 않았습니다."));
        }
        Long goalId = ((Number) preview.get("goalId")).longValue();
        LocalDate targetDate = LocalDate.parse(preview.get("scheduleDate").toString());
        if (!planDate.equals(targetDate)) {
            return ResponseEntity.badRequest().body(message("계획 날짜가 내일 날짜와 일치하지 않습니다."));
        }

        // 다음 날 시간 입력: 미리보기에서 실제 사용된 시간을 사용합니다.
        String start = preview.get("scheduleStart").toString().substring(11, 16);
        String end = preview.get("scheduleEnd").toString().substring(11, 16);
        String busyStart = shortTime(preview.get("busyStart"));
        String busyEnd = shortTime(preview.get("busyEnd"));

        String status = sourceFinished ? "FINAL" : "DRAFT";
        savedPlanService.save(goalId, sourceDate, endClock, planDate, status,
                start, end, busyStart, busyEnd, preview);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        result.put("planDate", planDate.toString());
        result.put("planStatus", status);
        result.put("studyMinutes", preview.get("studyMinutes"));
        result.put("carryoverMinutes", preview.get("carryoverMinutes"));
        result.put("newStudyMinutes", preview.get("newStudyMinutes"));
        result.put("message", sourceFinished
                ? "공부 종료 후 계산한 내일 시간표를 최종 저장했습니다."
                : "오늘 공부 종료 전이므로 임시 저장했습니다. 공부 종료 후 '내일 시간표 저장'을 다시 눌러 확정하세요.");
        return ResponseEntity.ok(result);
    }

    // DB 저장 확인 전용. 날짜 생략 시 내일 계획을 조회합니다.
    @GetMapping("/study-reschedule-calendar")
    public ResponseEntity<Map<String, Object>> getCalendar(
            @RequestParam(name = "month") String month,
            @RequestParam(name = "goalId") long goalId) {
        java.time.YearMonth selected;
        try {
            if (!month.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException();
            selected = java.time.YearMonth.parse(month);
            if (selected.getYear() < 1970 || selected.getYear() > 2100) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(message("월은 1970~2100년의 YYYY-MM 형식이어야 합니다."));
        }
        // Explicit goal is mandatory; existing authenticated demo owner remains user_id=1.
        List<Long> ids = GoalSelection.goalIds(jdbcTemplate, StudySavedPlanService.USER_ID);
        if (goalId <= 0 || !ids.contains(goalId)) return ResponseEntity.badRequest().body(message("목표 번호를 확인하세요."));
        try {
            return ResponseEntity.ok(Map.of("goalId", goalId, "month", month,
                    "plans", savedPlanService.findMonth(goalId, selected)));
        } catch (IllegalStateException ex) {
            return ResponseEntity.internalServerError().body(message("월간 저장 계획을 확인할 수 없습니다. 기존 자료는 변경하지 않았습니다."));
        }
    }

    @GetMapping("/study-reschedule-saved")
    public ResponseEntity<Map<String, Object>> getSaved(
            @RequestParam(name = "date", required = false) String date, @RequestParam(name = "goalId") long goalId) {
        LocalDate planDate;
        try {
            planDate = (date == null || date.isBlank())
                    ? LocalDate.now(KOREA).plusDays(1) : LocalDate.parse(date.trim());
        } catch (DateTimeParseException ex) {
            return ResponseEntity.badRequest().body(message("날짜는 YYYY-MM-DD 형식이어야 합니다."));
        }
        List<Long> goals = GoalSelection.goalIds(jdbcTemplate, StudySavedPlanService.USER_ID);
        if (goalId <= 0 || !goals.contains(goalId)) return ResponseEntity.badRequest().body(message("목표 번호를 확인하세요."));
        StudySavedPlanService.SavedPlan saved = savedPlanService.find(goalId, planDate);
        if (saved == null) {
            Map<String, Object> response = message("이 날짜에 저장된 학습 계획이 없습니다.");
            response.put("saved", false);
            return ResponseEntity.ok(response);
        }
        Map<String, Object> response = new LinkedHashMap<>(saved.plan());
        response.put("saved", true);
        response.put("usableNow", saved.usableNow());
        if (!saved.usableNow()) {
            response.put("warning", "임시 계획을 저장한 뒤 공부 종료 시각이 지났습니다. 오늘/내일의 최신 학습 기록으로 다시 저장하세요.");
        }
        return ResponseEntity.ok(response);
    }

    private static String shortTime(Object value) {
        if (value == null) return "";
        String valueText = value.toString();
        return valueText.length() >= 5 ? valueText.substring(0, 5) : valueText;
    }

    private static Map<String, Object> message(String value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", value);
        return result;
    }
}
