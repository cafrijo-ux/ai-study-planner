package review.mobile;

import ai_study_planner.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Spring security/controller/store/lifecycle, MockMvc only; never opens a socket. */
class FakeGeminiShutdownH2Tests {
    static final String PASSWORD=UUID.randomUUID().toString(),HASH=new BCryptPasswordEncoder(12).encode(PASSWORD);
    @TempDir Path temp;
    GenericWebApplicationContext ctx;MockMvc mvc;MockHttpSession session;String token,url,id;Path dir;
    FakeGeminiShutdownServer.Ledger ledger;FakeGeminiShutdownServer.Gate gate;
    IsolatedRescheduleFixture.Seed seed;IsolatedRescheduleFixture.Baseline baseline;
    @BeforeEach void isolatedContextAndLogin()throws Exception{
        id=UUID.randomUUID().toString();dir=Files.createDirectories(temp.resolve(FakeGeminiShutdownServer.BASE).resolve(id));
        GeminiShutdown.atomic(dir.resolve("approval.json"),FakeGeminiShutdownTests.approval(id),GeminiShutdown.NONE);ledger=new FakeGeminiShutdownServer.Ledger(dir);
        GeminiShutdown.atomic(dir.resolve("start-reserved.json"),Map.of("runId",id,"mode",FakeGeminiShutdownServer.MODE,"approvalSha256",ledger.approvalHash),GeminiShutdown.NONE);ledger.claim(12345);
        var props=FakeGeminiShutdownServer.properties(8102,id,HASH);url=(String)props.get("spring.datasource.url");
        var env=new org.springframework.core.env.StandardEnvironment();env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("fakeShutdownOnly",props));
        var app=new SpringApplication(AiStudyPlannerApplication.class);app.setEnvironment(env);app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setApplicationContextFactory(type->{var c=new GenericWebApplicationContext();c.setServletContext(new MockServletContext());return c;});
        ctx=(GenericWebApplicationContext)app.run("--spring.config.location=optional:classpath:/mobile-isolation-only.properties");
        IsolatedStudyLogFixture.assertH2(ctx.getBean(JdbcTemplate.class),url);assertThrows(ClassNotFoundException.class,()->Class.forName("com.mysql.cj.jdbc.Driver"));
        seed=IsolatedRescheduleFixture.seed(ctx,url);baseline=IsolatedRescheduleFixture.baseline(ctx);FakeGeminiShutdownServer.install(ctx,ledger);gate=new FakeGeminiShutdownServer.Gate();gate.open(seed,ledger);
        mvc=MockMvcBuilders.webAppContextSetup(ctx).addFilters(ctx.getBean("springSecurityFilterChain",Filter.class),gate).build();
        var csrf=mvc.perform(get("/auth/csrf")).andReturn();session=(MockHttpSession)csrf.getRequest().getSession();token=json(csrf).path("token").asText();
        mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN",token).param("username",FakeGeminiShutdownServer.OWNER).param("password",PASSWORD)).andExpect(status().isOk());
        token=json(mvc.perform(get("/auth/csrf").session(session)).andReturn()).path("token").asText();
    }
    @AfterEach void cleanup()throws Exception{if(ctx!=null&&ctx.isActive()){try(var c=DriverManager.getConnection(url,"sa","")){ctx.close();c.createStatement().execute("SHUTDOWN");}}}
    static com.fasterxml.jackson.databind.JsonNode json(MvcResult r)throws Exception{return GeminiShutdown.JSON.readTree(r.getResponse().getContentAsByteArray());}
    Map<String,Object> body(){return new LinkedHashMap<>(Map.of("provider","gemini","confirmPaidCall",true,"question",FakeGeminiShutdownServer.QUESTION,"subject","가상 운영체제","situation","복습","understanding","보통"));}
    MvcResult send(Map<String,Object> b,long goal)throws Exception{return mvc.perform(post("/study-question").session(session).header("X-CSRF-TOKEN",token).queryParam("goalId",""+goal).contentType("application/json").content(GeminiShutdown.JSON.writeValueAsBytes(b))).andReturn();}
    com.fasterxml.jackson.databind.JsonNode generate()throws Exception{var r=send(body(),seed.goalA());assertEquals(200,r.getResponse().getStatus());var value=json(r);assertTrue(value.path("responseSaved").asBoolean());assertEquals(FakeGeminiShutdownServer.ANSWER,value.path("aiAdvice").asText());assertTrue(value.path("mockResponse").asBoolean());assertEquals(FakeGeminiShutdownServer.MARKER,value.path("testMode").asText());return value;}
    GeminiShutdown.Result stop(IsolatedRescheduleFixture.Baseline expected,GeminiShutdown.Fault fault)throws Exception{
        GeminiShutdown.atomic(dir.resolve("stop-request.json"),Map.of("runId",id,"pid",12345),GeminiShutdown.NONE);assertTrue(GeminiShutdown.stopRequested(dir,id,12345));
        return FakeGeminiShutdownServer.finish(ctx,url,id,12345,dir,expected,ledger,gate,seed,fault);
    }
    void gone(){assertFalse(ctx.isActive());assertThrows(SQLException.class,()->{try(var c=DriverManager.getConnection(url+";IFEXISTS=TRUE","sa","")){fail("Memory H2 still exists");}});}
    @Test void fakeQuestionStoredReadAndSameShutdownPathProducesCompleteEvidence()throws Exception{
        var caps=json(mvc.perform(get("/study-question/capabilities").session(session).queryParam("goalId",""+seed.goalA())).andExpect(status().isOk()).andReturn());
        assertTrue(caps.path("goalScopedResponse").asBoolean());assertTrue(caps.path("mockResponse").asBoolean());assertFalse(caps.has("preparedGeminiContract"));assertFalse(caps.path("gpt").path("enabled").asBoolean());
        var result=generate();var history=json(mvc.perform(get("/ai-responses").session(session).queryParam("goalId",""+seed.goalA()).queryParam("feature","QUESTION")).andExpect(status().isOk()).andReturn());
        assertEquals(1,history.size());assertTrue(history.get(0).path("mockResponse").asBoolean());assertEquals(result.path("responseId"),history.get(0).path("responseId"));
        mvc.perform(get("/ai-responses").session(session).queryParam("goalId",""+seed.goalB()).queryParam("feature","QUESTION")).andExpect(jsonPath("$.length()").value(0));
        assertEquals(baseline,IsolatedRescheduleFixture.baseline(ctx));assertEquals(1,ctx.getBean(StudyQuestionRepository.class).count());
        var subjects=json(mvc.perform(get("/subjects").session(session).queryParam("goalId",""+seed.goalA())).andReturn());
        var contract=Map.of("syntheticOnly",true,"executionMode",FakeGeminiShutdownServer.MODE,"goalA",seed.goalA(),"goalB",seed.goalB(),"subjects",subjects,"capabilities",caps,"gemini",result,"historyQUESTION",history);
        Path export=Path.of("build/reports/gemini-fake-shutdown-review/flutter-contract.json");Files.createDirectories(export.getParent());Files.write(export,GeminiShutdown.JSON.writeValueAsBytes(contract));
        var shutdown=stop(baseline,GeminiShutdown.NONE);assertTrue(shutdown.preserved());
        var snapshot=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("saved-ai-responses.json")));assertEquals(FakeGeminiShutdownServer.ANSWER,snapshot.get(0).path("responseBody").asText());assertEquals(FakeGeminiShutdownServer.QUESTION,snapshot.get(0).path("question").asText());assertTrue(snapshot.get(0).path("createdAt").isTextual());
        var v=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("verification.json")));assertTrue(v.path("preserved").asBoolean());assertTrue(v.path("fakeContentsVerified").asBoolean());assertEquals(1,v.path("fakeWireCalls").asInt());assertEquals(0,v.path("realAiCalls").asInt(-1));assertEquals(1,v.path("questions").asInt());assertEquals(Files.size(dir.resolve("saved-ai-responses.json")),v.path("snapshotBytes").asLong());
        assertTrue(GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("stopped.json"))).path("h2Shutdown").asBoolean());assertThrows(IOException.class,ledger::reserve);gone();
    }
    @Test void secondSubmissionAndRecreatedClientCannotConsumeAnotherFakeCall()throws Exception{
        generate();String attempt=GeminiShutdown.sha(Files.readAllBytes(dir.resolve("attempt.json")));assertEquals(409,send(body(),seed.goalA()).getResponse().getStatus());assertThrows(IOException.class,()->new FakeGeminiShutdownServer.Ledger(dir).reserve());assertEquals(attempt,GeminiShutdown.sha(Files.readAllBytes(dir.resolve("attempt.json"))));assertEquals(1,ctx.getBean(AiResponseRepository.class).count());stop(baseline,GeminiShutdown.NONE);gone();
    }
    @Test void flutterUserScopedHistoryReadsNeedNoGoalParameterAndDoNotWrite()throws Exception{
        generate();mvc.perform(get("/study-questions").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].question").value(FakeGeminiShutdownServer.QUESTION)).andExpect(jsonPath("$[0].mockResponse").value(true));
        mvc.perform(get("/study-logs").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/ai-responses").session(session).queryParam("goalId",""+seed.goalB()).queryParam("feature","QUESTION")).andExpect(jsonPath("$.length()").value(0));assertEquals(baseline,IsolatedRescheduleFixture.baseline(ctx));assertEquals(1,ctx.getBean(AiResponseRepository.class).count());stop(baseline,GeminiShutdown.NONE);gone();
    }
    @Test void wrongQuestionProviderGoalOrConfirmationCannotReserveFakeCall()throws Exception{
        for(var change:Map.of("question",FakeGeminiShutdownServer.QUESTION+" ","provider","gpt","subject","다른 과목","situation","다른 상황","understanding","높음").entrySet()){var b=body();b.put(change.getKey(),change.getValue());assertEquals(400,send(b,seed.goalA()).getResponse().getStatus());}
        var b=body();b.put("confirmPaidCall",false);assertEquals(400,send(b,seed.goalA()).getResponse().getStatus());assertEquals(403,send(body(),seed.goalB()).getResponse().getStatus());assertEquals(400,send(body(),999999).getResponse().getStatus());assertFalse(ledger.attempted());assertEquals(0,ctx.getBean(AiResponseRepository.class).count());assertEquals(baseline,IsolatedRescheduleFixture.baseline(ctx));
    }
    @Test void sessionCsrfAndOtherGenerationOrWritePathsRemainBlocked()throws Exception{
        mvc.perform(get("/study-question/capabilities").queryParam("goalId",""+seed.goalA())).andExpect(status().isUnauthorized());
        mvc.perform(post("/study-question").session(session).queryParam("goalId",""+seed.goalA()).contentType("application/json").content(GeminiShutdown.JSON.writeValueAsBytes(body()))).andExpect(status().isForbidden());
        for(String path:List.of("/study-logs","/study-reschedule-save","/gpt-weekly-coaching"))mvc.perform(post(path).session(session).header("X-CSRF-TOKEN",token).queryParam("goalId",""+seed.goalA()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/llm-coaching").session(session).queryParam("goalId",""+seed.goalA())).andExpect(status().isForbidden());assertFalse(ledger.attempted());assertEquals(baseline,IsolatedRescheduleFixture.baseline(ctx));
    }
    @ParameterizedTest @ValueSource(strings={"SERIALIZE","BEFORE_PUBLISH","SNAPSHOT_WRITE","VERIFICATION_WRITE","STOPPED_WRITE"})
    void injectedFailuresNeverClaimSuccessfulStopOrResetLedger(String stage)throws Exception{
        generate();String hash=GeminiShutdown.sha(Files.readAllBytes(dir.resolve("attempt.json")));assertThrows(GeminiShutdown.Failure.class,()->stop(baseline,at->{if(at.equals(stage))throw new IOException("Synthetic failure");}));
        assertFalse(Files.exists(dir.resolve("stopped.json")));assertTrue(Files.exists(dir.resolve("shutdown-error.json")));assertEquals(hash,GeminiShutdown.sha(Files.readAllBytes(dir.resolve("attempt.json"))));try(var list=Files.list(dir)){assertFalse(list.anyMatch(p->p.toString().endsWith(".tmp")));}gone();
    }
    @Test void preservationMismatchRecordsFailureWithoutChangingFinal()throws Exception{
        generate();assertEquals(baseline,IsolatedRescheduleFixture.baseline(ctx));var bad=new IsolatedRescheduleFixture.Baseline(baseline.finals(),List.of(),baseline.subjects(),baseline.goals(),baseline.settings());assertFalse(stop(bad,GeminiShutdown.NONE).preserved());assertFalse(GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("verification.json"))).path("preserved").asBoolean());gone();
    }
    @Test void changedFakeApprovalPreventsSuccessfulPreservationProof()throws Exception{
        generate();Files.writeString(dir.resolve("approval.json"),"{}");assertThrows(GeminiShutdown.Failure.class,()->stop(baseline,GeminiShutdown.NONE));assertFalse(Files.exists(dir.resolve("verification.json")));assertFalse(Files.exists(dir.resolve("stopped.json")));gone();
    }
}
