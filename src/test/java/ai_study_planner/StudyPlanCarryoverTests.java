package ai_study_planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.*;
import java.sql.Date;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StudyPlanCarryoverTests {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final Map<LocalDate, Map<String, Object>> rows = new TreeMap<>();
    private final List<Map<String, Object>> settings = new ArrayList<>();
    private final List<StudyLog> logs = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private StudyPlanController controller;
    private Goal goal;
    private GoalRepository goals;
    private SubjectRepository subjects;
    private Subject os;
    private MockedStatic<LocalDate> dates;
    private MockedStatic<LocalDateTime> times;

    @BeforeEach void setUp() throws Exception {
        // No Spring context, datasource, application properties, or network.
        jdbc = mock(JdbcTemplate.class);
        goals = mock(GoalRepository.class);
        subjects = mock(SubjectRepository.class);
        StudyLogRepository records = mock(StudyLogRepository.class);
        goal = new Goal();
        goal.setGoalId(10L); goal.setUserId(1L); goal.setDailyStudyTime(120);
        goal.setExamDate(TODAY.plusDays(30));
        Subject subject = new Subject();
        subject.setGoalId(10L); subject.setSubjectId(100L); subject.setUserId(1L);
        subject.setSubjectName("Test subject");
        os = subject;
        when(goals.findAll()).thenReturn(List.of(goal));
        when(subjects.findAll()).thenReturn(List.of(subject));
        when(records.findAll()).thenAnswer(i -> new ArrayList<>(logs));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(i -> {
            Object[] p = (Object[]) i.getRawArguments()[1];
            if (p.length == 3) {
                LocalDate from = ((Date) p[1]).toLocalDate(), until = ((Date) p[2]).toLocalDate();
                return settings.stream().filter(row -> {
                    LocalDate day = ((Date) row.get("schedule_date")).toLocalDate();
                    return !day.isBefore(from) && day.isBefore(until);
                }).toList();
            }
            LocalDate date = ((Date) p[1]).toLocalDate();
            return settings.stream().filter(r -> Date.valueOf(date).equals(r.get("schedule_date"))).toList();
        });
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class))).thenAnswer(i -> {
            String sql = i.getArgument(0);
            Object[] p = (Object[]) i.getRawArguments()[2];
            assertEquals(1L, p[0]); assertEquals(10L, p[1]);
            RowMapper<?> reader = i.getArgument(1);
            List<Object> result = new ArrayList<>();
            if (sql.contains("plan_status = 'FINAL'")) {
                assertTrue(sql.contains("plan_status = 'FINAL'"));
                assertTrue(sql.contains("user_id = ? AND goal_id = ?"));
                LocalDate from = p.length == 4 ? ((Date) p[2]).toLocalDate() : null;
                LocalDate until = ((Date) p[p.length - 1]).toLocalDate();
                for (var e : rows.entrySet()) {
                    var row = e.getValue();
                    if ((from == null || !e.getKey().isBefore(from)) && e.getKey().isBefore(until)
                            && Long.valueOf(1).equals(row.get("user_id"))
                            && "FINAL".equals(row.get("plan_status")) && Long.valueOf(10).equals(row.get("goal_id")))
                        result.add(reader.mapRow(resultSet(row), result.size()));
                }
            } else {
                var row = rows.get(((Date) p[2]).toLocalDate());
                if (row != null && Long.valueOf(10).equals(row.get("goal_id")))
                    result.add(reader.mapRow(resultSet(row), 0));
            }
            return result;
        });
        controller = new StudyPlanController(goals, subjects, records, jdbc, new StudySavedPlanService(jdbc));
        dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS);
        dates.when(() -> LocalDate.now(KOREA)).thenReturn(TODAY);
        times = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS);
        at("22:00");
    }

    @AfterEach void tearDown() {
        if (times != null) times.close();
        if (dates != null) dates.close();
        verify(jdbc, never()).getDataSource();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void firstDayWithoutRecordsIsPendingNotZeroStudy() throws Exception {
        finalPlan(TODAY.minusDays(1), 0, 100);
        Map<String,Object> result = generated();
        assertEquals(0, result.get("carryoverTotalPendingMinutes"));
        assertEquals(List.of(TODAY.minusDays(1).toString()), result.get("unrecordedDates"));
        assertEquals(100, result.get("unrecordedPlannedMinutes"));
        assertEquals(List.of(Map.of("date", TODAY.minusDays(1).toString(),
                "plannedStudyMinutes", 100, "basis", "FINAL")), result.get("unrecordedDays"));
        assertFalse(result.get("rescheduleNotice").toString().contains("["));
        assertFalse(result.get("rescheduleNotice").toString().contains("계획 확인 필요: []"));
        assertEquals(List.of(), result.get("carryoverCalculation"));
    }

    @ParameterizedTest @CsvSource({"40,60", "100,0", "120,0"})
    void partialCompletedAndExceeded(int actual, int expected) throws Exception {
        LocalDate day = TODAY.minusDays(1);
        finalPlan(day, 0, 100); log(day, actual);
        var result = generated();
        assertEquals(expected, result.get("carryoverTotalPendingMinutes"));
        assertStep(result, 0, 0, 0, 100, actual, 0, expected, expected);
    }

    @Test void multipleUnrecordedDaysDoNotAccumulateDeficits() throws Exception {
        for (int i=1; i<=3; i++) finalPlan(TODAY.minusDays(i), 0, 100);
        var result = generated();
        assertEquals(3, ((List<?>)result.get("unrecordedDates")).size());
        assertEquals(300, result.get("unrecordedPlannedMinutes"));
        assertEquals(0, result.get("carryoverTotalPendingMinutes"));
        assertEquals(3, unrecordedDays(result).size());
        assertEquals(300, unrecordedDays(result).stream()
                .mapToInt(day -> (Integer) day.get("plannedStudyMinutes")).sum());
    }

    @Test void mixedDaysPreserveKnownBacklog() throws Exception {
        finalPlan(TODAY.minusDays(2), 0, 100); log(TODAY.minusDays(2), 40);
        finalPlan(TODAY.minusDays(1), 20, 80);
        var result = generated();
        assertEquals(60, result.get("carryoverTotalPendingMinutes"));
        assertEquals(List.of(TODAY.minusDays(1).toString()), result.get("unrecordedDates"));
        assertEquals(1, result.get("carryoverSourceDays"));
    }

    @Test void multiDayLedgerDoesNotCountCatchupTwice() throws Exception {
        finalPlan(TODAY.minusDays(3), 0, 100); log(TODAY.minusDays(3), 40);
        finalPlan(TODAY.minusDays(2), 20, 80); log(TODAY.minusDays(2), 50);
        finalPlan(TODAY.minusDays(1), 30, 70); log(TODAY.minusDays(1), 80);
        var result = generated();
        assertStep(result, 0, 0, 0, 100, 40, 0, 60, 60);
        assertStep(result, 1, 60, 20, 80, 50, 20, 50, 90);
        assertStep(result, 2, 90, 30, 70, 80, 30, 20, 80);
        assertEquals(80, result.get("carryoverTotalPendingMinutes"));
        assertEquals(12, result.get("carryoverMinutes")); // ceil(80 / 7)
        assertEquals(100, result.get("studyMinutes"));
        assertEquals(88, result.get("newStudyMinutes"));
        assertEquals(true, result.get("isScheduleValid"));
        assertEquals(false, result.get("hasConflict"));
    }

    @Test void fullyPerformedCatchupReducesExistingBacklog() throws Exception {
        finalPlan(TODAY.minusDays(2), 0, 100); log(TODAY.minusDays(2), 40);
        finalPlan(TODAY.minusDays(1), 20, 80); log(TODAY.minusDays(1), 100);
        var result = generated();
        assertStep(result, 1, 60, 20, 80, 100, 20, 0, 40);
    }

    @Test void lateCompletionCannotSubtractMoreThanKnownBacklog() throws Exception {
        finalPlan(TODAY.minusDays(2), 0, 100); log(TODAY.minusDays(2), 100);
        // 이 계획은 늦은 기록 입력 전에 저장되어, 이미 해결된 보충 20분을 포함합니다.
        finalPlan(TODAY.minusDays(1), 20, 80); log(TODAY.minusDays(1), 50);
        var result = generated();
        assertStep(result, 1, 0, 20, 80, 50, 0, 50, 50);
        assertEquals(result.get("carryoverCalculation"), generated().get("carryoverCalculation"));
        assertEquals(50, generated().get("carryoverTotalPendingMinutes"));
    }

    @Test void multipleLogsOnSameDayAreSummedOnce() throws Exception {
        LocalDate day = TODAY.minusDays(1);
        finalPlan(day, 0, 100); log(day, 15); log(day, 25);
        var result = generated();
        assertStep(result, 0, 0, 0, 100, 40, 0, 60, 60);
        assertEquals(1, result.get("carryoverSourceDays"));
    }

    @Test void draftWithSettingsAndRecordsUsesLegacyEstimate() throws Exception {
        LocalDate day = TODAY.minusDays(1);
        finalPlan(day, 0, 200);
        rows.get(day).put("plan_status", "DRAFT");
        setting(day); log(day, 40);
        var result = generated();
        assertEquals("SETTINGS_ESTIMATE", steps(result).get(0).get("basis"));
        assertEquals(60, result.get("carryoverTotalPendingMinutes"));
    }

    @Test void finalPlanIgnoresChangedGoalAndTimeSettings() throws Exception {
        LocalDate day = TODAY.minusDays(1);
        finalPlan(day, 0, 100); log(day, 40);
        goal.setDailyStudyTime(240);
        setting(day);
        // 현재 설정으로 재계산하면 공부 200분이 되지만, 과거 FINAL은 100분입니다.
        settings.set(0, Map.of("schedule_date", Date.valueOf(day),
                "start_time", Time.valueOf("19:00:00"), "end_time", Time.valueOf("23:00:00")));
        assertEquals(60, generated().get("carryoverTotalPendingMinutes"));
    }

    @Test void lateRecordsChangePreviewButDoNotRewriteStoredFinal() throws Exception {
        finalPlan(TODAY, 0, 100);
        finalPlan(TODAY.plusDays(1), 7, 93);
        String json = (String)rows.get(TODAY.plusDays(1)).get("plan_json");
        assertEquals(0, controller.previewTomorrowPlan().get("carryoverTotalPendingMinutes"));
        log(TODAY, 40);
        assertEquals(60, controller.previewTomorrowPlan().get("carryoverTotalPendingMinutes"));
        var stored = controller.getStudyPlan(null, null, null, null, TODAY.plusDays(1).toString());
        assertEquals(7, stored.get("carryoverMinutes"));
        assertEquals("FINAL", stored.get("planStatus"));
        assertEquals(true, stored.get("planSaved"));
        assertEquals(json, rows.get(TODAY.plusDays(1)).get("plan_json"));
    }

    @ParameterizedTest @CsvSource({"19:59,0", "20:00,1", "20:01,1"})
    void previewNeverLabelsUnrecordedBeforeEnd(String time, int count) throws Exception {
        finalPlan(TODAY, 0, 100); at(time);
        var result = controller.previewTomorrowPlan();
        assertEquals(count, ((List<?>)result.get("unrecordedDates")).size());
        assertEquals(count, unrecordedDays(result).size());
        assertEquals(0, result.get("carryoverTotalPendingMinutes"));
    }

    @Test void beforeEndPreviewWithRecordsIsExplicitlyProvisional() throws Exception {
        finalPlan(TODAY, 0, 100); log(TODAY, 40); at("19:59");
        var result = controller.previewTomorrowPlan();
        assertEquals(60, result.get("carryoverTotalPendingMinutes"));
        assertEquals(true, steps(result).get(0).get("provisional"));
        assertEquals(List.of(), result.get("unrecordedDates"));
        // A normal request for tomorrow must not confirm today's unfinished deficit.
        assertEquals(0, controller.getStudyPlan("19:00","21:00","","",TODAY.plusDays(1).toString())
                .get("carryoverTotalPendingMinutes"));
    }

    @ParameterizedTest @ValueSource(strings={"carryoverMinutes","newStudyMinutes","scheduleEnd"})
    void recoverableMissingFinalFieldUsesSavedData(String field) throws Exception {
        LocalDate day = TODAY.minusDays(1);
        finalPlan(day, 0, 100); log(day, 40);
        alter(day, p -> p.remove(field));
        assertEquals(60, generated().get("carryoverTotalPendingMinutes"));
    }

    @ParameterizedTest @ValueSource(strings={"both","studyMinutes","invalidSplit","negative","invalidEnd","brokenJson"})
    void incompleteFinalIsFlaggedWithoutGuessing(String kind) throws Exception {
        LocalDate day = TODAY.minusDays(1);
        finalPlan(day, 0, 100); log(day, 40); setting(day);
        alter(day, p -> {
            switch (kind) {
                case "both" -> { p.remove("carryoverMinutes"); p.remove("newStudyMinutes"); }
                case "studyMinutes" -> p.remove("studyMinutes");
                case "invalidSplit" -> p.put("newStudyMinutes", 90);
                case "negative" -> p.put("carryoverMinutes", -1);
                case "invalidEnd" -> p.put("scheduleEnd", "invalid");
            }
        });
        if ("brokenJson".equals(kind)) rows.get(day).put("plan_json", "{broken");
        var result = generated();
        assertEquals(0, result.get("carryoverTotalPendingMinutes"));
        assertEquals(List.of(day.toString()), result.get("carryoverPlanReviewDates"));
    }

    @Test void draftAndOtherGoalAreNotFinalEvidence() throws Exception {
        finalPlan(TODAY.minusDays(1), 0, 100);
        rows.get(TODAY.minusDays(1)).put("plan_status","DRAFT");
        finalPlan(TODAY.minusDays(2), 0, 100);
        rows.get(TODAY.minusDays(2)).put("goal_id", 99L);
        var result = generated();
        assertEquals(List.of(), result.get("unrecordedDates"));
        assertEquals(0, result.get("carryoverTotalPendingMinutes"));
    }

    @Test void otherUserOrSubjectLogsDoNotResolveUnknownDay() throws Exception {
        LocalDate day = TODAY.minusDays(1); finalPlan(day,0,100);
        log(day,40); logs.get(0).setUserId(99L);
        log(day,40); logs.get(1).setSubjectId(999L);
        assertEquals(List.of(day.toString()), generated().get("unrecordedDates"));
    }

    @Test void legacySettingsRetainEstimateWithoutInventingMissingRecords() {
        setting(TODAY.minusDays(3)); // before first record: no goal ownership evidence
        setting(TODAY.minusDays(2)); log(TODAY.minusDays(2),40);
        setting(TODAY.minusDays(1));
        var result = generated();
        assertEquals(60, result.get("carryoverTotalPendingMinutes"));
        assertEquals("SETTINGS_ESTIMATE", steps(result).get(0).get("basis"));
        assertEquals(List.of(TODAY.minusDays(1).toString()), result.get("unrecordedDates"));
    }

    @Test void oldUnrecordedDayRemainsButExamBoundaryStillApplies() throws Exception {
        finalPlan(TODAY.minusDays(15),0,100);
        assertEquals(List.of(TODAY.minusDays(15).toString()), generated().get("unrecordedDates"));
        finalPlan(TODAY.minusDays(1),0,100); log(TODAY.minusDays(1),40);
        goal.setExamDate(TODAY.minusDays(1));
        assertEquals(0, generated().get("carryoverTotalPendingMinutes"));
    }

    @Test void differentFinalDaysKeepTheirActualPlannedMinutes() throws Exception {
        finalPlan(TODAY.minusDays(2), 0, 100);
        finalPlan(TODAY.minusDays(1), 20, 60);
        var result = generated();
        assertEquals(List.of(
                Map.of("date", TODAY.minusDays(2).toString(), "plannedStudyMinutes", 100, "basis", "FINAL"),
                Map.of("date", TODAY.minusDays(1).toString(), "plannedStudyMinutes", 80, "basis", "FINAL")),
                result.get("unrecordedDays"));
        assertEquals(180, result.get("unrecordedPlannedMinutes"));
        assertEquals(0, result.get("carryoverMinutes"));
        assertEquals(0, result.get("carryoverTotalPendingMinutes"));
    }

    @Test void finalAndSettingsDaysKeepSeparateValuesAndBasis() throws Exception {
        // 첫 기록은 설정 추정의 목표 범위 근거만 제공합니다. 이 날의 계획은 없습니다.
        log(TODAY.minusDays(3), 40);
        finalPlan(TODAY.minusDays(2), 0, 80);
        setting(TODAY.minusDays(1)); // 하루 예산120 - 휴식20 = 공부100
        var result = generated();
        assertEquals(List.of(
                Map.of("date", TODAY.minusDays(2).toString(), "plannedStudyMinutes", 80, "basis", "FINAL"),
                Map.of("date", TODAY.minusDays(1).toString(), "plannedStudyMinutes", 100, "basis", "SETTINGS_ESTIMATE")),
                result.get("unrecordedDays"));
        assertEquals(180, result.get("unrecordedPlannedMinutes"));
    }

    @Test void noPendingDaysReturnEmptyDetailsAndNoReviewNotice() throws Exception {
        finalPlan(TODAY.minusDays(1), 0, 100);
        log(TODAY.minusDays(1), 100);
        var result = generated();
        assertEquals(List.of(), result.get("unrecordedDays"));
        assertEquals(List.of(), result.get("unrecordedDates"));
        assertEquals(0, result.get("unrecordedPlannedMinutes"));
        assertFalse(result.get("rescheduleNotice").toString().contains("확인 대상"));
        assertFalse(result.get("rescheduleNotice").toString().contains("[]"));
    }

    @Test void reviewNoticeDoesNotAddAnEmptyRecordDateList() throws Exception {
        LocalDate day = TODAY.minusDays(1);
        finalPlan(day, 0, 100);
        alter(day, plan -> plan.remove("studyMinutes"));
        var result = generated();
        assertEquals(List.of(), result.get("unrecordedDays"));
        assertEquals(List.of(day.toString()), result.get("carryoverPlanReviewDates"));
        assertTrue(result.get("rescheduleNotice").toString().contains("계획 자료 확인 대상은 1일"));
        assertFalse(result.get("rescheduleNotice").toString().contains("[]"));
        assertFalse(result.get("rescheduleNotice").toString().contains("공부 기록 확인 대상"));
    }

    @ParameterizedTest
    @ValueSource(strings={"missingValid", "missingConflict", "stringValid", "stringConflict"})
    void finalValidationFieldsAreNeverAssumed(String kind) throws Exception {
        LocalDate day = TODAY.minusDays(1);
        finalPlan(day, 0, 100);
        alter(day, plan -> {
            switch (kind) {
                case "missingValid" -> plan.remove("isScheduleValid");
                case "missingConflict" -> plan.remove("hasConflict");
                case "stringValid" -> plan.put("isScheduleValid", "true");
                case "stringConflict" -> plan.put("hasConflict", "false");
            }
        });
        var result = generated();
        assertEquals(List.of(day.toString()), result.get("carryoverPlanReviewDates"));
        assertEquals(List.of(), result.get("unrecordedDays"));
        assertEquals(0, result.get("carryoverTotalPendingMinutes"));
    }

    @ParameterizedTest @ValueSource(ints={15,20,30})
    void oldDeficitSurvivesOutsideFormerWindow(int age) throws Exception {
        LocalDate day = TODAY.minusDays(age);
        finalPlan(day, 0, 100); log(day, 40);
        var result = generated();
        assertEquals(60, result.get("carryoverTotalPendingMinutes"));
        assertEquals(9, result.get("carryoverMinutes"));
        assertStep(result, 0, 0, 0, 100, 40, 0, 60, 60);
    }

    @Test void movingPastFourteenDaysDoesNotResetBalance() throws Exception {
        LocalDate day = TODAY.minusDays(14);
        finalPlan(day, 0, 100); log(day, 40);
        for (int offset : List.of(0,1,16)) {
            var result = controller.getStudyPlan("19:00","21:00","","",TODAY.plusDays(offset).toString());
            assertEquals(60, result.get("carryoverTotalPendingMinutes"));
            assertEquals(1, steps(result).size());
        }
    }

    @Test void longHistoryLedgerCountsEachDayOnceAndHoldsUnrecordedDay() throws Exception {
        finalPlan(TODAY.minusDays(20),0,100); log(TODAY.minusDays(20),40);
        finalPlan(TODAY.minusDays(18),20,80); log(TODAY.minusDays(18),50);
        finalPlan(TODAY.minusDays(2),30,70); log(TODAY.minusDays(2),80);
        finalPlan(TODAY.minusDays(1),12,88);
        var result = generated();
        assertStep(result,0,0,0,100,40,0,60,60);
        assertStep(result,1,60,20,80,50,20,50,90);
        assertStep(result,2,90,30,70,80,30,20,80);
        assertEquals(80,result.get("carryoverTotalPendingMinutes"));
        assertEquals(12,result.get("carryoverMinutes"));
        assertEquals(88,result.get("newStudyMinutes"));
        assertEquals(100,result.get("unrecordedPlannedMinutes"));
        assertEquals(result.get("carryoverCalculation"),generated().get("carryoverCalculation"));
    }

    @Test void completedOldBalanceStaysZeroWithoutReaddingSavedTotals() throws Exception {
        finalPlan(TODAY.minusDays(30),0,100); log(TODAY.minusDays(30),40);
        finalPlan(TODAY.minusDays(20),60,40); log(TODAY.minusDays(20),100);
        alter(TODAY.minusDays(20), p -> {
            p.put("carryoverTotalPendingMinutes",999); p.put("carryoverRemainingMinutes",939);
        });
        finalPlan(TODAY.minusDays(1),0,100); log(TODAY.minusDays(1),100);
        assertEquals(0,generated().get("carryoverTotalPendingMinutes"));
        assertEquals(0,generated().get("carryoverTotalPendingMinutes"));
    }

    @Test void lateOldLogsRecalculateWithoutChangingFinal() throws Exception {
        LocalDate old = TODAY.minusDays(30);
        finalPlan(old,0,100);
        finalPlan(TODAY.minusDays(2),20,80); log(TODAY.minusDays(2),100);
        String original = (String) rows.get(old).get("plan_json");
        assertEquals(0,generated().get("carryoverTotalPendingMinutes"));
        log(old,40);
        assertEquals(40,generated().get("carryoverTotalPendingMinutes"));
        log(old,60);
        assertEquals(0,generated().get("carryoverTotalPendingMinutes"));
        assertEquals(original,rows.get(old).get("plan_json"));
    }

    @Test void oldUnknownDaysRetainExactTimesAndDoNotBecomeDebt() throws Exception {
        finalPlan(TODAY.minusDays(30),0,80);
        finalPlan(TODAY.minusDays(20),0,100);
        var result = generated();
        assertEquals(0,result.get("carryoverTotalPendingMinutes"));
        assertEquals(180,result.get("unrecordedPlannedMinutes"));
        assertEquals(List.of(80,100),unrecordedDays(result).stream()
                .map(d -> d.get("plannedStudyMinutes")).toList());
        assertEquals(List.of("FINAL","FINAL"),unrecordedDays(result).stream()
                .map(d -> d.get("basis")).toList());
    }

    @Test void oldSettingsStillStartAtFirstEligibleLogAndUseEstimate() throws Exception {
        setting(TODAY.minusDays(40)); // no ownership evidence
        setting(TODAY.minusDays(30)); log(TODAY.minusDays(30),40);
        setting(TODAY.minusDays(20));
        var result = generated();
        assertEquals(60,result.get("carryoverTotalPendingMinutes"));
        assertEquals("SETTINGS_ESTIMATE",steps(result).get(0).get("basis"));
        assertEquals(List.of(TODAY.minusDays(20).toString()),result.get("unrecordedDates"));
    }

    @Test void goalStartExcludesEarlierFinalLogsAndSettings() throws Exception {
        goal.setCreatedAt(TODAY.minusDays(20).atTime(12,0));
        finalPlan(TODAY.minusDays(30),0,100); log(TODAY.minusDays(30),40);
        setting(TODAY.minusDays(25));
        setting(TODAY.minusDays(19)); // before first eligible log
        finalPlan(TODAY.minusDays(18),0,100); log(TODAY.minusDays(18),40);
        var result = generated();
        assertEquals(60,result.get("carryoverTotalPendingMinutes"));
        assertEquals(1,steps(result).size());
        assertEquals(List.of(),result.get("unrecordedDates"));
    }

    @Test void oldDraftBrokenFinalAndForeignGoalOrUserStaySeparated() throws Exception {
        LocalDate broken=TODAY.minusDays(30), draft=TODAY.minusDays(25);
        finalPlan(broken,0,100); log(broken,40); setting(broken);
        rows.get(broken).put("plan_json","{broken");
        finalPlan(draft,0,200); rows.get(draft).put("plan_status","DRAFT");
        setting(draft); log(draft,40);
        finalPlan(TODAY.minusDays(22),0,100); rows.get(TODAY.minusDays(22)).put("goal_id",99L);
        finalPlan(TODAY.minusDays(21),0,100); rows.get(TODAY.minusDays(21)).put("user_id",99L);
        Goal foreign = new Goal(); foreign.setGoalId(999L); foreign.setUserId(99L);
        when(goals.findAll()).thenReturn(List.of(goal,foreign));
        var result=generated();
        assertEquals(10L,result.get("goalId"));
        assertEquals(60,result.get("carryoverTotalPendingMinutes"));
        assertEquals(List.of(broken.toString()),result.get("carryoverPlanReviewDates"));
        assertEquals("SETTINGS_ESTIMATE",steps(result).get(0).get("basis"));
        assertEquals(List.of(),result.get("unrecordedDates"));
    }

    @Test void oldMultipleLogsSumOnceAndNoPlanDoesNotInventDebt() throws Exception {
        LocalDate old=TODAY.minusDays(30);
        finalPlan(old,0,100); log(old,15); log(old,25);
        log(TODAY.minusDays(29),1); // no FINAL and no settings
        log(TODAY.plusDays(1),1); // future logs cannot seed history
        var result=generated();
        assertStep(result,0,0,0,100,40,0,60,60);
        assertEquals(1,steps(result).size());
        assertEquals(List.of(),result.get("unrecordedDates"));
    }

    @Test void longHistoryUsesOneAggregationPassAndMeasuresResponseSize() throws Exception {
        int dayCount=730;
        class CountedLog extends StudyLog {
            int dateReads;
            @Override public LocalDate getStudyDate() { dateReads++; return super.getStudyDate(); }
        }
        List<CountedLog> counted = new ArrayList<>();
        for(int i=dayCount;i>=1;i--) {
            LocalDate day=TODAY.minusDays(i); finalPlan(day,0,100);
            for(int j=0;j<2;j++) {
                CountedLog log=new CountedLog(); log.setUserId(1L); log.setSubjectId(100L);
                log.setStudyDate(day); log.setStudyMinutes(20); logs.add(log); counted.add(log);
            }
        }
        long start=System.nanoTime();
        var result=generated();
        long elapsedMs=(System.nanoTime()-start)/1_000_000;
        assertEquals(dayCount*60,result.get("carryoverTotalPendingMinutes"));
        assertEquals(dayCount,steps(result).size());
        assertEquals(dayCount*2,counted.stream().mapToInt(l -> l.dateReads).sum());
        assertTrue(((Integer)result.get("carryoverMinutes"))<=100);
        assertEquals(true,result.get("isScheduleValid"));
        Map<String,Object> jsonResult=new LinkedHashMap<>(result);
        jsonResult.replaceAll((key,value) -> value instanceof java.time.temporal.TemporalAccessor
                ? value.toString() : value);
        byte[] bytes=mapper.writeValueAsBytes(jsonResult);
        assertTrue(bytes.length<1_000_000,"730-day response unexpectedly exceeds 1 MB");
        System.out.printf("LONG_HISTORY_METRIC days=%d logs=%d dateReads=%d elapsedMs=%d responseBytes=%d%n",
                dayCount,logs.size(),counted.stream().mapToInt(l -> l.dateReads).sum(),elapsedMs,bytes.length);
        verify(jdbc, times(1)).query(contains("plan_status = 'FINAL'"),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),any(Object[].class));
        verify(jdbc, times(2)).query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),any(Object[].class));
    }

    @Test void osDeficitIsNeverAllocatedToHigherPriorityNetwork() throws Exception {
        twoSubjects();
        subjectFinal(TODAY.minusDays(20),0,80,0,20);
        log(TODAY.minusDays(20),20); logSubject(TODAY.minusDays(20),200L,20);
        var result=generated();
        assertEquals(60,result.get("carryoverTotalPendingMinutes"));
        assertEquals(0,result.get("carryoverUnattributedMinutes"));
        assertEquals(9,subjectPlan(result,100L).get("carryoverMinutes"));
        assertEquals(0,subjectPlan(result,200L).get("carryoverMinutes"));
        assertScheduleTotals(result);
    }

    @Test void twoDeficitsShareThirteenMinutesAsNineAndFour() throws Exception {
        twoSubjects(); subjectFinal(TODAY.minusDays(1),0,80,0,50);
        log(TODAY.minusDays(1),20); logSubject(TODAY.minusDays(1),200L,20);
        var result=generated();
        assertEquals(90,result.get("carryoverTotalPendingMinutes"));
        assertEquals(13,result.get("carryoverMinutes"));
        assertEquals(9,subjectPlan(result,100L).get("carryoverMinutes"));
        assertEquals(4,subjectPlan(result,200L).get("carryoverMinutes"));
        assertEquals(87,result.get("newStudyMinutes"));
        assertScheduleTotals(result);
    }

    @Test void networkExcessDoesNotEraseOsDeficit() throws Exception {
        twoSubjects(); subjectFinal(TODAY.minusDays(1),0,80,0,60);
        log(TODAY.minusDays(1),20); logSubject(TODAY.minusDays(1),200L,120);
        var result=generated();
        assertEquals(60,result.get("carryoverTotalPendingMinutes"));
        assertEquals(60,steps(result).get(0).get("missingNewMinutes"));
        assertEquals("SUBJECT",steps(result).get(0).get("calculationMode"));
        assertScheduleTotals(result);
    }

    @Test void missingSubjectRecordHoldsItsPreviousBalanceAndNewDeficit() throws Exception {
        twoSubjects(); subjectFinal(TODAY.minusDays(20),0,80,0,20);
        log(TODAY.minusDays(20),20); logSubject(TODAY.minusDays(20),200L,20);
        subjectFinal(TODAY.minusDays(1),20,60,0,20);
        logSubject(TODAY.minusDays(1),200L,20);
        var result=generated();
        assertEquals(60,result.get("carryoverTotalPendingMinutes"));
        assertEquals(0,steps(result).get(1).get("creditedCatchupMinutes"));
        assertEquals(0,steps(result).get(1).get("missingNewMinutes"));
        assertEquals(List.of(),result.get("unrecordedDates"));
        assertEquals(List.of(Map.of("date",TODAY.minusDays(1).toString(),"subjectId",100L,
                "subjectName","운영체제","plannedStudyMinutes",80,"basis","FINAL")),result.get("unrecordedSubjects"));
    }

    @ParameterizedTest @CsvSource({"19:59,0", "20:00,1", "20:01,1"})
    void partialSubjectNoticeRespectsEndBoundary(String time,int expected) throws Exception {
        twoSubjects(); subjectFinal(TODAY,0,80,0,20); log(TODAY,20); at(time);
        var result=controller.previewTomorrowPlan();
        assertEquals(expected,((List<?>)result.get("unrecordedSubjects")).size());
        assertEquals(60,result.get("carryoverTotalPendingMinutes"));
        assertEquals(expected==0,steps(result).get(0).get("provisional"));
    }

    @ParameterizedTest @ValueSource(strings={"missingPlans","missingId","missingSplit","settings"})
    void unattributedSixtyIsPreservedAndNeverAllocated(String kind) throws Exception {
        LocalDate day=TODAY.minusDays(30); log(day,40);
        if("settings".equals(kind)) setting(day);
        else {
            finalPlan(day,0,100);
            alter(day,p -> {
                if("missingPlans".equals(kind))p.remove("subjectPlans");
                if("missingId".equals(kind))p.put("subjectPlans",List.of(Map.of(
                        "studyMinutes",100,"carryoverMinutes",0,"newStudyMinutes",100)));
                if("missingSplit".equals(kind))p.put("subjectPlans",List.of(Map.of("subjectId",100L,"studyMinutes",100)));
            });
        }
        var result=generated();
        assertEquals(60,result.get("carryoverTotalPendingMinutes"));
        assertEquals(60,result.get("carryoverUnattributedMinutes"));
        assertEquals(0,result.get("carryoverAllocatableMinutes"));
        assertEquals(0,result.get("carryoverMinutes"));
        assertEquals(100,result.get("newStudyMinutes"));
        assertEquals(List.of(),result.get("carryoverBySubject"));
        assertEquals(List.of(),result.get("unrecordedDates"));
        assertEquals(List.of(day.toString()),result.get("carryoverAttributionReviewDates"));
        assertScheduleTotals(result);
    }

    @Test void knownSixtyAndUnknownThirtyHaveOneTotalAndNineAllocated() throws Exception {
        LocalDate unknown=TODAY.minusDays(30), known=TODAY.minusDays(20);
        finalPlan(unknown,0,40); alter(unknown,p -> p.remove("subjectPlans")); log(unknown,10);
        finalPlan(known,0,80); log(known,20);
        var result=generated();
        assertEquals(90,result.get("carryoverTotalPendingMinutes"));
        assertEquals(30,result.get("carryoverUnattributedMinutes"));
        assertEquals(60,result.get("carryoverAllocatableMinutes"));
        assertEquals(9,result.get("carryoverMinutes"));
        assertEquals(91,result.get("newStudyMinutes"));
        assertScheduleTotals(result);
    }

    @Test void ambiguousRepaymentConvertsRemainderOnceToUnattributed() throws Exception {
        finalPlan(TODAY.minusDays(30),0,100); log(TODAY.minusDays(30),40);
        finalPlan(TODAY.minusDays(20),20,80); log(TODAY.minusDays(20),100);
        alter(TODAY.minusDays(20),p -> p.remove("subjectPlans"));
        var result=generated();
        assertEquals(40,result.get("carryoverTotalPendingMinutes"));
        assertEquals(40,result.get("carryoverUnattributedMinutes"));
        assertEquals(0,result.get("carryoverAllocatableMinutes"));
        assertEquals(0,result.get("carryoverMinutes"));
        assertStep(result,1,60,20,80,100,20,0,40);
        assertEquals(result.get("carryoverCalculation"),generated().get("carryoverCalculation"));
    }

    @Test void subjectRepaymentDoesNotConsumeUnknownBalance() throws Exception {
        finalPlan(TODAY.minusDays(30),0,100); alter(TODAY.minusDays(30),p -> p.remove("subjectPlans"));
        log(TODAY.minusDays(30),40);
        finalPlan(TODAY.minusDays(1),20,80); log(TODAY.minusDays(1),100);
        var result=generated();
        assertEquals(60,result.get("carryoverUnattributedMinutes"));
        assertEquals(60,result.get("carryoverTotalPendingMinutes"));
        assertEquals(0,steps(result).get(1).get("creditedCatchupMinutes"));
    }

    @ParameterizedTest @ValueSource(strings={"duplicate","foreignId","stringId","fractionId","negative",
            "sumMismatch","invalidSplit","wrongType"})
    void contradictorySubjectEvidenceIsHeldWithoutFallback(String kind) throws Exception {
        LocalDate day=TODAY.minusDays(20); finalPlan(day,0,100); setting(day); log(day,40);
        alter(day,p -> {
            Map<String,Object> item=new LinkedHashMap<>(Map.of("subjectId",100L,"studyMinutes",100,
                    "carryoverMinutes",0,"newStudyMinutes",100));
            switch(kind) {
                case "foreignId" -> item.put("subjectId",999L);
                case "stringId" -> item.put("subjectId","100");
                case "fractionId" -> item.put("subjectId",100.5);
                case "negative" -> item.put("studyMinutes",-1);
                case "sumMismatch" -> {item.put("studyMinutes",90);item.put("newStudyMinutes",90);}
                case "invalidSplit" -> item.put("newStudyMinutes",90);
            }
            p.put("subjectPlans","wrongType".equals(kind)?"invalid":
                    "duplicate".equals(kind)?List.of(item,item):List.of(item));
        });
        var result=generated();
        assertEquals(0,result.get("carryoverTotalPendingMinutes"));
        assertEquals(List.of(day.toString()),result.get("carryoverAttributionReviewDates"));
        assertEquals(List.of(),result.get("carryoverCalculation"));
    }

    @Test void catchupOnlySubjectIsIncludedOutsideNewSelectionAndAllNewCanBeZero() throws Exception {
        twoSubjects(); finalPlan(TODAY.minusDays(1),0,100); log(TODAY.minusDays(1),40);
        var small=controller.getStudyPlan("19:00","19:36","","",TODAY.toString());
        assertEquals(0,subjectPlan(small,100L).get("newStudyMinutes"));
        assertEquals(9,subjectPlan(small,100L).get("carryoverMinutes"));
        assertEquals(27,subjectPlan(small,200L).get("newStudyMinutes"));
        assertEquals(0,small.get("unassignedSubjectCount"));
        assertScheduleTotals(small);
        var only=controller.getStudyPlan("19:00","19:05","","",TODAY.toString());
        assertEquals(0,only.get("newStudyMinutes"));
        assertEquals(5,subjectPlan(only,100L).get("carryoverMinutes"));
        assertEquals(1,((List<?>)only.get("subjectPlans")).size());
        assertScheduleTotals(only);
    }

    @Test void splitAcrossRestAndBusyPreservesSubjectAndAllocationType() throws Exception {
        twoSubjects(); subjectFinal(TODAY.minusDays(1),0,400,0,400);
        // 과거 공부800+휴식160이 실제960분 창에 들어가는 FINAL을 사용합니다.
        alter(TODAY.minusDays(1), p -> {
            p.put("scheduleStart",TODAY.minusDays(1)+" 07:00");
            p.put("scheduleEnd",TODAY.minusDays(1)+" 23:00");
            p.put("dailyStudyTime",960);p.put("breakMinutes",160);p.put("totalPlannedMinutes",960);
        });
        log(TODAY.minusDays(1),1); logSubject(TODAY.minusDays(1),200L,1);
        var result=controller.getStudyPlan("19:00","22:00","19:20","19:25",TODAY.toString());
        assertEquals(0,result.get("newStudyMinutes"));
        assertEquals(100,result.get("carryoverMinutes"));
        assertTrue(((List<?>)result.get("timeSlots")).size()>3);
        assertScheduleTotals(result);
        assertTrue(slots(result).stream().filter(s -> "공부".equals(s.get("type")))
                .allMatch(s -> "CATCHUP".equals(s.get("allocationType"))));
    }

    @Test void oneMinuteTieIsStableAndNeverExceedsSubjectBalance() throws Exception {
        twoSubjects(); subjectFinal(TODAY.minusDays(1),0,2,0,2);
        log(TODAY.minusDays(1),1); logSubject(TODAY.minusDays(1),200L,1);
        var result=generated();
        assertEquals(1,result.get("carryoverMinutes"));
        assertEquals(1,subjectPlan(result,100L).get("carryoverMinutes"));
        assertEquals(0,subjectPlan(result,200L).get("carryoverMinutes"));
        assertScheduleTotals(result);
    }

    @Test void lateSubjectRecordChangesNewCalculationWithoutRewritingFinal() throws Exception {
        twoSubjects(); LocalDate day=TODAY.minusDays(30); subjectFinal(day,0,80,0,20);
        logSubject(day,200L,20); String original=(String)rows.get(day).get("plan_json");
        assertEquals(0,generated().get("carryoverTotalPendingMinutes"));
        log(day,20); assertEquals(60,generated().get("carryoverTotalPendingMinutes"));
        log(day,60); assertEquals(0,generated().get("carryoverTotalPendingMinutes"));
        assertEquals(original,rows.get(day).get("plan_json"));
    }

    @Test void otherSubjectsCatchupCannotRepayOsBalance() throws Exception {
        twoSubjects(); finalPlan(TODAY.minusDays(30),0,100); log(TODAY.minusDays(30),40);
        subjectFinal(TODAY.minusDays(1),0,0,20,80); logSubject(TODAY.minusDays(1),200L,100);
        var result=generated();
        assertEquals(60,result.get("carryoverTotalPendingMinutes"));
        assertEquals(0,result.get("carryoverUnattributedMinutes"));
        assertEquals(0,steps(result).get(1).get("creditedCatchupMinutes"));
        assertEquals(9,subjectPlan(result,100L).get("carryoverMinutes"));
        assertScheduleTotals(result);
    }

    @Test void allLegacyDaysKeepOriginalLedgerButHaveNoAutomaticCatchup() throws Exception {
        finalPlan(TODAY.minusDays(30),0,100);log(TODAY.minusDays(30),40);
        finalPlan(TODAY.minusDays(20),20,80);log(TODAY.minusDays(20),50);
        finalPlan(TODAY.minusDays(1),30,70);log(TODAY.minusDays(1),80);
        for(LocalDate day:List.of(TODAY.minusDays(30),TODAY.minusDays(20),TODAY.minusDays(1)))
            alter(day,p -> p.remove("subjectPlans"));
        var result=generated();
        assertStep(result,0,0,0,100,40,0,60,60);
        assertStep(result,1,60,20,80,50,20,50,90);
        assertStep(result,2,90,30,70,80,30,20,80);
        assertEquals(80,result.get("carryoverTotalPendingMinutes"));
        assertEquals(80,result.get("carryoverUnattributedMinutes"));
        assertEquals(0,result.get("carryoverMinutes"));
        assertEquals(100,result.get("newStudyMinutes"));
        assertScheduleTotals(result);
    }

    private void twoSubjects() {
        os.setSubjectName("운영체제");
        Subject network=new Subject(); network.setUserId(1L);network.setGoalId(10L);network.setSubjectId(200L);
        network.setSubjectName("네트워크");network.setPriorityLevel("높음");
        when(subjects.findAll()).thenReturn(List.of(os,network));
    }
    private void logSubject(LocalDate day,long id,int minutes) {
        log(day,minutes);logs.get(logs.size()-1).setSubjectId(id);
    }
    private void subjectFinal(LocalDate day,int osCatchup,int osNew,int netCatchup,int netNew) throws Exception {
        finalPlan(day,osCatchup+netCatchup,osNew+netNew);
        alter(day,p -> p.put("subjectPlans",List.of(
                Map.of("subjectId",100L,"studyMinutes",osCatchup+osNew,"carryoverMinutes",osCatchup,"newStudyMinutes",osNew),
                Map.of("subjectId",200L,"studyMinutes",netCatchup+netNew,"carryoverMinutes",netCatchup,"newStudyMinutes",netNew))));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> subjectPlan(Map<String,Object> result,long id) {
        return ((List<Map<String,Object>>)result.get("subjectPlans")).stream()
                .filter(p -> Long.valueOf(id).equals(p.get("subjectId"))).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> slots(Map<String,Object> result) {
        return (List<Map<String,Object>>)result.get("timeSlots");
    }
    @SuppressWarnings("unchecked") private void assertScheduleTotals(Map<String,Object> result) {
        List<Map<String,Object>> plans=(List<Map<String,Object>>)result.get("subjectPlans");
        for(Map<String,Object> plan:plans) {
            long id=((Number)plan.get("subjectId")).longValue(); int study=0,catchup=0,fresh=0;
            for(Map<String,Object> slot:slots(result)) {
                if(!"공부".equals(slot.get("type")) || !Long.valueOf(id).equals(slot.get("subjectId")))continue;
                int m=(Integer)slot.get("minutes");study+=m;
                if("CATCHUP".equals(slot.get("allocationType")))catchup+=m;
                else {assertEquals("NEW",slot.get("allocationType"));fresh+=m;}
            }
            assertEquals(plan.get("studyMinutes"),study);assertEquals(plan.get("carryoverMinutes"),catchup);
            assertEquals(plan.get("newStudyMinutes"),fresh);assertEquals(study,catchup+fresh);
        }
        for(String field:List.of("studyMinutes","carryoverMinutes","newStudyMinutes"))
            assertEquals(result.get(field),plans.stream().mapToInt(p -> (Integer)p.get(field)).sum());
        assertEquals(true,result.get("isScheduleValid"));assertEquals(false,result.get("hasConflict"));
        assertEquals((Integer)result.get("carryoverTotalPendingMinutes"),
                (Integer)result.get("carryoverAllocatableMinutes")+(Integer)result.get("carryoverUnattributedMinutes"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> unrecordedDays(Map<String,Object> result) {
        return (List<Map<String,Object>>) result.get("unrecordedDays");
    }

    private Map<String,Object> generated() {
        return controller.getStudyPlan("19:00","21:00","","",TODAY.toString());
    }
    private void at(String time) {
        LocalDateTime value = LocalDateTime.of(TODAY, LocalTime.parse(time));
        times.when(() -> LocalDateTime.now(KOREA)).thenReturn(value);
    }
    private void log(LocalDate day, int minutes) {
        StudyLog log = new StudyLog(); log.setUserId(1L); log.setSubjectId(100L);
        log.setStudyDate(day); log.setStudyMinutes(minutes); log.setCompleted("완료");
        logs.add(log);
    }
    private void finalPlan(LocalDate day, int catchup, int fresh) throws Exception {
        Map<String,Object> plan = new LinkedHashMap<>();
        plan.put("studyMinutes",catchup+fresh); plan.put("carryoverMinutes",catchup);
        plan.put("newStudyMinutes",fresh); plan.put("scheduleEnd",day+" 20:00");
        plan.put("scheduleStart",day+" 18:00"); plan.put("isScheduleValid",true);
        plan.put("hasConflict",false); plan.put("goalId",10L);
        plan.put("subjectPlans", List.of(Map.of("subjectId",100L,"studyMinutes",catchup+fresh,
                "carryoverMinutes",catchup,"newStudyMinutes",fresh)));
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("plan_date",Date.valueOf(day)); row.put("goal_id",10L);
        row.put("user_id",1L);
        row.put("plan_json",mapper.writeValueAsString(plan)); row.put("plan_status","FINAL");
        row.put("source_date",Date.valueOf(day.minusDays(1)));
        row.put("source_end_time",Time.valueOf("20:00:00"));
        row.put("start_time",Time.valueOf("18:00:00")); row.put("end_time",Time.valueOf("20:00:00"));
        row.put("saved_at",Timestamp.valueOf(day.minusDays(1).atTime(21,0)));
        rows.put(day,row);
    }
    @SuppressWarnings("unchecked")
    private void alter(LocalDate day, java.util.function.Consumer<Map<String,Object>> edit) throws Exception {
        Map<String,Object> plan = mapper.readValue((String)rows.get(day).get("plan_json"),Map.class);
        edit.accept(plan); rows.get(day).put("plan_json",mapper.writeValueAsString(plan));
    }
    private void setting(LocalDate day) {
        settings.add(Map.of("schedule_date",Date.valueOf(day),"start_time",Time.valueOf("19:00:00"),
                "end_time",Time.valueOf("21:00:00")));
    }
    private ResultSet resultSet(Map<String,Object> row) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(anyString())).thenAnswer(i -> (String)row.get(i.getArgument(0)));
        when(rs.getDate(anyString())).thenAnswer(i -> (Date)row.get(i.getArgument(0)));
        when(rs.getTime(anyString())).thenAnswer(i -> (Time)row.get(i.getArgument(0)));
        when(rs.getTimestamp(anyString())).thenAnswer(i -> (Timestamp)row.get(i.getArgument(0)));
        return rs;
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> steps(Map<String,Object> result) {
        return (List<Map<String,Object>>) result.get("carryoverCalculation");
    }
    private void assertStep(Map<String,Object> result, int index, int before, int catchup, int fresh,
                            int actual, int credit, int missing, int after) {
        var step = steps(result).get(index);
        assertEquals(before,step.get("previousBacklogMinutes"));
        assertEquals(catchup,step.get("plannedCatchupMinutes"));
        assertEquals(fresh,step.get("plannedNewMinutes"));
        assertEquals(actual,step.get("actualStudyMinutes"));
        assertEquals(Math.min(catchup,actual),step.get("performedCatchupMinutes"));
        assertEquals(credit,step.get("creditedCatchupMinutes"));
        assertEquals(Math.max(0,actual-Math.min(catchup,actual)),step.get("completedNewMinutes"));
        assertEquals(missing,step.get("missingNewMinutes"));
        assertEquals(after,step.get("nextBacklogMinutes"));
    }
}
