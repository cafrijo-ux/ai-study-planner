package ai_study_planner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 확정된/임시 저장된 학습 계획 스냅샷을 MySQL에 보관합니다.
 * GET은 데이터를 변경하지 않으며, POST 저장에서만 INSERT/UPDATE 합니다.
 * 테스트 단계의 고정 사용자 ID(1)를 사용합니다.
 */
@Service
public class StudySavedPlanService {
    public static final long USER_ID = 1L;
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public StudySavedPlanService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record SavedPlan(Map<String, Object> plan, LocalDate sourceDate,
                            LocalTime sourceEnd, String status, String start,
                            String end, String busyStart, String busyEnd, String savedAt) {
        public boolean usableNow() {
            return "FINAL".equals(status) ||
                    LocalDateTime.now(KOREA).isBefore(LocalDateTime.of(sourceDate, sourceEnd));
        }

        // 저장 후 사용자가 시간 입력을 바꾸면 저장된 계획을 강제로 덮어 보여주지 않습니다.
        public boolean sameInputs(String startValue, String endValue,
                                  String busyStartValue, String busyEndValue) {
            // 서버 내부에서 인자를 생략한 경우에는 저장된 시간대를 그대로 사용합니다.
            if (startValue == null && endValue == null
                    && busyStartValue == null && busyEndValue == null) return true;
            // 실제 화면에서 시작/종료 입력을 전달한 경우 '다른 일정 없음'도 비교합니다.
            return matches(startValue, start) && matches(endValue, end)
                    && matches(busyStartValue, busyStart)
                    && matches(busyEndValue, busyEnd);
        }

        private static boolean matches(String supplied, String saved) {
            return Objects.equals(supplied == null ? "" : supplied.trim(),
                    saved == null ? "" : saved);
        }
    }

