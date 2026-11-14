package review.mobile;

import ai_study_planner.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest(classes={AiStudyPlannerApplication.class,IsolatedStudyLogServer.RuntimeConfiguration.class},webEnvironment=SpringBootTest.WebEnvironment.MOCK)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MobileStudyLogIsolationTests {
    static final String RUN=UUID.randomUUID().toString(),PASSWORD=UUID.randomUUID().toString().replace("-","").substring(0,12);
    static final Map<String,Object> PROPS=IsolatedStudyLogServer.properties(8099,RUN,new BCryptPasswordEncoder(12).encode(PASSWORD));
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){PROPS.forEach((k,v)->r.add(k,()->v));}
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired StudyLogRepository logs;
    @Autowired IsolatedStudyLogServer.Gate gate;
    IsolatedStudyLogFixture.Seed seed;
    ObjectMapper json=new ObjectMapper();
    @BeforeAll void fixture()throws Exception{
        seed=IsolatedStudyLogFixture.seed((org.springframework.context.ConfigurableApplicationContext)context,(String)PROPS.get("spring.datasource.url"));gate.open(seed);
    }
    @Test void realSecuritySaveGetGoalSeparationAndFinalPreservation()throws Exception{
        var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(gate).apply(springSecurity()).build();
        mvc.perform(get("/study-logs")).andExpect(status().isUnauthorized());
        var auth=mvc.perform(get("/auth/csrf")).andReturn();var session=(MockHttpSession)auth.getRequest().getSession();
        String token=json.readTree(auth.getResponse().getContentAsString()).path("token").asText();
        mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN",token).param("username","mobile-h2-owner").param("password",PASSWORD)).andExpect(status().isOk());
        token=json.readTree(mvc.perform(get("/auth/csrf").session(session)).andReturn().getResponse().getContentAsString()).path("token").asText();
        for(long goal:List.of(seed.goalA(),seed.goalB()))mvc.perform(get("/analysis-summary").session(session).param("goalId",""+goal)).andExpect(status().isOk()).andExpect(jsonPath("$.recordCount").value(0));
        var body=Map.of("userId",1,"subjectId",seed.subjectA(),"studyDate",seed.date(),"studyMinutes",40,"studyContent","격리 H2 기록 확인","focusScore",3,"understandingScore",3,"fatigueScore",2,"memo","가상 단일 저장");
        var result=mvc.perform(post("/study-logs").session(session).header("X-CSRF-TOKEN",token).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.studyMinutes").value(40)).andExpect(jsonPath("$.focusScore").value(3)).andExpect(jsonPath("$.understandingScore").value(3)).andExpect(jsonPath("$.fatigueScore").value(2)).andReturn();
        long id=json.readTree(result.getResponse().getContentAsString()).path("logId").asLong();assertTrue(id>0);
        for(int i=0;i<2;i++)mvc.perform(get("/study-logs").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].logId").value(id));
        mvc.perform(get("/analysis-summary").session(session).param("goalId",""+seed.goalA())).andExpect(status().isOk()).andExpect(jsonPath("$.totalStudyMinutes").value(40)).andExpect(jsonPath("$.averageUnderstanding").value(3.0)).andExpect(jsonPath("$.averageFocus").value(3.0));
        mvc.perform(get("/analysis-summary").session(session).param("goalId",""+seed.goalB())).andExpect(status().isOk()).andExpect(jsonPath("$.recordCount").value(0));
        mvc.perform(get("/study-reschedule-saved").session(session).param("goalId",""+seed.goalA()).param("date",seed.date())).andExpect(status().isOk()).andExpect(jsonPath("$.planStatus").value("FINAL")).andExpect(jsonPath("$.studyMinutes").value(100)).andExpect(jsonPath("$.carryoverMinutes").value(9)).andExpect(jsonPath("$.newStudyMinutes").value(91)).andExpect(jsonPath("$.timeSlots.length()").value(3));
        mvc.perform(post("/study-logs").session(session).header("X-CSRF-TOKEN",token).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isConflict());
        mvc.perform(get("/llm-coaching").session(session)).andExpect(status().isForbidden());
        assertEquals(1,logs.count());assertEquals(seed.finalJson(),jdbc.queryForObject("SELECT plan_json FROM study_saved_plans",String.class));
        assertThrows(IllegalStateException.class,()->IsolatedStudyLogFixture.seed((org.springframework.context.ConfigurableApplicationContext)context,(String)PROPS.get("spring.datasource.url")));
    }
    @ParameterizedTest @ValueSource(ints={0,80,8080,8083,8084,8085,8086,65536}) void protectedOrInvalidPortsRejected(int port){assertThrows(IllegalArgumentException.class,()->IsolatedStudyLogServer.validatePort(port));}
    @ParameterizedTest @ValueSource(ints={12,13,14,15,16,72}) void allowedPasswordByteLengths(int length){assertDoesNotThrow(()->IsolatedStudyLogServer.validatePasswordByteLength(length));}
    @ParameterizedTest @ValueSource(ints={-1,0,11,73}) void invalidPasswordByteLengths(int length){assertThrows(IllegalArgumentException.class,()->IsolatedStudyLogServer.validatePasswordByteLength(length));}
    @Test void networkGuardDeniesProvidersMysqlAndOtherListeners(){
        var guard=new IsolatedStudyLogServer.NetworkGuard(8099);
        assertThrows(SecurityException.class,()->guard.checkConnect("api.openai.com",443));assertThrows(SecurityException.class,()->guard.checkConnect("generativelanguage.googleapis.com",443));
        assertThrows(SecurityException.class,()->guard.checkConnect("localhost",3306));assertThrows(SecurityException.class,()->guard.checkConnect("localhost",8080));assertThrows(SecurityException.class,()->guard.checkListen(8080));
        assertDoesNotThrow(()->guard.checkConnect("127.0.0.1",8099));
    }
    @Test void missingReadinessAndUnapprovedEndpointsNeverReachController()throws Exception{
        var g=new IsolatedStudyLogServer.Gate();var request=new MockHttpServletRequest("GET","/goals");var response=new MockHttpServletResponse();var chain=new MockFilterChain();g.doFilter(request,response,chain);assertEquals(503,response.getStatus());assertNull(chain.getRequest());
        g.open(seed);request=new MockHttpServletRequest("POST","/study-reschedule-save");response=new MockHttpServletResponse();chain=new MockFilterChain();g.doFilter(request,response,chain);assertEquals(403,response.getStatus());assertNull(chain.getRequest());
    }
    @Test void failedAttemptConsumesOnlyTestBudgetWithoutDatabaseWrite()throws Exception{
        var g=new IsolatedStudyLogServer.Gate();g.open(seed);var request=new MockHttpServletRequest("POST","/study-logs");request.setContent("{}".getBytes());var response=new MockHttpServletResponse();var chain=new MockFilterChain();g.doFilter(request,response,chain);assertEquals(400,response.getStatus());assertNull(chain.getRequest());
        response=new MockHttpServletResponse();g.doFilter(request,response,new MockFilterChain());assertEquals(409,response.getStatus());
    }
    @Test void externalOrFileH2UrlsRejectedBeforeSeed() {
        assertThrows(IllegalStateException.class,()->IsolatedStudyLogFixture.assertH2(jdbc,"jdbc:mysql://localhost:3306/ai_study_planner_test"));
        assertThrows(IllegalStateException.class,()->IsolatedStudyLogFixture.assertH2(jdbc,"jdbc:h2:file:./unsafe"));
    }
}
