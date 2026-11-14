package review.mobile;

import ai_study_planner.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Local MockServletContext only. The documentation IPs are synthetic, not this PC's configuration. */
class ReadOnlyLanH2Tests {
    static final String PC="192.168.50.10",PEER="192.168.50.11",PASSWORD=UUID.randomUUID().toString();
    @TempDir Path temp;GenericWebApplicationContext ctx;MockMvc mvc;MockHttpSession session;String token,url,id;
    ReadOnlyLanH2Server.Scope scope;ReadOnlyLanH2Server.Boundary boundary;IsolatedRescheduleFixture.Seed seed;IsolatedRescheduleFixture.Baseline before;
    static Map<String,Object> approval(String id){return Map.of("runId",id,"mode",ReadOnlyLanH2Server.MODE,"address",PC,"peer",PEER,"port",8103);}
    @BeforeEach void setup()throws Exception{
        id=UUID.randomUUID().toString();Path dir=Files.createDirectories(temp.resolve(ReadOnlyLanH2Server.BASE).resolve(id));GeminiShutdown.atomic(dir.resolve("approval.json"),approval(id),GeminiShutdown.NONE);GeminiShutdown.atomic(dir.resolve("approval-claim.json"),Map.of("runId",id,"mode",ReadOnlyLanH2Server.MODE,"approvalSha256",GeminiShutdown.sha(Files.readAllBytes(dir.resolve("approval.json")))),GeminiShutdown.NONE);scope=new ReadOnlyLanH2Server.Scope(dir);
        GeminiShutdown.atomic(dir.resolve("start-reserved.json"),Map.of("runId",id,"approvalSha256",scope.hash),GeminiShutdown.NONE);scope.claim(12345);
        var p=ReadOnlyLanH2Server.properties(8103,id,new BCryptPasswordEncoder(12).encode(PASSWORD),PC,PEER);url=(String)p.get("spring.datasource.url");var env=new org.springframework.core.env.StandardEnvironment();env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("lanH2Only",p));var app=new SpringApplication(AiStudyPlannerApplication.class);app.setEnvironment(env);app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setApplicationContextFactory(type->{var c=new GenericWebApplicationContext();c.setServletContext(new MockServletContext());return c;});ctx=(GenericWebApplicationContext)app.run("--spring.config.location=optional:classpath:/mobile-isolation-only.properties");
        IsolatedStudyLogFixture.assertH2(ctx.getBean(JdbcTemplate.class),url);assertThrows(ClassNotFoundException.class,()->Class.forName("com.mysql.cj.jdbc.Driver"));seed=IsolatedRescheduleFixture.seed(ctx,url);before=IsolatedRescheduleFixture.baseline(ctx);boundary=new ReadOnlyLanH2Server.Boundary(PEER);boundary.open(seed);
        mvc=MockMvcBuilders.webAppContextSetup(ctx).addFilters(boundary,ctx.getBean("springSecurityFilterChain",Filter.class)).build();
        var r=mvc.perform(peer(get("/auth/csrf"))).andReturn();session=(MockHttpSession)r.getRequest().getSession();token=json(r).path("token").asText();
    }
    @AfterEach void cleanup()throws Exception{if(ctx!=null&&ctx.isActive())try(var c=DriverManager.getConnection(url,"sa","")){ctx.close();c.createStatement().execute("SHUTDOWN");}}
    MockHttpServletRequestBuilder peer(MockHttpServletRequestBuilder q){return q.with(r->{r.setRemoteAddr(PEER);return r;});}
    static com.fasterxml.jackson.databind.JsonNode json(MvcResult r)throws Exception{return GeminiShutdown.JSON.readTree(r.getResponse().getContentAsByteArray());}
    void login()throws Exception{
        String previous=session.getId();mvc.perform(peer(post("/auth/login")).session(session).header("X-CSRF-TOKEN",token).param("username",ReadOnlyLanH2Server.OWNER).param("password",PASSWORD)).andExpect(status().isOk());assertNotEquals(previous,session.getId());token=json(mvc.perform(peer(get("/auth/csrf")).session(session)).andReturn()).path("token").asText();
    }
    @Test void actualGetContractPlansAndGoalSeparationRemainReadOnly()throws Exception{
        login();mvc.perform(peer(get("/goals")).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        for(long goal:List.of(seed.goalA(),seed.goalB())){
            mvc.perform(peer(get("/subjects")).session(session).queryParam("goalId",""+goal)).andExpect(status().isOk()).andExpect(jsonPath("$[0].goalId").value(goal));
            mvc.perform(peer(get("/study-plan")).session(session).queryParam("goalId",""+goal).queryParam("date",seed.today().minusDays(1).toString())).andExpect(status().isOk()).andExpect(jsonPath("$.goalId").value(goal)).andExpect(jsonPath("$.studyMinutes").value(100));
            mvc.perform(peer(get("/analysis-summary")).session(session).queryParam("scope","current").queryParam("goalId",""+goal)).andExpect(status().isOk()).andExpect(jsonPath("$.goalId").value(goal)).andExpect(jsonPath("$.totalStudyMinutes").value(goal==seed.goalA()?40:100));
        }
        mvc.perform(peer(get("/study-logs")).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        // Reused fixture's original FINAL is 100/0/100; do not confuse it with MySQL's 100/9/91.
        mvc.perform(peer(get("/study-reschedule-saved")).session(session).queryParam("goalId",""+seed.goalA()).queryParam("date",seed.today().minusDays(1).toString())).andExpect(status().isOk()).andExpect(jsonPath("$.goalId").value(seed.goalA())).andExpect(jsonPath("$.studyMinutes").value(100)).andExpect(jsonPath("$.carryoverMinutes").value(0)).andExpect(jsonPath("$.newStudyMinutes").value(100));
        assertEquals(before,IsolatedRescheduleFixture.baseline(ctx));
    }
    @Test void authenticationCsrfAndPeerAreIndependentBoundaries()throws Exception{
        mvc.perform(peer(get("/goals"))).andExpect(status().isUnauthorized());
        mvc.perform(peer(post("/auth/login")).session(session).param("username",ReadOnlyLanH2Server.OWNER).param("password",PASSWORD)).andExpect(status().isForbidden());
        mvc.perform(peer(post("/auth/login")).session(session).header("X-CSRF-TOKEN",token).param("username",ReadOnlyLanH2Server.OWNER).param("password","not-the-password")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/csrf").header("X-Forwarded-For",PEER).with(r->{r.setRemoteAddr("192.168.50.99");return r;})).andExpect(status().isForbidden());
        login();mvc.perform(peer(get("/subjects")).session(session).queryParam("goalId","9999")).andExpect(status().isBadRequest());
        mvc.perform(peer(post("/auth/logout")).session(session).header("X-CSRF-TOKEN",token)).andExpect(status().isOk());
        mvc.perform(peer(get("/goals"))).andExpect(status().isUnauthorized());
    }
    @Test void everyBusinessWriteAndGeneratingGetBlockedEvenAuthenticated()throws Exception{
        login();for(String path:List.of("/study-logs","/study-reschedule-save","/study-question","/gpt-weekly-coaching","/goals"))mvc.perform(peer(post(path)).session(session).header("X-CSRF-TOKEN",token).queryParam("goalId",""+seed.goalA()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        for(String path:List.of("/llm-coaching","/study-log-summary"))mvc.perform(peer(get(path)).session(session).header("X-CSRF-TOKEN",token).queryParam("goalId",""+seed.goalA())).andExpect(status().isForbidden());
        mvc.perform(peer(get("/study-question/capabilities")).session(session).queryParam("goalId",""+seed.goalA())).andExpect(jsonPath("$.gemini.enabled").value(false)).andExpect(jsonPath("$.gpt.enabled").value(false));
        assertEquals(0,ctx.getBean(AiResponseRepository.class).count());assertEquals(0,ctx.getBean(StudyQuestionRepository.class).count());assertEquals(before,IsolatedRescheduleFixture.baseline(ctx));
    }
    @Test void boundedRequestsNeverRestartOrRetry()throws Exception{
        for(int i=0;i<ReadOnlyLanH2Server.REQUEST_LIMIT;i++)mvc.perform(peer(get("/auth/me")));
        mvc.perform(peer(get("/auth/csrf"))).andExpect(status().isTooManyRequests());assertEquals(before,IsolatedRescheduleFixture.baseline(ctx));
    }
    @Test void realShutdownHelperPublishesH2PreservationAndPeerAccessWithoutAiRows()throws Exception{
        login();mvc.perform(peer(get("/goals")).session(session)).andExpect(status().isOk());
        var result=ReadOnlyLanH2Server.finish(ctx,url,scope,12345,before,boundary);assertTrue(result.preserved());
        var proof=GeminiShutdown.validJson(Files.readAllBytes(scope.dir.resolve("verification.json")));assertEquals(0,proof.path("questions").asInt(-1));assertEquals(0,proof.path("responses").asInt(-1));assertEquals(0,proof.path("realAiCalls").asInt(-1));assertEquals(GeminiShutdown.sha(Files.readAllBytes(scope.dir.resolve("lan-access.json"))),proof.path("accessSha256").asText());
        assertTrue(GeminiShutdown.validJson(Files.readAllBytes(scope.dir.resolve("saved-ai-responses.json"))).isEmpty());assertTrue(GeminiShutdown.validJson(Files.readAllBytes(scope.dir.resolve("stopped.json"))).path("h2Shutdown").asBoolean());
        var access=GeminiShutdown.validJson(Files.readAllBytes(scope.dir.resolve("lan-access.json")));assertEquals(1,access.path("successfulLogins").asInt());assertEquals(1,access.path("successfulReads").path("/goals").asInt());
        assertFalse(ctx.isActive());assertThrows(SQLException.class,()->DriverManager.getConnection(url+";IFEXISTS=TRUE","sa",""));
    }
    @Test void modifiedApprovalFailsCleanupProofButStillDisposesH2()throws Exception{
        Files.writeString(scope.dir.resolve("approval.json"),"{}");assertThrows(GeminiShutdown.Failure.class,()->ReadOnlyLanH2Server.finish(ctx,url,scope,12345,before,boundary));assertFalse(Files.exists(scope.dir.resolve("verification.json")));assertTrue(Files.exists(scope.dir.resolve("shutdown-error.json")));assertFalse(ctx.isActive());assertThrows(SQLException.class,()->DriverManager.getConnection(url+";IFEXISTS=TRUE","sa",""));
    }
}