    public SavedPlan find(long goalId, LocalDate planDate) {
        String sql = """
                SELECT plan_json, source_date, source_end_time, plan_status,
                       start_time, end_time, busy_start, busy_end, saved_at
                FROM study_saved_plans
                WHERE user_id = ? AND goal_id = ? AND plan_date = ?
                """;
        List<SavedPlan> rows = jdbcTemplate.query(sql, (rs, n) -> {
            Map<String, Object> data;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(rs.getString("plan_json"), Map.class);
                data = parsed;
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("저장된 계획 JSON을 읽을 수 없습니다.", e);
            }
            String date = planDate.toString();
            if (data.get("goalId") != null && !String.valueOf(goalId).equals(data.get("goalId").toString())
                    || data.get("scheduleDate") != null && !date.equals(data.get("scheduleDate").toString())
                    || data.get("planDate") != null && !date.equals(data.get("planDate").toString()))
                throw new IllegalStateException("저장 계획의 목표 또는 날짜가 일치하지 않습니다.");
            // Same authoritative identity as the monthly snapshot contract; never write to DB.
            data.put("goalId", goalId);
            data.put("planDate", date);
            data.put("planSaved", true);
            data.put("planStatus", rs.getString("plan_status"));
            data.put("savedAt", rs.getTimestamp("saved_at").toLocalDateTime().toString());
            LocalDate source = rs.getDate("source_date").toLocalDate();
            LocalTime sourceEnd = rs.getTime("source_end_time").toLocalTime();
            return new SavedPlan(data, source, sourceEnd, rs.getString("plan_status"),
                    clock(rs.getTime("start_time")), clock(rs.getTime("end_time")),
                    clock(rs.getTime("busy_start")), clock(rs.getTime("busy_end")),
                    rs.getTimestamp("saved_at").toLocalDateTime().toString());
        }, USER_ID, goalId, Date.valueOf(planDate));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Calendar snapshots only: one bounded query, no calculation/settings/upsert. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findMonth(long goalId, java.time.YearMonth month) {
        int[] characters = {0};
        return jdbcTemplate.query("""
                SELECT goal_id, plan_date,
                       CASE WHEN CHAR_LENGTH(plan_json) <= 262144 THEN plan_json ELSE NULL END AS plan_json,
                       plan_status, saved_at, source_date, source_end_time
                FROM study_saved_plans
                WHERE user_id = ? AND goal_id = ? AND plan_date >= ? AND plan_date < ?
                ORDER BY plan_date ASC LIMIT 32
                """, (rs, n) -> {
            if (n >= 31) throw new IllegalStateException("월간 저장 계획의 개수가 올바르지 않습니다.");
            Map<String, Object> plan;
            try {
                String json = rs.getString("plan_json");
                if (json == null || json.length() > 262144 || (characters[0] += json.length()) > 1048576)
                    throw new IllegalStateException("월간 계획 응답이 너무 큽니다.");
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(json, Map.class);
                plan = new LinkedHashMap<>(parsed);
            } catch (JsonProcessingException | IllegalArgumentException | NullPointerException ex) {
                throw new IllegalStateException("저장된 계획 JSON을 읽을 수 없습니다.");
            }
            String date = rs.getDate("plan_date").toLocalDate().toString();
            if (plan.get("goalId") != null && !String.valueOf(goalId).equals(plan.get("goalId").toString())
                    || plan.get("scheduleDate") != null && !date.equals(plan.get("scheduleDate").toString())
                    || plan.get("planDate") != null && !date.equals(plan.get("planDate").toString()))
                throw new IllegalStateException("저장 계획의 목표 또는 날짜가 일치하지 않습니다.");
            plan.put("goalId", goalId);
            plan.put("planDate", date);
            plan.put("saved", true);
            plan.put("planSaved", true);
            plan.put("planStatus", rs.getString("plan_status"));
            plan.put("savedAt", rs.getTimestamp("saved_at").toLocalDateTime().toString());
            boolean usable = "FINAL".equals(rs.getString("plan_status")) || LocalDateTime.now(KOREA).isBefore(
                    LocalDateTime.of(rs.getDate("source_date").toLocalDate(), rs.getTime("source_end_time").toLocalTime()));
            plan.put("usableNow", usable);
            if (!usable) plan.put("warning", "임시 계획의 원본 공부 종료 시각이 지났습니다. 최신 기록으로 명시적으로 다시 저장하기 전까지 FINAL이 아닙니다.");
            return plan;
        }, USER_ID, goalId, Date.valueOf(month.atDay(1)), Date.valueOf(month.plusMonths(1).atDay(1)));
    }

