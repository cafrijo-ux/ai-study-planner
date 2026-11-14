package ai_study_planner;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 DataSource/애플리케이션 컨텍스트가 없는 회귀 테스트. 롤백은 격리 MySQL에서 별도 검증합니다. */
class GoalWorkflowTests {
    GoalRepository goals; SubjectRepository subjects; JdbcTemplate jdbc;
    GoalRegistrationService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        goals=mock(GoalRepository.class); subjects=mock(SubjectRepository.class); jdbc=mock(JdbcTemplate.class);
        service=new GoalRegistrationService(goals,subjects,jdbc);
        when(jdbc.queryForList("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE",Long.class,1L)).thenReturn(List.of(1L));
        when(goals.saveAndFlush(any())).thenAnswer(i->{Goal g=i.getArgument(0);g.setGoalId(8L);return g;});
        when(subjects.saveAndFlush(any())).thenAnswer(i->{Subject s=i.getArgument(0);s.setSubjectId(9L);return s;});
        mvc=MockMvcBuilders.standaloneSetup(new GoalRegistrationController(service)).build();
    }
    static Goal goal(long id) {
        Goal g=new Goal(); if(id>0)g.setGoalId(id);g.setUserId(1L);g.setGoalName("시험");g.setGoalType("시험 대비");
        g.setExamDate(LocalDate.of(2026,11,12));g.setTargetScore("80");g.setCurrentLevel("보통");g.setWeakPoint("개념");g.setDailyStudyTime(120);g.setPreferredStudyMethod("복습");return g;
    }
    static Subject subject() {
        Subject s=new Subject();s.setSubjectName("운영체제");s.setDifficulty("보통");s.setPriorityLevel("높음");s.setCurrentConfidence("보통");return s;
    }
    static String payload() { return """
        {"goal":{"userId":1,"goalName":"시험","goalType":"시험 대비","examDate":"2026-11-12","targetScore":"80","currentLevel":"보통","weakPoint":"개념","dailyStudyTime":120,"preferredStudyMethod":"복습"},"subject":{"subjectName":"운영체제","difficulty":"보통","priorityLevel":"높음","currentConfidence":"보통"}}
        """; }
    @Test void normalRegistrationConnectsBothRecords() throws Exception {
        mvc.perform(post("/goal-registrations").contentType("application/json").content(payload()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true))
            .andExpect(jsonPath("$.subject.goalId").value(8)).andExpect(jsonPath("$.goal.goalId").value(8));
        var order=inOrder(jdbc,goals,subjects);order.verify(jdbc).queryForList(anyString(),eq(Long.class),eq(1L));
        order.verify(goals).findByUserId(1L);order.verify(goals).saveAndFlush(any());order.verify(subjects).saveAndFlush(any());
    }
    @ParameterizedTest @ValueSource(strings={"missingSubject","blankName","longName","badTime","badUser","existingId","badDifficulty","badDate"})
    void invalidInputNeverWrites(String kind) throws Exception {
        String p=payload();
        p=switch(kind){
            case "missingSubject"->p.substring(0,p.indexOf(",\"subject\""))+"}";
            case "blankName"->p.replace("운영체제","");
            case "longName"->p.replace("운영체제","a".repeat(101));
            case "badTime"->p.replace(":120",":1441");
            case "badUser"->p.replace("\"userId\":1","\"userId\":2");
            case "existingId"->p.replace("\"userId\":1","\"userId\":1,\"goalId\":1");
            case "badDifficulty"->p.replace("\"difficulty\":\"보통\"","\"difficulty\":\"unknown\"");
            default->p.replace("2026-11-12","invalid");};
        mvc.perform(post("/goal-registrations").contentType("application/json").content(p)).andExpect(status().isBadRequest());
        verify(goals,never()).saveAndFlush(any());verify(subjects,never()).saveAndFlush(any());
    }
    @Test void duplicateReturnsExistingEvenAfterAdditionalSubject() {
        Subject s=subject();s.setSubjectId(7L);s.setGoalId(1L);
        when(goals.findByUserId(1L)).thenReturn(List.of(goal(1)));
        when(subjects.findByUserIdAndGoalId(1L,1L)).thenReturn(List.of(s,new Subject()));
        var result=service.register(new GoalRegistrationService.Registration(goal(0),subject()));
        assertEquals(true,result.get("duplicate"));assertEquals(1L,((Goal)result.get("goal")).getGoalId());
        verify(goals,never()).saveAndFlush(any());verify(subjects,never()).saveAndFlush(any());
    }
    @Test void storageFailureIsNotReportedAsSuccessOrSqlDetail() throws Exception {
        doThrow(new DataIntegrityViolationException("private SQL detail")).when(subjects).saveAndFlush(any());
        mvc.perform(post("/goal-registrations").contentType("application/json").content(payload()))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.saved").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private SQL detail"))));
    }
    @Test void subjectFailurePropagatesForTransactionRollback() {
        doThrow(new DataIntegrityViolationException("fixture")).when(subjects).saveAndFlush(any());
        assertThrows(DataIntegrityViolationException.class,()->service.register(new GoalRegistrationService.Registration(goal(0),subject())));
    }
    @Test void incompleteGoalCanReceiveSubjectWithoutSavingGoal() {
        when(goals.findById(3L)).thenReturn(Optional.of(goal(3)));
        var result=service.addSubject(3,subject());
        assertEquals(3L,((Subject)result.get("subject")).getGoalId());verify(goals,never()).saveAndFlush(any());
    }
    @Test void duplicateSubjectIsReusedAndChangedSameNameConflicts() {
        when(goals.findById(3L)).thenReturn(Optional.of(goal(3)));
        Subject old=subject();old.setSubjectId(7L);when(subjects.findByUserIdAndGoalId(1L,3L)).thenReturn(List.of(old));
        assertEquals(true,service.addSubject(3,subject()).get("duplicate"));
        Subject changed=subject();changed.setDifficulty("어려움");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.addSubject(3,changed)).getStatusCode().value());
        verify(subjects,never()).saveAndFlush(any());
    }
    @Test void foreignOrMissingGoalCannotBeCompleted() {
        Goal foreign=goal(3);foreign.setUserId(2L);when(goals.findById(3L)).thenReturn(Optional.of(foreign));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.addSubject(3,subject())).getStatusCode().value());
        verify(subjects,never()).saveAndFlush(any());
    }
    @Test void explicitGoalOneGetsFinalWhileGoalThreeRemainsIncomplete() throws Exception {
        StudySavedPlanService saved=mock(StudySavedPlanService.class);
        when(goals.findAll()).thenReturn(List.of(goal(1),goal(3)));
        var snapshot=Map.<String,Object>of("goalId",1L,"planSaved",true,"studyMinutes",100,"carryoverMinutes",9,"newStudyMinutes",91);
        when(saved.find(eq(1L),any())).thenReturn(new StudySavedPlanService.SavedPlan(snapshot,LocalDate.now(),LocalTime.NOON,"FINAL","10:00","12:00","","",""));
        StudyPlanController plan=new StudyPlanController(goals,subjects,mock(StudyLogRepository.class),jdbc,saved);
        var web=MockMvcBuilders.standaloneSetup(plan).build();
        web.perform(get("/study-plan?goalId=1")).andExpect(status().isOk()).andExpect(jsonPath("$.goalId").value(1))
                .andExpect(jsonPath("$.carryoverMinutes").value(9)).andExpect(jsonPath("$.newStudyMinutes").value(91));
        web.perform(get("/study-plan?goalId=3")).andExpect(status().isOk()).andExpect(jsonPath("$.goalId").value(3))
                .andExpect(jsonPath("$.message").exists()).andExpect(jsonPath("$.studyMinutes").doesNotExist());
        verify(saved,never()).save(anyLong(),any(),any(),any(),anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","abc","","999999999999999999999"})
    void invalidSelectedGoalIsNotSilentlyReplaced(String id) throws Exception {
        when(goals.findAll()).thenReturn(List.of(goal(1)));
        var plan=new StudyPlanController(goals,subjects,mock(StudyLogRepository.class),jdbc,mock(StudySavedPlanService.class));
        MockMvcBuilders.standaloneSetup(plan).build().perform(get("/study-plan").param("goalId",id)).andExpect(status().isBadRequest());
    }
    @Test void unknownSelectedGoalReturns404() throws Exception {
        when(goals.findAll()).thenReturn(List.of(goal(1)));
        var plan=new StudyPlanController(goals,subjects,mock(StudyLogRepository.class),jdbc,mock(StudySavedPlanService.class));
        MockMvcBuilders.standaloneSetup(plan).build().perform(get("/study-plan?goalId=99")).andExpect(status().isNotFound());
    }
    @Test void savedLookupUsesExplicitGoalSql() throws Exception {
        when(jdbc.queryForList("SELECT goal_id FROM goals WHERE user_id = ? AND goal_id = ?",Long.class,1L,1L)).thenReturn(List.of(1L));
        var saved=mock(StudySavedPlanService.class);
        var controller=new StudyRescheduleSaveController(jdbc,mock(StudyPlanController.class),saved);
        MockMvcBuilders.standaloneSetup(controller).build().perform(get("/study-reschedule-saved?goalId=1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(false));
        verify(saved).find(eq(1L),any());
    }
}
