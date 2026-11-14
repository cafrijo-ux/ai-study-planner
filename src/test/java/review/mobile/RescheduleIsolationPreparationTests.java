package review.mobile;

import ai_study_planner.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 서버를 열지 않는 H2 + 실제 세션/CSRF/Controller/저장 서비스 + 전용 Gate 검증. */
@SpringBootTest(classes=AiStudyPlannerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.MOCK)
class RescheduleIsolationPreparationTests {
    static final String PASSWORD=UUID.randomUUID().toString().substring(0,16);
    static final Map<String,Object> PROPS=IsolatedStudyLogServer.properties(8099,UUID.randomUUID().toString(),new BCryptPasswordEncoder(12).encode(PASSWORD));
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){PROPS.forEach((k,v)->r.add(k,()->v));}
    @Autowired WebApplicationContext ctx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StudyLogRepository logs;
    @Autowired SubjectRepository subjects;
    @Autowired GoalRepository goals;
    IsolatedRescheduleFixture.Seed seed;
    IsolatedRescheduleFixture.Baseline baseline;
    IsolatedRescheduleServer.Gate gate;
    MockMvc mvc;
    MockHttpSession session;
    String token;
    final ObjectMapper json=new ObjectMapper();
    ConfigurableApplicationContext context(){return (ConfigurableApplicationContext)ctx;}
    @BeforeEach void prepare()throws Exception{
        IsolatedStudyLogFixture.assertH2(jdbc,(String)PROPS.get("spring.datasource.url"));
        assertThrows(ClassNotFoundException.class,()->Class.forName("com.mysql.cj.jdbc.Driver"));
        logs.deleteAll();subjects.deleteAll();goals.deleteAll();jdbc.update("DELETE FROM users");jdbc.update("DELETE FROM study_questions");jdbc.update("DELETE FROM ai_responses");
        jdbc.execute("DROP TABLE IF EXISTS study_saved_plans");jdbc.execute("DROP TABLE IF EXISTS study_schedule_settings");
        seed=IsolatedRescheduleFixture.seed(context(),(String)PROPS.get("spring.datasource.url"));
        baseline=IsolatedRescheduleFixture.baseline(context());gate=new IsolatedRescheduleServer.Gate();gate.open(seed);
        // 실제 등록 순서처럼 보안 필터 뒤에서 전용 Gate를 실행한다.
        mvc=MockMvcBuilders.webAppContextSetup(ctx).addFilters(ctx.getBean("springSecurityFilterChain",Filter.class),gate).build();
        var csrf=mvc.perform(get("/auth/csrf")).andReturn();session=(MockHttpSession)csrf.getRequest().getSession();token=json.readTree(csrf.getResponse().getContentAsString()).path("token").asText();
        mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN",token).param("username","mobile-h2-owner").param("password",PASSWORD)).andExpect(status().isOk());
        token=json.readTree(mvc.perform(get("/auth/csrf").session(session)).andReturn().getResponse().getContentAsString()).path("token").asText();
    }
    @AfterEach void protectedRowsRemain(){assertEquals(true,IsolatedRescheduleFixture.preservation(context(),seed,baseline).get("preserved"));}
    @Test void dynamicFixtureAndPreviewHaveSixtyNineNinetyOneWithoutWrites()throws Exception{
        assertEquals(LocalDate.now(IsolatedRescheduleFixture.KOREA),seed.today());assertEquals(seed.today().plusDays(1),seed.target());
        assertEquals(2,logs.count());assertEquals(3,baseline.finals().size());assertEquals(1,baseline.settings().size());
        assertEquals(40,logs.findAll().stream().filter(l->l.getSubjectId()==seed.subjectA()).mapToInt(StudyLog::getStudyMinutes).sum());
        IsolatedRescheduleFixture.verifyPreview(context(),seed);
        for(int i=0;i<2;i++)mvc.perform(get("/study-reschedule-preview").session(session).queryParam("goalId",""+seed.goalA()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scheduleDate").value(seed.target().toString())).andExpect(jsonPath("$.carryoverTotalPendingMinutes").value(60)).andExpect(jsonPath("$.carryoverMinutes").value(9)).andExpect(jsonPath("$.newStudyMinutes").value(91));
        mvc.perform(get("/study-reschedule-saved").session(session).queryParam("goalId",""+seed.goalA()).queryParam("date",seed.target().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(false));
        mvc.perform(get("/study-plan").session(session).queryParam("goalId",""+seed.goalA()).queryParam("date",seed.target().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.carryoverMinutes").value(9));
        mvc.perform(get("/study-reschedule-preview").session(session).queryParam("goalId",""+seed.goalB())).andExpect(status().isOk()).andExpect(jsonPath("$.carryoverTotalPendingMinutes").value(0));
        for(String route:List.of("/subjects","/analysis-summary"))mvc.perform(get(route).session(session).queryParam("goalId",""+seed.goalA())).andExpect(status().isOk());
        mvc.perform(get("/goals").session(session)).andExpect(status().isOk());mvc.perform(get("/study-logs").session(session)).andExpect(status().isOk());
        assertFalse(gate.attempted(),"미리보기/취소 후에는 저장 시도가 없어야 함");assertEquals(baseline,IsolatedRescheduleFixture.baseline(context()));
    }
    @ParameterizedTest @CsvSource({"10:00,DRAFT","23:30,FINAL"})
    void authenticatedEmptyPostSavesOnceAndGetMatches(String time,String expected)throws Exception{
        LocalDateTime now=LocalDateTime.of(seed.today(),LocalTime.parse(time));
        try(MockedStatic<LocalDateTime> clock=mockStatic(LocalDateTime.class,CALLS_REAL_METHODS)){
            clock.when(()->LocalDateTime.now(IsolatedRescheduleFixture.KOREA)).thenReturn(now);
            mvc.perform(post("/study-reschedule-save").session(session).queryParam("goalId",""+seed.goalA()).header("X-CSRF-TOKEN",token).contentType("application/x-www-form-urlencoded").content(""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planStatus").value(expected)).andExpect(jsonPath("$.planDate").value(seed.target().toString())).andExpect(jsonPath("$.carryoverMinutes").value(9));
        }
        assertTrue(gate.attempted());
        for(int i=0;i<2;i++)mvc.perform(get("/study-reschedule-saved").session(session).queryParam("goalId",""+seed.goalA()).queryParam("date",seed.target().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.goalId").value(seed.goalA())).andExpect(jsonPath("$.planStatus").value(expected)).andExpect(jsonPath("$.studyMinutes").value(100)).andExpect(jsonPath("$.carryoverMinutes").value(9)).andExpect(jsonPath("$.newStudyMinutes").value(91));
        mvc.perform(post("/study-reschedule-save").session(session).queryParam("goalId",""+seed.goalA()).header("X-CSRF-TOKEN",token).content("")) .andExpect(status().isConflict());
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM study_saved_plans",Integer.class));assertEquals(2,logs.count());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM study_schedule_settings",Integer.class));
    }
    @ParameterizedTest @ValueSource(strings={"/study-logs","/study-schedule-settings","/goals/register","/study-questions","/gpt-weekly-coaching"})
    void otherWritesAreBlocked(String path)throws Exception{
        mvc.perform(post(path).session(session).header("X-CSRF-TOKEN",token).content("")) .andExpect(status().isForbidden());assertFalse(gate.attempted());
    }
    @ParameterizedTest @ValueSource(strings={"/llm-coaching","/study-log-summary","/gpt-weekly-coaching","/auto-reschedule"})
    void nonAllowlistedGetCannotCallProviders(String path)throws Exception{
        mvc.perform(get(path).session(session).header("X-CSRF-TOKEN",token)).andExpect(status().isForbidden());
    }
    @ParameterizedTest @ValueSource(strings={"goalB","duplicate","body","missing"})
    void invalidSaveCannotWriteAndConsumesAttempt(String kind)throws Exception{
        var request=post("/study-reschedule-save").session(session).header("X-CSRF-TOKEN",token).contentType("application/x-www-form-urlencoded");
        if(!kind.equals("missing"))request.queryParam("goalId",String.valueOf(kind.equals("goalB")?seed.goalB():seed.goalA()));
        if(kind.equals("duplicate"))request.queryParam("goalId",""+seed.goalA());
        request.content(kind.equals("body")?"unexpected=1":"");
        mvc.perform(request).andExpect(status().isBadRequest());assertTrue(gate.attempted());assertEquals(baseline,IsolatedRescheduleFixture.baseline(context()));
    }
    @Test void authenticationCsrfAndFilterOrderRemainRequired()throws Exception{
        mvc.perform(get("/study-reschedule-preview").queryParam("goalId",""+seed.goalA())).andExpect(status().isUnauthorized());
        mvc.perform(post("/study-reschedule-save").session(session).queryParam("goalId",""+seed.goalA()).content("")) .andExpect(status().isForbidden());
        mvc.perform(post("/study-reschedule-save").session(session).queryParam("goalId",""+seed.goalA()).header("X-CSRF-TOKEN","bad").content("")) .andExpect(status().isForbidden());
        assertFalse(gate.attempted());assertEquals(-90,new IsolatedRescheduleServer.RuntimeConfiguration().isolatedRescheduleFilter(gate).getOrder());
    }
    @Test void unreadyWrongPrincipalAndExpiredRunFailClosed()throws Exception{
        var q=new MockHttpServletRequest("GET","/goals");var r=new MockHttpServletResponse();var chain=new MockFilterChain();new IsolatedRescheduleServer.Gate().doFilter(q,r,chain);assertEquals(503,r.getStatus());assertNull(chain.getRequest());
        q.setUserPrincipal(()->"not-the-fixture-owner");r=new MockHttpServletResponse();gate.doFilter(q,r,new MockFilterChain());assertEquals(401,r.getStatus());
        var expired=new IsolatedRescheduleServer.Gate();expired.open(new IsolatedRescheduleFixture.Seed(seed.goalA(),seed.goalB(),seed.subjectA(),seed.subjectB(),seed.today().minusDays(1)));
        q.setUserPrincipal(()->"mobile-h2-owner");r=new MockHttpServletResponse();expired.doFilter(q,r,new MockFilterChain());assertEquals(409,r.getStatus());assertFalse(expired.attempted());
    }
    @Test void missingExplicitReadGoalIsRejected()throws Exception{
        mvc.perform(get("/study-reschedule-preview").session(session)).andExpect(status().isBadRequest());
    }
    @Test void defaultAndWrongModeNeverStartServer(){
        var prior=System.out;var bytes=new ByteArrayOutputStream();
        try{System.setOut(new PrintStream(bytes, true,StandardCharsets.UTF_8));IsolatedRescheduleServer.main(new String[0]);IsolatedRescheduleServer.main(new String[]{"--approved-mobile-h2","8099","unused","unused"});}
        finally{System.setOut(prior);}
        assertEquals(2,bytes.toString(StandardCharsets.UTF_8).lines().filter(s->s.equals("PREPARED_ONLY; NO_SERVER_STARTED")).count());
    }
}
