package ai_study_planner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StudyRescheduleSaveControllerTests {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final long GOAL_ID = 10L;

    private final Map<LocalDate, Map<String, Object>> settingsRows = new LinkedHashMap<>();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private Map<String, Object> savedPlanRow;
    private final Map<LocalDate, Map<String, Object>> historicalPlans = new LinkedHashMap<>();
    private final List<StudyLog> studyLogs = new java.util.ArrayList<>();
    private JdbcTemplate jdbcTemplate;
    private MockMvc mockMvc;
    private MockedStatic<LocalDate> dates;
    private MockedStatic<LocalDateTime> times;

    private enum Scenario {
        DIFFERENT_TOMORROW_BUSY,
        TOMORROW_WITHOUT_BUSY,
        TOMORROW_NOT_CONFIGURED
    }

    @BeforeEach
    void setUp() {
        // Standalone MVC: no Spring context, real datasource, or application properties.
        jdbcTemplate = mock(JdbcTemplate.class);
        GoalRepository goals = mock(GoalRepository.class);
        SubjectRepository subjects = mock(SubjectRepository.class);
        StudyLogRepository logs = mock(StudyLogRepository.class);

        Goal goal = new Goal();
        goal.setGoalId(GOAL_ID);
        goal.setUserId(1L);
        goal.setGoalName("Test goal");
        goal.setDailyStudyTime(120);
        goal.setExamDate(TODAY.plusDays(30));
        Subject subject = new Subject();
        subject.setSubjectId(100L);
        subject.setUserId(1L);
        subject.setGoalId(GOAL_ID);
        subject.setSubjectName("Test subject");
        when(goals.findAll()).thenReturn(List.of(goal));
        when(subjects.findAll()).thenReturn(List.of(subject));
        when(logs.findAll()).thenAnswer(i -> new java.util.ArrayList<>(studyLogs));
        settingsRows.put(TODAY, settingsRow(TODAY, "19:00", "23:00", "20:00", "20:30"));
        mockJdbcReadsAndWrites();

        // Exercise the real save service; its SQL calls only reach the mock above.
        StudySavedPlanService savedPlans = new StudySavedPlanService(jdbcTemplate);
        StudyPlanController plans = new StudyPlanController(goals, subjects, logs,
                jdbcTemplate, savedPlans);
        mockMvc = MockMvcBuilders.standaloneSetup(plans,
                new StudyRescheduleSaveController(jdbcTemplate, plans, savedPlans),
                new StudyScheduleSettingsController(jdbcTemplate)).build();
    }

    @AfterEach
    void tearDown() {
        if (times != null) times.close();
        if (dates != null) dates.close();
        verify(jdbcTemplate, never()).getDataSource();
    }

    @Test
    void savedPlanLookupWithoutGoalIsRejectedBeforeAnyWrite() throws Exception {
        mockMvc.perform(get("/study-reschedule-saved").param("date", TOMORROW.toString()))
                .andExpect(status().isBadRequest());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void savesAndReloadsTheSettingsActuallyUsedByPreview(Scenario scenario) throws Exception {
        freezeAt("22:30");
        assertRoundTrip(scenario, "DRAFT");
    }

    @ParameterizedTest
    @CsvSource({"22:30,DRAFT", "23:00,FINAL", "23:01,FINAL"})
    void keepsSaveStatusBasedOnTodaysEndTime(String now, String expectedStatus) throws Exception {
        // Tomorrow ends at 22:00; status must still follow today's 23:00 boundary.
        freezeAt(now);
        assertRoundTrip(Scenario.DIFFERENT_TOMORROW_BUSY, expectedStatus);
    }

    @Test
    void doesNotSaveWhenTodaysSettingsAreMissing() throws Exception {
        freezeAt("22:30");
        settingsRows.remove(TODAY);
        settingsRows.put(TOMORROW, settingsRow(TOMORROW, "18:00", "22:00", "21:00", "21:30"));

        mockMvc.perform(post("/study-reschedule-save"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void doesNotSaveInvalidTomorrowSettingsOrFallBackToToday() throws Exception {
        freezeAt("22:30");
        settingsRows.put(TOMORROW, settingsRow(TOMORROW, "18:00", "22:00", "17:00", "17:30"));

        mockMvc.perform(get("/study-reschedule-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());
        mockMvc.perform(post("/study-reschedule-save"))
                .andExpect(status().isBadRequest());

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void savesAndReloadsDateDetailsAndValidationFields() throws Exception {
        freezeAt("23:01");
        seedMockFinal(TODAY, new LinkedHashMap<>(Map.of(
                "studyMinutes", 100, "carryoverMinutes", 0, "newStudyMinutes", 100,
                "isScheduleValid", true, "hasConflict", false, "scheduleEnd", TODAY + " 23:00")));
        String previewJson = mockMvc.perform(get("/study-reschedule-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carryoverMinutes").value(0))
                .andExpect(jsonPath("$.unrecordedDays[0].date").value(TODAY.toString()))
                .andExpect(jsonPath("$.unrecordedDays[0].plannedStudyMinutes").value(100))
                .andExpect(jsonPath("$.unrecordedDays[0].basis").value("FINAL"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode preview = jsonMapper.readTree(previewJson);
        mockMvc.perform(post("/study-reschedule-save"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planStatus").value("FINAL"));
        JsonNode stored = jsonMapper.readTree((String) savedPlanRow.get("plan_json"));
        for (String field : List.of("unrecordedDates", "unrecordedPlannedMinutes", "unrecordedDays",
                "studyMinutes", "carryoverMinutes", "newStudyMinutes", "isScheduleValid", "hasConflict", "scheduleEnd")) {
            assertEquals(preview.path(field), stored.path(field), field);
        }
        mockMvc.perform(get("/study-reschedule-saved").param("goalId", Long.toString(GOAL_ID)).param("date", TOMORROW.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isScheduleValid").value(true))
                .andExpect(jsonPath("$.hasConflict").value(false))
                .andExpect(jsonPath("$.scheduleEnd").value(preview.path("scheduleEnd").asString()))
                .andExpect(jsonPath("$.unrecordedDates[0]").value(TODAY.toString()))
                .andExpect(jsonPath("$.unrecordedPlannedMinutes").value(100))
                .andExpect(jsonPath("$.unrecordedDays[0].plannedStudyMinutes").value(100))
                .andExpect(jsonPath("$.unrecordedDays[0].basis").value("FINAL"));
    }

    @Test
    void olderFinalWithoutDayDetailsIsReadWithoutRewritingJson() throws Exception {
        freezeAt("23:01");
        Map<String, Object> old = new LinkedHashMap<>(Map.of(
                "studyMinutes", 100, "carryoverMinutes", 9, "newStudyMinutes", 91,
                "isScheduleValid", true, "hasConflict", false, "scheduleEnd", TOMORROW + " 23:00",
                "unrecordedDates", List.of(TODAY.minusDays(1).toString()), "unrecordedPlannedMinutes", 180));
        seedMockFinal(TOMORROW, old);
        String originalJson = (String) savedPlanRow.get("plan_json");
        mockMvc.perform(get("/study-reschedule-saved").param("goalId", Long.toString(GOAL_ID)).param("date", TOMORROW.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planStatus").value("FINAL"))
                .andExpect(jsonPath("$.unrecordedDays").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.unrecordedPlannedMinutes").value(180))
                .andExpect(jsonPath("$.studyMinutes").value(100))
                .andExpect(jsonPath("$.carryoverMinutes").value(9))
                .andExpect(jsonPath("$.newStudyMinutes").value(91));
        mockMvc.perform(get("/study-plan").param("date", TOMORROW.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planSaved").value(true))
                .andExpect(jsonPath("$.unrecordedDays").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.carryoverMinutes").value(9));
        assertEquals(originalJson, savedPlanRow.get("plan_json"));
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void oldDeficitSurvivesSaveReloadAndLateLogDoesNotRewriteFinal() throws Exception {
        freezeAt("23:01");
        LocalDate old = TODAY.minusDays(30);
        seedMockFinal(old, new LinkedHashMap<>(Map.of(
                "studyMinutes",100,"carryoverMinutes",0,"newStudyMinutes",100,
                "isScheduleValid",true,"hasConflict",false,"scheduleEnd",old+" 23:00")));
        StudyLog record = new StudyLog(); record.setUserId(1L); record.setSubjectId(100L);
        record.setStudyDate(old); record.setStudyMinutes(40); studyLogs.add(record);
        settingsRows.remove(TODAY); // isolate historic FINAL from settings-estimate days
        mockMvc.perform(get("/study-reschedule-preview"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.carryoverTotalPendingMinutes").value(60))
                .andExpect(jsonPath("$.carryoverUnattributedMinutes").value(60))
                .andExpect(jsonPath("$.carryoverMinutes").value(0));
        // Saving still requires today's settings. A valid FINAL today avoids a new estimated deficit.
        settingsRows.put(TODAY,settingsRow(TODAY,"19:00","23:00","20:00","20:30"));
        seedMockFinal(TODAY,new LinkedHashMap<>(Map.of(
                "studyMinutes",100,"carryoverMinutes",0,"newStudyMinutes",100,
                "isScheduleValid",true,"hasConflict",false,"scheduleEnd",TODAY+" 23:00")));
        mockMvc.perform(post("/study-reschedule-save"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planStatus").value("FINAL"))
                .andExpect(jsonPath("$.carryoverMinutes").value(0));
        String snapshot=(String)savedPlanRow.get("plan_json");
        mockMvc.perform(get("/study-reschedule-saved").param("goalId", Long.toString(GOAL_ID)).param("date",TOMORROW.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.carryoverTotalPendingMinutes").value(60))
                .andExpect(jsonPath("$.carryoverCalculation[0].date").value(old.toString()))
                .andExpect(jsonPath("$.unrecordedDays[0].date").value(TODAY.toString()));
        org.mockito.Mockito.clearInvocations(jdbcTemplate);
        record.setStudyMinutes(100); // memory-only late correction
        mockMvc.perform(get("/study-reschedule-preview"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.carryoverTotalPendingMinutes").value(0));
        mockMvc.perform(get("/study-plan").param("date",TOMORROW.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planSaved").value(true))
                .andExpect(jsonPath("$.carryoverMinutes").value(0));
        assertEquals(snapshot,savedPlanRow.get("plan_json"));
        verify(jdbcTemplate,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void oldUnknownDayDetailsSurviveFinalSaveAndReload() throws Exception {
        freezeAt("23:01");
        LocalDate old=TODAY.minusDays(20);
        seedMockFinal(old,new LinkedHashMap<>(Map.of(
                "studyMinutes",80,"carryoverMinutes",0,"newStudyMinutes",80,
                "isScheduleValid",true,"hasConflict",false,"scheduleEnd",old+" 23:00")));
        mockMvc.perform(post("/study-reschedule-save"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planStatus").value("FINAL"));
        mockMvc.perform(get("/study-reschedule-saved").param("goalId", Long.toString(GOAL_ID)).param("date",TOMORROW.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.carryoverTotalPendingMinutes").value(0))
                .andExpect(jsonPath("$.unrecordedDates[0]").value(old.toString()))
                .andExpect(jsonPath("$.unrecordedPlannedMinutes").value(80))
                .andExpect(jsonPath("$.unrecordedDays[0].plannedStudyMinutes").value(80))
                .andExpect(jsonPath("$.unrecordedDays[0].basis").value("FINAL"))
                .andExpect(jsonPath("$.isScheduleValid").value(true))
                .andExpect(jsonPath("$.hasConflict").value(false));
    }

    @ParameterizedTest
    @CsvSource({"22:30,DRAFT","23:01,FINAL"})
    void subjectAndUnknownBalancesRoundTripWithSlots(String clock,String expectedStatus) throws Exception {
        freezeAt(clock);
        LocalDate old=TODAY.minusDays(30), known=TODAY.minusDays(20);
        seedMockFinal(old,new LinkedHashMap<>(Map.of(
                "studyMinutes",40,"carryoverMinutes",0,"newStudyMinutes",40,
                "isScheduleValid",true,"hasConflict",false,"scheduleEnd",old+" 23:00")));
        seedMockFinal(known,new LinkedHashMap<>(Map.of(
                "studyMinutes",100,"carryoverMinutes",0,"newStudyMinutes",100,
                "isScheduleValid",true,"hasConflict",false,"scheduleEnd",known+" 23:00",
                "subjectPlans",List.of(Map.of("subjectId",100L,"studyMinutes",100,"carryoverMinutes",0,"newStudyMinutes",100)))));
        for(var entry:Map.of(old,10,known,40).entrySet()) {
            StudyLog record=new StudyLog();record.setUserId(1L);record.setSubjectId(100L);
            record.setStudyDate(entry.getKey());record.setStudyMinutes(entry.getValue());studyLogs.add(record);
        }
        String oldJson=(String)historicalPlans.get(old).get("plan_json");
        String knownJson=(String)historicalPlans.get(known).get("plan_json");
        JsonNode preview=jsonMapper.readTree(mockMvc.perform(get("/study-reschedule-preview"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.carryoverTotalPendingMinutes").value(90))
                .andExpect(jsonPath("$.carryoverUnattributedMinutes").value(30))
                .andExpect(jsonPath("$.carryoverAllocatableMinutes").value(60))
                .andExpect(jsonPath("$.carryoverMinutes").value(9))
                .andExpect(jsonPath("$.carryoverBySubject[0].subjectId").value(100))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(post("/study-reschedule-save"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planStatus").value(expectedStatus));
        JsonNode stored=jsonMapper.readTree((String)savedPlanRow.get("plan_json"));
        JsonNode reloaded=jsonMapper.readTree(mockMvc.perform(get("/study-reschedule-saved").param("goalId", Long.toString(GOAL_ID)).param("date",TOMORROW.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planStatus").value(expectedStatus))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        for(String field:List.of("carryoverTotalPendingMinutes","carryoverUnattributedMinutes",
                "carryoverAllocatableMinutes","carryoverBySubject","carryoverAttributionReviewDates",
                "unrecordedDays","unrecordedSubjects","subjectPlans","timeSlots","carryoverCalculation",
                "studyMinutes","carryoverMinutes","newStudyMinutes","isScheduleValid","hasConflict","scheduleEnd")) {
            assertEquals(preview.path(field),stored.path(field),field);
            assertEquals(preview.path(field),reloaded.path(field),field);
        }
        assertEquals(oldJson,historicalPlans.get(old).get("plan_json"));
        assertEquals(knownJson,historicalPlans.get(known).get("plan_json"));
        assertTrue(stored.path("timeSlots").valueStream().filter(slot -> "공부".equals(slot.path("type").asString()))
                .allMatch(slot -> slot.path("subjectId").asLong()==100L
                        && List.of("CATCHUP","NEW").contains(slot.path("allocationType").asString())));
    }

    private void seedMockFinal(LocalDate date, Map<String, Object> plan) {
        plan.put("scheduleDate", date.toString());
        plan.put("goalId", GOAL_ID);
        savedPlanRow = new LinkedHashMap<>();
        savedPlanRow.put("goal_id", GOAL_ID);
        savedPlanRow.put("plan_date", Date.valueOf(date));
        savedPlanRow.put("source_date", Date.valueOf(date.minusDays(1)));
        savedPlanRow.put("source_end_time", Time.valueOf("23:00:00"));
        savedPlanRow.put("plan_status", "FINAL");
        savedPlanRow.put("start_time", Time.valueOf("19:00:00"));
        savedPlanRow.put("end_time", Time.valueOf("23:00:00"));
        savedPlanRow.put("saved_at", Timestamp.valueOf(TODAY.atTime(12, 0)));
        savedPlanRow.put("plan_json", jsonMapper.writeValueAsString(plan));
        historicalPlans.put(date,savedPlanRow);
    }

    private void assertRoundTrip(Scenario scenario, String expectedStatus) throws Exception {
        if (scenario != Scenario.TOMORROW_NOT_CONFIGURED) {
            settingsRows.put(TOMORROW, settingsRow(TOMORROW, "18:00", "22:00",
                    scenario == Scenario.TOMORROW_WITHOUT_BUSY ? "" : "21:00",
                    scenario == Scenario.TOMORROW_WITHOUT_BUSY ? "" : "21:30"));
        }
        Map<String, Object> originalToday = new LinkedHashMap<>(settingsRows.get(TODAY));
        String expectedStart = scenario == Scenario.TOMORROW_NOT_CONFIGURED ? "19:00" : "18:00";
        String expectedEnd = scenario == Scenario.TOMORROW_NOT_CONFIGURED ? "23:00" : "22:00";
        String expectedBusyStart = switch (scenario) {
            case DIFFERENT_TOMORROW_BUSY -> "21:00";
            case TOMORROW_WITHOUT_BUSY -> "";
            case TOMORROW_NOT_CONFIGURED -> "20:00";
        };
        String expectedBusyEnd = switch (scenario) {
            case DIFFERENT_TOMORROW_BUSY -> "21:30";
            case TOMORROW_WITHOUT_BUSY -> "";
            case TOMORROW_NOT_CONFIGURED -> "20:30";
        };

        String previewJson = mockMvc.perform(get("/study-reschedule-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busyStart").value(expectedBusyStart))
                .andExpect(jsonPath("$.busyEnd").value(expectedBusyEnd))
                .andExpect(jsonPath("$.previewMode").value(true))
                .andExpect(jsonPath("$.previewNotice").isNotEmpty())
                .andExpect(jsonPath("$.isScheduleValid").value(true))
                .andExpect(jsonPath("$.hasConflict").value(false))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode preview = jsonMapper.readTree(previewJson);

        mockMvc.perform(post("/study-reschedule-save"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.planDate").value(TOMORROW.toString()))
                .andExpect(jsonPath("$.planStatus").value(expectedStatus))
                .andExpect(jsonPath("$.studyMinutes").value(preview.path("studyMinutes").asInt()))
                .andExpect(jsonPath("$.carryoverMinutes").value(preview.path("carryoverMinutes").asInt()))
                .andExpect(jsonPath("$.newStudyMinutes").value(preview.path("newStudyMinutes").asInt()))
                .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(get("/study-schedule-settings").param("scheduleDate", TOMORROW.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.startTime").value(expectedStart))
                .andExpect(jsonPath("$.endTime").value(expectedEnd))
                .andExpect(jsonPath("$.busyStart").value(preview.path("busyStart").asString()))
                .andExpect(jsonPath("$.busyEnd").value(preview.path("busyEnd").asString()));

        mockMvc.perform(get("/study-reschedule-saved").param("goalId", Long.toString(GOAL_ID)).param("date", TOMORROW.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.usableNow").value(true))
                .andExpect(jsonPath("$.planStatus").value(expectedStatus))
                .andExpect(jsonPath("$.busyStart").value(expectedBusyStart))
                .andExpect(jsonPath("$.busyEnd").value(expectedBusyEnd))
                .andExpect(jsonPath("$.scheduleStart").value(preview.path("scheduleStart").asString()))
                .andExpect(jsonPath("$.scheduleEnd").value(preview.path("scheduleEnd").asString()));

        JsonNode snapshot = jsonMapper.readTree((String) savedPlanRow.get("plan_json"));
        assertTrue(snapshot.path("unrecordedDays").isArray());
        assertTrue(snapshot.path("isScheduleValid").isBoolean());
        assertTrue(snapshot.path("isScheduleValid").asBoolean());
        assertTrue(snapshot.path("hasConflict").isBoolean());
        assertEquals(false, snapshot.path("hasConflict").asBoolean());
        assertEquals(preview.path("scheduleEnd"), snapshot.path("scheduleEnd"));
        assertEquals(preview.path("unrecordedDates"), snapshot.path("unrecordedDates"));
        assertEquals(preview.path("unrecordedPlannedMinutes"), snapshot.path("unrecordedPlannedMinutes"));
        assertEquals(preview.path("unrecordedDays"), snapshot.path("unrecordedDays"));

        mockMvc.perform(get("/study-plan").param("date", TOMORROW.toString())
                        .param("startTime", expectedStart).param("endTime", expectedEnd)
                        .param("busyStart", expectedBusyStart).param("busyEnd", expectedBusyEnd))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planSaved").value(true))
                .andExpect(jsonPath("$.busyStart").value(expectedBusyStart))
                .andExpect(jsonPath("$.busyEnd").value(expectedBusyEnd));

        assertEquals(originalToday, settingsRows.get(TODAY), "Today's settings must not change");
        assertEquals(TODAY, ((Date) savedPlanRow.get("source_date")).toLocalDate());
        assertEquals(LocalTime.of(23, 0), ((Time) savedPlanRow.get("source_end_time")).toLocalTime());
    }

    private void freezeAt(String clock) {
        LocalDateTime now = LocalDateTime.of(TODAY, LocalTime.parse(clock));
        dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS);
        dates.when(() -> LocalDate.now(KOREA)).thenReturn(TODAY);
        times = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS);
        times.when(() -> LocalDateTime.now(KOREA)).thenReturn(now);
    }

    private void mockJdbcReadsAndWrites() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertTrue(sql.contains("FROM study_schedule_settings"));
            Object[] parameters = (Object[]) invocation.getRawArguments()[1];
            if (parameters.length == 3) {
                LocalDate from = ((Date) parameters[1]).toLocalDate();
                LocalDate until = ((Date) parameters[2]).toLocalDate();
                return settingsRows.entrySet().stream()
                        .filter(e -> !e.getKey().isBefore(from) && e.getKey().isBefore(until))
                        .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
            }
            Map<String, Object> row = settingsRows.get(((Date) parameters[1]).toLocalDate());
            return row == null ? List.of() : List.of(row);
        });
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(GOAL_ID));

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object[] parameters = (Object[]) invocation.getRawArguments()[1];
            if (sql.contains("INSERT INTO study_saved_plans")) {
                String[] columns = {"user_id", "goal_id", "plan_date", "source_date", "source_end_time",
                        "plan_status", "start_time", "end_time", "busy_start", "busy_end", "plan_json"};
                assertEquals(columns.length, parameters.length);
                savedPlanRow = new LinkedHashMap<>();
                for (int i = 0; i < columns.length; i++) savedPlanRow.put(columns[i], parameters[i]);
                savedPlanRow.put("saved_at", Timestamp.valueOf(LocalDateTime.of(TODAY, LocalTime.NOON)));
            } else {
                assertTrue(sql.contains("INSERT INTO study_schedule_settings"));
                assertEquals(6, parameters.length);
                LocalDate date = ((Date) parameters[1]).toLocalDate();
                Map<String, Object> row = settingsRow(date, "00:00", "01:00", "", "");
                String[] columns = {"start_time", "end_time", "busy_start", "busy_end"};
                for (int i = 0; i < columns.length; i++) row.put(columns[i], parameters[i + 2]);
                settingsRows.put(date, row);
            }
            return 1;
        });

        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object[] parameters = (Object[]) invocation.getRawArguments()[2];
            Map<String, Object> row;
            if (sql.contains("FROM study_saved_plans")) {
                if (sql.contains("plan_status = 'FINAL'")) {
                    assertTrue(sql.contains("user_id = ? AND goal_id = ?"));
                    assertEquals(1L,parameters[0]); assertEquals(GOAL_ID,parameters[1]);
                    Map<LocalDate,Map<String,Object>> all=new java.util.TreeMap<>(historicalPlans);
                    if(savedPlanRow!=null) all.put(((Date)savedPlanRow.get("plan_date")).toLocalDate(),savedPlanRow);
                    LocalDate from=parameters.length==4?((Date)parameters[2]).toLocalDate():null;
                    LocalDate until=((Date)parameters[parameters.length-1]).toLocalDate();
                    List<Object> result=new java.util.ArrayList<>();
                    RowMapper<?> reader=invocation.getArgument(1);
                    for(var entry:all.entrySet()) {
                        if((from==null || !entry.getKey().isBefore(from)) && entry.getKey().isBefore(until)
                                && "FINAL".equals(entry.getValue().get("plan_status")))
                            result.add(reader.mapRow(resultSet(entry.getValue()),result.size()));
                    }
                    return result;
                }
                row = savedPlanRow;
                if (row != null) {
                    LocalDate date = ((Date) row.get("plan_date")).toLocalDate();
                    if (parameters.length == 4) {
                        if (!"FINAL".equals(row.get("plan_status"))
                                || date.isBefore(((Date) parameters[2]).toLocalDate())
                                || !date.isBefore(((Date) parameters[3]).toLocalDate())) return List.of();
                    } else if (!parameters[2].equals(row.get("plan_date"))) return List.of();
                }
            } else {
                assertTrue(sql.contains("FROM study_schedule_settings"));
                row = settingsRows.get(((Date) parameters[1]).toLocalDate());
            }
            if (row == null) return List.of();
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet(row), 0));
        });
    }

    private Map<String, Object> settingsRow(LocalDate date, String start, String end,
                                             String busyStart, String busyEnd) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schedule_id", 1L);
        row.put("schedule_date", Date.valueOf(date));
        row.put("start_time", Time.valueOf(LocalTime.parse(start)));
        row.put("end_time", Time.valueOf(LocalTime.parse(end)));
        row.put("busy_start", busyStart.isEmpty() ? null : Time.valueOf(LocalTime.parse(busyStart)));
        row.put("busy_end", busyEnd.isEmpty() ? null : Time.valueOf(LocalTime.parse(busyEnd)));
        row.put("updated_at", Timestamp.valueOf(LocalDateTime.of(TODAY, LocalTime.NOON)));
        return row;
    }

    private ResultSet resultSet(Map<String, Object> row) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getString(anyString())).thenAnswer(invocation -> (String) row.get(invocation.getArgument(0)));
        when(result.getDate(anyString())).thenAnswer(invocation -> (Date) row.get(invocation.getArgument(0)));
        when(result.getTime(anyString())).thenAnswer(invocation -> (Time) row.get(invocation.getArgument(0)));
        when(result.getTimestamp(anyString())).thenAnswer(invocation -> (Timestamp) row.get(invocation.getArgument(0)));
        when(result.getLong(anyString())).thenAnswer(invocation -> ((Number) row.get(invocation.getArgument(0))).longValue());
        return result;
    }
}
