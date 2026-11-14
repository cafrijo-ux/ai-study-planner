package ai_study_planner.security;

import ai_study_planner.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.test.web.servlet.*;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

/** Manual MVC + Security context only: no Boot auto-configuration, JPA or real datasource. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes=DemoSecurityTests.Config.class)
class DemoSecurityTests {
    @Configuration @EnableWebMvc
    @Import({DemoSecurityConfiguration.class,AuthController.class,DemoOwnershipAdvice.class,SafeApiErrors.class,
        GoalController.class,SubjectController.class,StudyLogController.class,UserController.class,LlmConfigController.class,Probe.class})
    static class Config {
        @Bean GoalRepository goals(){return mock(GoalRepository.class);}
        @Bean SubjectRepository subjects(){return mock(SubjectRepository.class);}
        @Bean StudyLogRepository logs(){return mock(StudyLogRepository.class);}
        @Bean UserRepository users(){return mock(UserRepository.class);}
    }
    @RestController static class Probe {
        @GetMapping("/private") public Map<String,String> get(){return Map.of("message","private");}
        @PostMapping("/write") public Map<String,String> write(){return Map.of("saved","mock only");}
        @GetMapping("/explode") public Object explode(){throw new org.springframework.dao.DataAccessResourceFailureException("synthetic internal connection detail");}
    }
    @DynamicPropertySource static void auth(DynamicPropertyRegistry registry){
        registry.add("planner.auth.username",()->"mock-owner");
        registry.add("planner.auth.password-hash",()->new BCryptPasswordEncoder(12).encode("mock-test-password"));
    }
    @Autowired WebApplicationContext context;
    @Autowired GoalRepository goals;
    @Autowired SubjectRepository subjects;
    @Autowired StudyLogRepository logs;
    @Autowired UserRepository users;
    MockMvc mvc;
    @BeforeEach void setup(){reset(goals,subjects,logs,users);mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();}
    @Test void anonymousCannotReadPrivateApi()throws Exception {mvc.perform(get("/goals")).andExpect(status().isUnauthorized());verifyNoInteractions(goals);}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"/study-question.html.before-aiquestion.bak", "/dashboard.html.before-display", "/dashboard.html.backup-20261020", "/STUDY-QUESTION.HTML.BAK"})
    void sourceBackupsAreDeniedEvenForAuthenticatedOwner(String path)throws Exception {
        mvc.perform(get(path).with(user("owner").roles("DEMO_OWNER"))).andExpect(status().isForbidden());
    }
    @Test void anonymousHtmlRedirectsToLogin()throws Exception {mvc.perform(get("/dashboard.html")).andExpect(status().isFound()).andExpect(redirectedUrl("/login.html"));}
    @Test void anonymousUnknownApiAlsoBlocked()throws Exception {mvc.perform(get("/new-api-not-yet-added")).andExpect(status().isUnauthorized());}
    @Test void authenticatedWrongRoleDenied()throws Exception {mvc.perform(get("/goals").with(user("other").roles("OTHER"))).andExpect(status().isForbidden());verifyNoInteractions(goals);}
    @Test void missingCsrfNeverWrites()throws Exception {mvc.perform(post("/write").with(user("owner").roles("DEMO_OWNER"))).andExpect(status().isForbidden());}
    @Test void badCsrfNeverWrites()throws Exception {mvc.perform(post("/write").with(user("owner").roles("DEMO_OWNER")).with(csrf().useInvalidToken())).andExpect(status().isForbidden());}
    @Test void validCsrfAllowsMockWrite()throws Exception {mvc.perform(post("/write").with(user("owner").roles("DEMO_OWNER")).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.saved").value("mock only"));}
    @Test void csrfEndpointHasNoCacheAndMaskedToken()throws Exception {mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN")).andExpect(jsonPath("$.token").isString()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));}
    @Test void correctPasswordCreatesSessionAndRotatesSessionId()throws Exception {
        MockHttpSession session=new MockHttpSession();String before=session.getId();
        mvc.perform(post("/auth/login").servletPath("/auth/login").session(session).with(csrf()).param("username","mock-owner").param("password","mock-test-password")).andExpect(status().isOk());
        assertNotEquals(before,session.getId());
        mvc.perform(get("/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(1));
    }
    @Test void incorrectPasswordIsGenericAndDoesNotAuthenticate()throws Exception {
        MockHttpSession session=new MockHttpSession();mvc.perform(post("/auth/login").session(session).with(csrf()).param("username","mock-owner").param("password","wrong")).andExpect(status().isUnauthorized()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))));
        mvc.perform(get("/private").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void logoutInvalidatesSession()throws Exception {
        MockHttpSession session=new MockHttpSession();mvc.perform(post("/auth/logout").session(session).with(user("owner").roles("DEMO_OWNER")).with(csrf())).andExpect(status().isOk());assertTrue(session.isInvalid());
    }
    @Test void logoutRequiresCsrf()throws Exception {mvc.perform(post("/auth/logout").with(user("owner").roles("DEMO_OWNER"))).andExpect(status().isForbidden());}
    @Test void noCrossOriginCredentialHeaders()throws Exception {mvc.perform(options("/write").header("Origin","https://attacker.invalid").header("Access-Control-Request-Method","POST")).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));}
    @Test void protectiveHeadersAndHttpsHsts()throws Exception {mvc.perform(get("/auth/csrf").secure(true)).andExpect(header().string("X-Frame-Options","DENY")).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(header().exists("Strict-Transport-Security")).andExpect(header().exists("Content-Security-Policy"));}
    @Test void sessionCookieAttributesAreSecure()throws Exception {MockServletContext servlet=new MockServletContext();new DemoSecurityConfiguration().secureSessionCookie(true).onStartup(servlet);assertTrue(servlet.getSessionCookieConfig().isSecure());assertTrue(servlet.getSessionCookieConfig().isHttpOnly());assertEquals("Lax",servlet.getSessionCookieConfig().getAttribute("SameSite"));}
    @Test void onlyHashedPasswordsAccepted(){assertThrows(IllegalStateException.class,()->new DemoSecurityConfiguration().demoUsers("owner","plain-text"));assertThrows(IllegalStateException.class,()->new DemoSecurityConfiguration().demoUsers("",""));}
    @Test void backupIsDenied()throws Exception {mvc.perform(get("/dashboard.html.backup-example").with(user("owner").roles("DEMO_OWNER"))).andExpect(status().isForbidden());}
    @Test void configStatusRemainsAvailableOnlyToOwner()throws Exception {mvc.perform(get("/llm-config-check")).andExpect(status().isUnauthorized());mvc.perform(get("/llm-config-check").with(user("owner").roles("DEMO_OWNER"))).andExpect(status().isOk()).andExpect(jsonPath("$.apiKey").doesNotExist());}
    @Test void legacyAiGetRequiresCsrfEvenForOwner()throws Exception {for(String path:List.of("/llm-coaching","/study-log-summary"))mvc.perform(get(path).servletPath(path).with(user("owner").roles("DEMO_OWNER"))).andExpect(status().isForbidden());}
    @Test void databaseFailureDoesNotExposeDetails()throws Exception {mvc.perform(get("/explode").with(user("owner").roles("DEMO_OWNER"))).andExpect(status().isInternalServerError()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("connection detail"))));}
    @Test void goalsCannotEnumerateOtherOwner()throws Exception {Goal own=goal(1,1),other=goal(2,2);when(goals.findAll()).thenReturn(List.of(own,other));mvc.perform(get("/goals").with(user("owner").roles("DEMO_OWNER"))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].goalId").value(1));}
    @Test void forgedUserCannotWriteGoal()throws Exception {mvc.perform(post("/goals").with(user("owner").roles("DEMO_OWNER")).with(csrf()).contentType("application/json").content("{\"userId\":2}")).andExpect(status().isForbidden());verifyNoInteractions(goals);}
    @Test void cannotOverwriteOtherOwnersGoal()throws Exception {when(goals.findById(2L)).thenReturn(Optional.of(goal(2,2)));mvc.perform(post("/goals").with(user("owner").roles("DEMO_OWNER")).with(csrf()).contentType("application/json").content("{\"userId\":1,\"goalId\":2}")).andExpect(status().isNotFound());verify(goals,never()).save(any());}
    @Test void cannotAddSubjectToOtherOwnersGoal()throws Exception {when(goals.findById(2L)).thenReturn(Optional.of(goal(2,2)));mvc.perform(post("/subjects").with(user("owner").roles("DEMO_OWNER")).with(csrf()).contentType("application/json").content("{\"userId\":1,\"goalId\":2}")).andExpect(status().isNotFound());verifyNoInteractions(subjects);}
    @Test void cannotWriteLogForAnotherOwnersSubject()throws Exception {Subject other=new Subject();other.setUserId(2L);when(subjects.findById(8L)).thenReturn(Optional.of(other));mvc.perform(post("/study-logs").with(user("owner").roles("DEMO_OWNER")).with(csrf()).contentType("application/json").content("{\"userId\":1,\"subjectId\":8}")).andExpect(status().isForbidden());verifyNoInteractions(logs);}
    @Test void validGoalIsWrittenOnlyToMockRepository()throws Exception {when(goals.save(any())).thenAnswer(i->i.getArgument(0));mvc.perform(post("/goals").with(user("owner").roles("DEMO_OWNER")).with(csrf()).contentType("application/json").content("{\"userId\":1,\"goalName\":\"가상 목표\",\"goalType\":\"시험\",\"examDate\":\"2026-11-12\",\"targetScore\":\"80\",\"currentLevel\":\"보통\",\"weakPoint\":\"CPU\",\"dailyStudyTime\":100,\"preferredStudyMethod\":\"복습\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(1));verify(goals).save(any());}
    @Test void loginAttemptsBoundedWithoutTrustingForwardedIp()throws Exception {
        LoginAttemptFilter filter=new LoginAttemptFilter();
        for(int i=0;i<11;i++){MockHttpServletRequest q=new MockHttpServletRequest("POST","/auth/login");q.setServletPath("/auth/login");q.setRemoteAddr("192.0.2.1");q.addHeader("X-Forwarded-For","192.0.2."+i);MockHttpServletResponse s=new MockHttpServletResponse();filter.doFilter(q,s,new MockFilterChain());assertEquals(i<10?200:429,s.getStatus());}
    }

    @Test void studyLogTestsUseMocksWithoutDatasourceOrIdempotencyBean() {
        assertTrue(context.getBeansOfType(javax.sql.DataSource.class).isEmpty());
        assertTrue(context.getBeansOfType(StudyLogIdempotencyService.class).isEmpty());
        assertTrue(mockingDetails(logs).isMock());
    }

    @Test void anonymousStudyLogPostNeverSaves() throws Exception {
        mvc.perform(post("/study-logs").with(csrf()).contentType("application/json")
                .content(studyJson(studyBody(null,101))))
                .andExpect(status().isUnauthorized());
        noStudyLogWrites();
    }

    @Test void studyLogPostWithoutCsrfNeverSaves() throws Exception {
        mvc.perform(post("/study-logs").with(user("owner").roles("DEMO_OWNER"))
                .contentType("application/json").content(studyJson(studyBody(null,101))))
                .andExpect(status().isForbidden());
        noStudyLogWrites();
    }

    @Test void studyLogForgedUserNeverSaves() throws Exception {
        var body=studyBody(null,101);body.put("userId",2);
        studyPost("",body).andExpect(status().isForbidden());
        noStudyLogWrites();
    }

    @Test void anotherOwnersExistingLogCannotBeEdited() throws Exception {
        ownedStudyFixtures();
        var foreign=new StudyLog();foreign.setLogId(500L);foreign.setUserId(2L);foreign.setSubjectId(101L);
        when(logs.findById(500L)).thenReturn(Optional.of(foreign));
        studyPost("",studyBody(500L,102)).andExpect(status().isForbidden());
        noStudyLogWrites();
    }

    @Test void anotherOwnersSubjectCannotBeUsedToEditLog() throws Exception {
        ownedStudyFixtures();
        var foreign=new Subject();foreign.setSubjectId(301L);foreign.setUserId(2L);foreign.setGoalId(30L);
        when(subjects.findById(301L)).thenReturn(Optional.of(foreign));
        studyPost("",studyBody(500L,301)).andExpect(status().isForbidden());
        noStudyLogWrites();
    }

    @Test void selectedGoalAndNewSubjectMustMatch() throws Exception {
        ownedStudyFixtures();
        studyPost("?goalId=10",studyBody(null,201)).andExpect(status().isBadRequest());
        noStudyLogWrites();
    }

    @Test void omittingGoalCannotMoveExistingLogToAnotherGoal() throws Exception {
        ownedStudyFixtures();
        studyPost("",studyBody(500L,201)).andExpect(status().isBadRequest());
        noStudyLogWrites();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"?goalId=10","?goalId=20"})
    void explicitGoalCannotMoveExistingLogToAnotherGoal(String query) throws Exception {
        ownedStudyFixtures();
        studyPost(query,studyBody(500L,201)).andExpect(status().isBadRequest());
        noStudyLogWrites();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"","?goalId=10"})
    void sameGoalEditsKeepExistingContract(String query) throws Exception {
        ownedStudyFixtures();
        when(logs.save(any())).thenAnswer(i->i.getArgument(0));
        studyPost(query,studyBody(500L,102)).andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Mode","DISABLED"))
                .andExpect(jsonPath("$.logId").value(500))
                .andExpect(jsonPath("$.subjectId").value(102))
                .andExpect(jsonPath("$.studyMinutes").value(40));
        verify(logs).save(argThat(l->Objects.equals(l.getLogId(),500L)
                && Objects.equals(l.getSubjectId(),102L)&&Objects.equals(l.getUserId(),1L)));
        verify(logs,never()).saveAndFlush(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"","?goalId=10"})
    void newLogWithoutIdKeepsExistingContract(String query) throws Exception {
        ownedStudyFixtures();
        when(logs.save(any())).thenAnswer(i->{
            StudyLog row=i.getArgument(0);assertNull(row.getLogId());row.setLogId(501L);return row;
        });
        studyPost(query,studyBody(null,101)).andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Mode","DISABLED"))
                .andExpect(jsonPath("$.logId").value(501))
                .andExpect(jsonPath("$.subjectId").value(101))
                .andExpect(jsonPath("$.studyContent").value("가상 학습 기록"));
        verify(logs).save(any());verify(logs,never()).saveAndFlush(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"studyMinutes","studyContent","studyDate","focusScore"})
    void invalidStudyLogNeverReachesEitherSaveMethod(String field) throws Exception {
        ownedStudyFixtures();
        var body=studyBody(null,101);
        body.put(field,switch(field){case "studyContent"->" ";case "studyDate"->null;default->0;});
        studyPost("?goalId=10",body).andExpect(status().isBadRequest());
        noStudyLogWrites();
    }

    private void ownedStudyFixtures() {
        when(goals.findById(10L)).thenReturn(Optional.of(goal(10,1)));
        when(goals.findById(20L)).thenReturn(Optional.of(goal(20,1)));
        for(long id:new long[]{101,102,201}){
            var subject=new Subject();subject.setSubjectId(id);subject.setUserId(1L);
            subject.setGoalId(id==201?20L:10L);
            when(subjects.findById(id)).thenReturn(Optional.of(subject));
        }
        var existing=new StudyLog();existing.setLogId(500L);existing.setUserId(1L);existing.setSubjectId(101L);
        when(logs.findById(500L)).thenReturn(Optional.of(existing));
    }

    private static Map<String,Object> studyBody(Long logId,long subjectId) {
        var body=new LinkedHashMap<String,Object>();
        body.put("userId",1);body.put("subjectId",subjectId);body.put("studyDate","2026-11-02");
        body.put("studyMinutes",40);body.put("studyContent","가상 학습 기록");
        body.put("understandingScore",3);body.put("focusScore",3);body.put("fatigueScore",2);
        if(logId!=null)body.put("logId",logId);
        return body;
    }

    private static String studyJson(Map<String,Object> body) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
    }

    private ResultActions studyPost(String query,Map<String,Object> body) throws Exception {
        return mvc.perform(post("/study-logs"+query).with(user("owner").roles("DEMO_OWNER"))
                .with(csrf()).contentType("application/json").content(studyJson(body)));
    }

    private void noStudyLogWrites() {
        verify(logs,never()).save(any());
        verify(logs,never()).saveAndFlush(any());
    }
    private static Goal goal(long id,long owner){Goal g=new Goal();g.setGoalId(id);g.setUserId(owner);return g;}
}
