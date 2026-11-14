package review.mobile;

import ai_study_planner.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real rule engine/JPA/JDBC/security, memory H2 and MockMvc only. No socket or AI. */
@SpringBootTest(classes=AiStudyPlannerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.MOCK)
class RuleRescheduleH2Tests {
    static final String PASSWORD=UUID.randomUUID().toString();
    static final Map<String,Object> PROPS=IsolatedStudyLogServer.properties(8099,UUID.randomUUID().toString(),new BCryptPasswordEncoder(12).encode(PASSWORD));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){PROPS.forEach((k,v)->r.add(k,()->v));}
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired GoalRepository goals;
    @Autowired SubjectRepository subjects;
    @Autowired StudyLogRepository logs;
    final ObjectMapper json=new ObjectMapper();
    final ZoneId korea=ZoneId.of("Asia/Seoul");
    IsolatedStudyLogFixture.Seed seed;
    LocalDate today;
    MockMvc mvc;
    MockHttpSession session;
    String token;

    @BeforeEach void setup()throws Exception{
        // Verify the actual connection before even clearing this test's own memory tables.
        IsolatedStudyLogFixture.assertH2(jdbc,(String)PROPS.get("spring.datasource.url"));
        assertThrows(ClassNotFoundException.class,()->Class.forName("com.mysql.cj.jdbc.Driver"));
        logs.deleteAll();subjects.deleteAll();goals.deleteAll();
        jdbc.update("DELETE FROM users");jdbc.update("DELETE FROM study_questions");jdbc.update("DELETE FROM ai_responses");
        jdbc.execute("DROP TABLE IF EXISTS study_saved_plans");jdbc.execute("DROP TABLE IF EXISTS study_schedule_settings");
        seed=IsolatedStudyLogFixture.seed((ConfigurableApplicationContext)context,(String)PROPS.get("spring.datasource.url"));
        today=LocalDate.parse(seed.date());
        // Give the synthetic FINAL the same validation fields required by the real ledger.
        snapshot(seed.goalA(),seed.subjectA(),today,9,91);
        snapshot(seed.goalB(),seed.subjectB(),today,0,100);
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var csrf=mvc.perform(get("/auth/csrf")).andReturn();session=(MockHttpSession)csrf.getRequest().getSession();
        token=json.readTree(csrf.getResponse().getContentAsString()).path("token").asText();
        mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN",token).param("username","mobile-h2-owner").param("password",PASSWORD)).andExpect(status().isOk());
        token=json.readTree(mvc.perform(get("/auth/csrf").session(session)).andReturn().getResponse().getContentAsString()).path("token").asText();
    }
    void snapshot(long goal,long subject,LocalDate date,int catchup,int fresh)throws Exception{
        var p=new LinkedHashMap<String,Object>();
        p.put("goalId",goal);p.put("scheduleDate",date.toString());p.put("studyMinutes",catchup+fresh);
        p.put("carryoverMinutes",catchup);p.put("newStudyMinutes",fresh);p.put("scheduleStart",date+" 10:00");p.put("scheduleEnd",date+" 12:00");
        p.put("isScheduleValid",true);p.put("hasConflict",false);p.put("fixtureSource","SYNTHETIC_H2_ONLY");
        p.put("subjectPlans",List.of(Map.of("subjectId",subject,"studyMinutes",catchup+fresh,"carryoverMinutes",catchup,"newStudyMinutes",fresh)));
        jdbc.update("DELETE FROM study_saved_plans WHERE goal_id=? AND plan_date=?",goal,java.sql.Date.valueOf(date));
        jdbc.update("INSERT INTO study_saved_plans(user_id,goal_id,plan_date,source_date,source_end_time,plan_status,start_time,end_time,plan_json) VALUES(1,?,?,?,'21:00:00','FINAL','10:00:00','12:00:00',?)",goal,java.sql.Date.valueOf(date),java.sql.Date.valueOf(date.minusDays(1)),json.writeValueAsString(p));
    }
    void log(long subject,LocalDate day,int actual){
        var l=new StudyLog();l.setUserId(1L);l.setSubjectId(subject);l.setStudyDate(day);l.setStudyMinutes(actual);l.setStudyContent("가상 규칙 검증");l.setCompleted("완료");l.setFocusScore(3);l.setUnderstandingScore(3);l.setFatigueScore(2);logs.save(l);
    }
    List<Map<String,Object>> rows(){return jdbc.queryForList("SELECT * FROM study_saved_plans ORDER BY goal_id,plan_date");}
    Map<String,Object> read(String path,long goal,LocalDate date,String end)throws Exception{
        var before=rows();var settings=jdbc.queryForList("SELECT * FROM study_schedule_settings ORDER BY schedule_date");
        var records=jdbc.queryForList("SELECT * FROM study_logs ORDER BY log_id");
        var request=get(path).session(session).param("goalId",""+goal);
        if(date!=null)request.param("date",date.toString());
        if(end!=null)request.param("startTime","19:00").param("endTime",end).param("busyStart","").param("busyEnd","");
        var r=mvc.perform(request).andExpect(status().isOk()).andReturn();
        assertEquals(before,rows(),"GET/preview must not modify any FINAL row");
        assertEquals(settings,jdbc.queryForList("SELECT * FROM study_schedule_settings ORDER BY schedule_date"));
        assertEquals(records,jdbc.queryForList("SELECT * FROM study_logs ORDER BY log_id"));
        return json.readValue(r.getResponse().getContentAsString(),Map.class);
    }
    Map<String,Object> calculated(long goal)throws Exception{return read("/study-plan",goal,today,"21:00");}
    void totals(Map<String,Object> p,int pending,int carry,int fresh){
        assertEquals(pending,p.get("carryoverTotalPendingMinutes"));assertEquals(carry,p.get("carryoverMinutes"));assertEquals(fresh,p.get("newStudyMinutes"));
        assertEquals(carry+fresh,p.get("studyMinutes"));assertEquals(true,p.get("isScheduleValid"));assertEquals(false,p.get("hasConflict"));
        var plans=(List<Map<String,Object>>)p.get("subjectPlans");
        for(String key:List.of("studyMinutes","carryoverMinutes","newStudyMinutes"))assertEquals(((Number)p.get(key)).intValue(),plans.stream().mapToInt(row->((Number)row.get(key)).intValue()).sum());
        var slots=(List<Map<String,Object>>)p.get("timeSlots");
        assertEquals(carry,slots.stream().filter(s->"CATCHUP".equals(s.get("allocationType"))).mapToInt(s->((Number)s.get("minutes")).intValue()).sum());
        assertTrue(slots.stream().allMatch(s->s.get("startTime").toString().startsWith(p.get("scheduleDate").toString())));
    }
    @ParameterizedTest @CsvSource({"100,0,0,100","40,60,9,91","140,0,0,100"})
    void completedPartialAndExcess(int actual,int pending,int carry,int fresh)throws Exception{
        snapshot(seed.goalA(),seed.subjectA(),today.minusDays(1),0,100);log(seed.subjectA(),today.minusDays(1),actual);
        var p=calculated(seed.goalA());totals(p,pending,carry,fresh);assertEquals(today.toString(),p.get("scheduleDate"));
    }
    @Test void missingSubjectIsUnknownRatherThanZeroStudy()throws Exception{
        var extra=new Subject();extra.setUserId(1L);extra.setGoalId(seed.goalA());extra.setSubjectName("[가상] 미기록 과목");extra=subjects.save(extra);
        snapshot(seed.goalA(),seed.subjectA(),today.minusDays(1),0,100);
        String raw=jdbc.queryForObject("SELECT plan_json FROM study_saved_plans WHERE goal_id=? AND plan_date=?",String.class,seed.goalA(),java.sql.Date.valueOf(today.minusDays(1)));
        Map<String,Object> p=json.readValue(raw,Map.class);p.put("subjectPlans",List.of(
            Map.of("subjectId",seed.subjectA(),"studyMinutes",60,"carryoverMinutes",0,"newStudyMinutes",60),
            Map.of("subjectId",extra.getSubjectId(),"studyMinutes",40,"carryoverMinutes",0,"newStudyMinutes",40)));
        jdbc.update("UPDATE study_saved_plans SET plan_json=? WHERE goal_id=? AND plan_date=?",json.writeValueAsString(p),seed.goalA(),java.sql.Date.valueOf(today.minusDays(1)));
        log(seed.subjectA(),today.minusDays(1),30);var result=calculated(seed.goalA());totals(result,30,5,95);
        var missing=(List<Map<String,Object>>)result.get("unrecordedSubjects");assertEquals(1,missing.size());assertEquals(extra.getSubjectId().intValue(),((Number)missing.get(0).get("subjectId")).intValue());
        var assigned=(List<Map<String,Object>>)result.get("subjectPlans");assertTrue(assigned.stream().filter(s->((Number)s.get("subjectId")).longValue()==seed.subjectA()).allMatch(s->s.get("carryoverMinutes").equals(5)));
    }
    @Test void nextDayCreditsCatchupOnceAndKeepsTwentyDayHistory()throws Exception{
        snapshot(seed.goalA(),seed.subjectA(),today.minusDays(20),0,100);log(seed.subjectA(),today.minusDays(20),40);
        totals(calculated(seed.goalA()),60,9,91);
        snapshot(seed.goalA(),seed.subjectA(),today.minusDays(1),9,91);log(seed.subjectA(),today.minusDays(1),100);
        totals(calculated(seed.goalA()),51,8,92);
    }
    @Test void shortWindowCapsCatchupWithoutIncreasingBudget()throws Exception{
        snapshot(seed.goalA(),seed.subjectA(),today.minusDays(1),0,100);log(seed.subjectA(),today.minusDays(1),40);
        var p=read("/study-plan",seed.goalA(),today,"19:05");totals(p,60,5,0);assertEquals(55,p.get("carryoverRemainingMinutes"));assertEquals(0,p.get("breakMinutes"));
    }
    @Test void examDayUsesOneDaySpreadAndExpiredGoalDoesNotCarryDebt()throws Exception{
        snapshot(seed.goalA(),seed.subjectA(),today.minusDays(1),0,100);log(seed.subjectA(),today.minusDays(1),40);
        Goal a=goals.findById(seed.goalA()).orElseThrow();a.setExamDate(today);goals.save(a);
        var exam=calculated(seed.goalA());totals(exam,60,60,40);assertEquals(0,((Number)exam.get("daysLeft")).intValue());assertTrue(exam.get("deadlineStatus").toString().contains("오늘"));
        a.setExamDate(today.minusDays(1));goals.save(a);var expired=calculated(seed.goalA());totals(expired,0,0,100);assertTrue(expired.get("deadlineStatus").toString().contains("지났"));
    }
    @Test void goalsAndSavedSnapshotsStaySeparateFromPreview()throws Exception{
        snapshot(seed.goalA(),seed.subjectA(),today.minusDays(1),0,100);log(seed.subjectA(),today.minusDays(1),40);
        snapshot(seed.goalB(),seed.subjectB(),today.minusDays(1),0,100);log(seed.subjectB(),today.minusDays(1),100);
        totals(calculated(seed.goalA()),60,9,91);totals(calculated(seed.goalB()),0,0,100);
        var saved=read("/study-reschedule-saved",seed.goalA(),today,null);assertEquals(true,saved.get("saved"));assertEquals(9,saved.get("carryoverMinutes"));
        var tomorrow=read("/study-reschedule-preview",seed.goalA(),null,null);totals(tomorrow,60,9,91);assertEquals(today.plusDays(1).toString(),tomorrow.get("scheduleDate"));assertEquals(true,tomorrow.get("previewMode"));
        var storedFirst=read("/study-plan",seed.goalA(),today,null);assertEquals(true,storedFirst.get("planSaved"));assertEquals("FINAL",storedFirst.get("planStatus"));
    }
    @Test void onlyExplicitSaveCreatesTomorrowSnapshotAndOtherGoalFinalIsPreserved()throws Exception{
        snapshot(seed.goalA(),seed.subjectA(),today.minusDays(1),0,100);log(seed.subjectA(),today.minusDays(1),40);
        snapshot(seed.goalB(),seed.subjectB(),today.plusDays(1),0,100);var before=rows();
        jdbc.update("INSERT INTO study_schedule_settings(user_id,schedule_date,start_time,end_time) VALUES(1,?,'19:00:00','21:00:00')",java.sql.Date.valueOf(today));
        LocalDateTime afterEnd=today.atTime(23,30);
        try(MockedStatic<LocalDateTime> clock=mockStatic(LocalDateTime.class,CALLS_REAL_METHODS)){
            clock.when(()->LocalDateTime.now(korea)).thenReturn(afterEnd);
            mvc.perform(post("/study-reschedule-save").session(session).param("goalId",""+seed.goalA()).header("X-CSRF-TOKEN",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planStatus").value("FINAL")).andExpect(jsonPath("$.carryoverMinutes").value(9));
        }
        assertEquals(before.size()+1,rows().size());for(var row:before)assertTrue(rows().contains(row),"Existing A/B FINAL snapshots must survive explicit save to another date");
        var saved=read("/study-reschedule-saved",seed.goalA(),today.plusDays(1),null);assertEquals(100,saved.get("studyMinutes"));assertEquals(9,saved.get("carryoverMinutes"));assertEquals(91,saved.get("newStudyMinutes"));assertFalse(saved.containsKey("previewMode"));
    }
    @ParameterizedTest @CsvSource({"10:00,DRAFT","23:30,FINAL"})
    void flutterEmptyBodyContractPreservesServerStatus(String time,String status)throws Exception{
        snapshot(seed.goalA(),seed.subjectA(),today.minusDays(1),0,100);log(seed.subjectA(),today.minusDays(1),40);
        jdbc.update("INSERT INTO study_schedule_settings(user_id,schedule_date,start_time,end_time) VALUES(1,?,'19:00:00','21:00:00')",java.sql.Date.valueOf(today));
        var before=rows();LocalDateTime now=LocalDateTime.of(today,LocalTime.parse(time));
        try(MockedStatic<LocalDateTime> clock=mockStatic(LocalDateTime.class,CALLS_REAL_METHODS)){
            clock.when(()->LocalDateTime.now(korea)).thenReturn(now);
            mvc.perform(post("/study-reschedule-save").session(session).param("goalId",""+seed.goalA()).header("X-CSRF-TOKEN",token)
                    .contentType("application/x-www-form-urlencoded").content(""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.planDate").value(today.plusDays(1).toString()))
                .andExpect(jsonPath("$.planStatus").value(status))
                .andExpect(jsonPath("$.studyMinutes").value(100)).andExpect(jsonPath("$.carryoverMinutes").value(9)).andExpect(jsonPath("$.newStudyMinutes").value(91));
        }
        for(var row:before)assertTrue(rows().contains(row));
        var saved=read("/study-reschedule-saved",seed.goalA(),today.plusDays(1),null);
        assertEquals(status,saved.get("planStatus"));assertEquals(9,saved.get("carryoverMinutes"));
    }
    @Test void absentTodaySettingsRejectsSaveEvenWhenPreviewIsPossible()throws Exception{
        var before=rows();read("/study-reschedule-preview",seed.goalA(),null,null);
        mvc.perform(post("/study-reschedule-save").session(session).param("goalId",""+seed.goalA()).header("X-CSRF-TOKEN",token).content(""))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        assertEquals(before,rows());
    }
    @Test void rescheduleAuthenticationAndCsrfFailureCannotChangeFinals()throws Exception{
        var before=rows();
        mvc.perform(get("/study-reschedule-preview").param("goalId",""+seed.goalA())).andExpect(status().isUnauthorized());
        mvc.perform(get("/study-reschedule-saved").param("goalId",""+seed.goalA())).andExpect(status().isUnauthorized());
        mvc.perform(post("/study-reschedule-save").session(session).param("goalId",""+seed.goalA()).content("")) .andExpect(status().isForbidden());
        mvc.perform(post("/study-reschedule-save").session(session).param("goalId",""+seed.goalA()).header("X-CSRF-TOKEN","invalid").content("")) .andExpect(status().isForbidden());
        assertEquals(before,rows());
    }
    @Test void explicitSameGoalDateOverwriteDoesNotTouchOtherFinals()throws Exception{
        snapshot(seed.goalA(),seed.subjectA(),today.minusDays(1),0,100);log(seed.subjectA(),today.minusDays(1),40);
        snapshot(seed.goalA(),seed.subjectA(),today.plusDays(1),0,100);snapshot(seed.goalB(),seed.subjectB(),today.plusDays(1),0,100);
        jdbc.update("INSERT INTO study_schedule_settings(user_id,schedule_date,start_time,end_time) VALUES(1,?,'19:00:00','21:00:00')",java.sql.Date.valueOf(today));
        var before=rows();read("/study-reschedule-preview",seed.goalA(),null,null);
        LocalDateTime now=today.atTime(23,30);
        try(MockedStatic<LocalDateTime> clock=mockStatic(LocalDateTime.class,CALLS_REAL_METHODS)){
            clock.when(()->LocalDateTime.now(korea)).thenReturn(now);
            mvc.perform(post("/study-reschedule-save").session(session).param("goalId",""+seed.goalA()).header("X-CSRF-TOKEN",token).content(""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.carryoverMinutes").value(9));
        }
        assertEquals(before.size(),rows().size());
        for(var row:before){
            if(((Number)row.get("goal_id")).longValue()==seed.goalA()&&row.get("plan_date").toString().equals(today.plusDays(1).toString()))continue;
            assertTrue(rows().contains(row),"Only the explicitly saved A/tomorrow row may change");
        }
        var stored=read("/study-reschedule-saved",seed.goalA(),today.plusDays(1),null);assertEquals(9,stored.get("carryoverMinutes"));assertEquals(91,stored.get("newStudyMinutes"));
    }
}
