package ai_study_planner;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class StudyScheduleSettingsController {

    // 현재 프로젝트는 단일 사용자 테스트 단계입니다.
    // 로그인 기능을 추가하면 실제 로그인 사용자 ID로 바꿔야 합니다.
    private static final long TEST_USER_ID = 1L;
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter UPDATED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public StudyScheduleSettingsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 예: GET /study-schedule-settings?scheduleDate=2026-10-13
    // 날짜가 없으면 한국 기준 오늘 날짜를 조회합니다.
    @GetMapping("/study-schedule-settings")
    public ResponseEntity<Map<String, Object>> getSettings(
            @RequestParam(required = false) String scheduleDate) {
        try {
            LocalDate date = parseDate(scheduleDate);
            return ResponseEntity.ok(loadSettings(date));
        } catch (DateTimeParseException ex) {
            return badRequest("날짜 형식은 YYYY-MM-DD여야 합니다.");
        }
    }

    // 예: POST /study-schedule-settings
    // {"scheduleDate":"2026-10-13","startTime":"19:00","endTime":"23:00",
    //  "busyStart":"20:00","busyEnd":"20:30"}
    @PostMapping("/study-schedule-settings")
    public ResponseEntity<Map<String, Object>> saveSettings(
            @RequestBody Map<String, String> request) {
        try {
            LocalDate date = parseDate(request.get("scheduleDate"));
            LocalTime start = LocalTime.parse(required(request, "startTime"));
            LocalTime end = LocalTime.parse(required(request, "endTime"));
            String busyStartText = optional(request, "busyStart");
            String busyEndText = optional(request, "busyEnd");

            if (!start.isBefore(end)) {
                return badRequest("공부 종료 시간은 시작 시간보다 늦어야 합니다. 자정을 넘는 일정은 아직 지원하지 않습니다.");
            }

            if (busyStartText.isEmpty() != busyEndText.isEmpty()) {
                return badRequest("다른 일정 시작과 종료 시간은 둘 다 입력하거나 둘 다 비워야 합니다.");
            }

            LocalTime busyStart = busyStartText.isEmpty() ? null : LocalTime.parse(busyStartText);
            LocalTime busyEnd = busyEndText.isEmpty() ? null : LocalTime.parse(busyEndText);

            if (busyStart != null && (!busyStart.isBefore(busyEnd)
                    || busyStart.isBefore(start) || busyEnd.isAfter(end))) {
                return badRequest("다른 일정은 공부 가능 시간 안에서 시작 시간이 종료 시간보다 빨라야 합니다.");
            }

            // user_id + schedule_date가 UNIQUE이므로 같은 날 다시 저장하면 갱신됩니다.
            String sql = """
                    INSERT INTO study_schedule_settings
                        (user_id, schedule_date, start_time, end_time, busy_start, busy_end)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        start_time = ?, end_time = ?, busy_start = ?, busy_end = ?
                    """;

            jdbcTemplate.update(sql,
                    TEST_USER_ID, Date.valueOf(date), Time.valueOf(start), Time.valueOf(end),
                    asSqlTime(busyStart), asSqlTime(busyEnd),
                    Time.valueOf(start), Time.valueOf(end), asSqlTime(busyStart), asSqlTime(busyEnd));

            return ResponseEntity.ok(loadSettings(date));
        } catch (DateTimeParseException ex) {
            return badRequest("날짜 또는 시간 형식이 올바르지 않습니다. 날짜는 YYYY-MM-DD, 시간은 HH:mm으로 입력하세요.");
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    private Map<String, Object> loadSettings(LocalDate date) {
        String sql = """
                SELECT schedule_id, schedule_date, start_time, end_time,
                       busy_start, busy_end, updated_at
                FROM study_schedule_settings
                WHERE user_id = ? AND schedule_date = ?
                """;

        List<Map<String, Object>> rows = jdbcTemplate.query(sql, (rs, rowNumber) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("saved", true);
            item.put("scheduleId", rs.getLong("schedule_id"));
            item.put("scheduleDate", rs.getDate("schedule_date").toLocalDate().toString());
            item.put("startTime", formatSqlTime(rs.getTime("start_time")));
            item.put("endTime", formatSqlTime(rs.getTime("end_time")));
            item.put("busyStart", formatSqlTime(rs.getTime("busy_start")));
            item.put("busyEnd", formatSqlTime(rs.getTime("busy_end")));
            Timestamp updatedAt = rs.getTimestamp("updated_at");
            item.put("updatedAt", updatedAt == null ? null
                    : updatedAt.toLocalDateTime().format(UPDATED_FORMAT));
            return item;
        }, TEST_USER_ID, Date.valueOf(date));

        if (!rows.isEmpty()) {
            return rows.get(0);
        }

        // 저장 기록이 없으면 화면에 적용할 기본값을 반환합니다.
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("saved", false);
        defaults.put("scheduleDate", date.toString());
        defaults.put("startTime", "19:00");
        defaults.put("endTime", "23:00");
        defaults.put("busyStart", "");
        defaults.put("busyEnd", "");
        defaults.put("message", "이 날짜에 저장된 시간표 설정이 없어 기본값을 표시합니다.");
        return defaults;
    }

    private LocalDate parseDate(String value) {
        return value == null || value.isBlank()
                ? LocalDate.now(KOREA)
                : LocalDate.parse(value.trim());
    }

    private String required(Map<String, String> request, String field) {
        String value = optional(request, field);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " 값을 입력해야 합니다.");
        }
        return value;
    }

    private String optional(Map<String, String> request, String field) {
        String value = request.get(field);
        return value == null ? "" : value.trim();
    }

    private Time asSqlTime(LocalTime time) {
        return time == null ? null : Time.valueOf(time);
    }

    private String formatSqlTime(Time value) {
        return value == null ? "" : value.toLocalTime().format(TIME_FORMAT);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message);
        return ResponseEntity.badRequest().body(result);
    }
}