    // 이월 계산용 읽기 전용 조회. 저장 당시의 계획을 현재 목표 시간으로 다시 만들지 않습니다.
    // from이 없으면 해당 사용자/목표의 전체 과거 FINAL을 조회합니다.
    public Map<LocalDate, Map<String, Object>> findFinalPlans(long goalId,
                                                           LocalDate from, LocalDate until) {
        Map<LocalDate, Map<String, Object>> plans = new LinkedHashMap<>();
        String sql = """
                SELECT plan_date, plan_json, end_time
                FROM study_saved_plans
                WHERE user_id = ? AND goal_id = ? AND plan_status = 'FINAL'
                """ + (from == null ? "" : " AND plan_date >= ?")
                + " AND plan_date < ? ORDER BY plan_date ASC";
        Object[] parameters = from == null
                ? new Object[]{USER_ID, goalId, Date.valueOf(until)}
                : new Object[]{USER_ID, goalId, Date.valueOf(from), Date.valueOf(until)};
        jdbcTemplate.query(sql, (rs, n) -> {
            LocalDate date = rs.getDate("plan_date").toLocalDate();
            Map<String, Object> plan = new LinkedHashMap<>();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(rs.getString("plan_json"), Map.class);
                if (parsed != null) plan.putAll(parsed);
            } catch (JsonProcessingException | IllegalArgumentException ex) {
                // 불완전한 과거 자료는 계산 단계에서 확인 대상으로 분류합니다.
            }
            if (plan.get("scheduleEnd") == null && rs.getTime("end_time") != null) {
                plan.put("scheduleEnd", date + " " + clock(rs.getTime("end_time")));
            }
            plans.put(date, plan);
            return date;
        }, parameters);
        return plans;
    }

    @Transactional
    public void save(long goalId, LocalDate sourceDate, LocalTime sourceEnd,
                     LocalDate planDate, String planStatus,
                     String startTime, String endTime, String busyStart, String busyEnd,
                     Map<String, Object> plan) {
        if (!"DRAFT".equals(planStatus) && !"FINAL".equals(planStatus)) {
            throw new IllegalArgumentException("계획 상태는 DRAFT 또는 FINAL이어야 합니다.");
        }
        if (!Boolean.TRUE.equals(plan.get("isScheduleValid"))
                || !Boolean.FALSE.equals(plan.get("hasConflict"))) {
            throw new IllegalArgumentException("검증에 실패한 시간표는 저장할 수 없습니다.");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>(plan);
        // 미리보기 문구를 확정된 저장 계획에 남기지 않습니다.
        snapshot.remove("previewMode");
        snapshot.remove("previewNotice");
        snapshot.put("planSaved", true);
        snapshot.put("planStatus", planStatus);
        snapshot.put("sourceDate", sourceDate.toString());
        final String json;
        try {
            json = mapper.writeValueAsString(jsonSafe(snapshot));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("학습 계획을 JSON으로 변환하지 못했습니다.", e);
        }

        String sql = """
                INSERT INTO study_saved_plans
                   (user_id, goal_id, plan_date, source_date, source_end_time,
                    plan_status, start_time, end_time, busy_start, busy_end, plan_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                   source_date = VALUES(source_date),
                   source_end_time = VALUES(source_end_time),
                   plan_status = VALUES(plan_status),
                   start_time = VALUES(start_time), end_time = VALUES(end_time),
                   busy_start = VALUES(busy_start), busy_end = VALUES(busy_end),
                   plan_json = VALUES(plan_json), saved_at = CURRENT_TIMESTAMP
                """;
        jdbcTemplate.update(sql, USER_ID, goalId, Date.valueOf(planDate),
                Date.valueOf(sourceDate), Time.valueOf(sourceEnd), planStatus,
                Time.valueOf(LocalTime.parse(startTime)), Time.valueOf(LocalTime.parse(endTime)),
                sqlTime(busyStart), sqlTime(busyEnd), json);

        // 다음 날에 화면을 다시 열어도 같은 시각/바쁜 일정으로 시간표를 요청할 수 있도록
        // 내일의 시간 설정도 함께 저장합니다. 하나의 트랜잭션으로 처리합니다.
        // 내일 설정이 미리 존재하면 미리보기가 해당 설정을 사용했으므로 동일한 값만 갱신합니다.
        String settingsSql = """
                INSERT INTO study_schedule_settings
                    (user_id, schedule_date, start_time, end_time, busy_start, busy_end)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    start_time = VALUES(start_time), end_time = VALUES(end_time),
                    busy_start = VALUES(busy_start), busy_end = VALUES(busy_end)
                """;
        jdbcTemplate.update(settingsSql, USER_ID, Date.valueOf(planDate),
                Time.valueOf(LocalTime.parse(startTime)), Time.valueOf(LocalTime.parse(endTime)),
                sqlTime(busyStart), sqlTime(busyEnd));
    }

    private static Time sqlTime(String clock) {
        return clock == null || clock.isBlank() ? null : Time.valueOf(LocalTime.parse(clock));
    }

    private static String clock(Time time) {
        return time == null ? "" : time.toLocalTime().toString().substring(0, 5);
    }

    // LocalDate 등 Java Time 타입을 Jackson JavaTimeModule 의존 없이 직렬화합니다.
    private Object jsonSafe(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean)
            return value;
        if (value instanceof TemporalAccessor) return value.toString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                safe.put(String.valueOf(entry.getKey()), jsonSafe(entry.getValue()));
            }
            return safe;
        }
        if (value instanceof List<?> list) {
            List<Object> safe = new ArrayList<>();
            for (Object item : list) safe.add(jsonSafe(item));
            return safe;
        }
        throw new IllegalArgumentException("직렬화할 수 없는 계획 항목: " + value.getClass().getName());
    }
}
