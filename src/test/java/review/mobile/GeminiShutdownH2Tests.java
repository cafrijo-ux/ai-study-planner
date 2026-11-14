package review.mobile;

import ai_study_planner.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Context is deliberately closed by production shutdown helper; no HTTP socket is ever opened. */
class GeminiShutdownH2Tests {
    static final String PASSWORD=UUID.randomUUID().toString();
    static final String HASH=new BCryptPasswordEncoder(12).encode(PASSWORD);
    static final String BODY="[Fake Wire · 실제 AI 아님] 한글 \"강조\"\n두 번째 문단 \\ 경로\t😀 {\"id\":1,\"ID\":2}";
    GenericWebApplicationContext web;
    @TempDir Path temp;
    Path dir;String id,url;GeminiOncePolicy.Ledger ledger;PreparedGeminiServer.Gate gate;
    IsolatedRescheduleFixture.Baseline baseline;AtomicInteger calls;
    ConfigurableApplicationContext ctx(){return (ConfigurableApplicationContext)web;}
    @BeforeEach void seedAndSaveOneFakeResponse()throws Exception{
        var properties=PreparedGeminiServer.properties(8101,UUID.randomUUID().toString(),HASH);
        var environment=new org.springframework.core.env.StandardEnvironment();environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("shutdownTestOnly",properties));
        var app=new SpringApplication(AiStudyPlannerApplication.class);app.setEnvironment(environment);app.setWebApplicationType(WebApplicationType.SERVLET);
        // Deliberately not a ServletWebServerApplicationContext: real beans + MockServletContext, no socket.
        app.setApplicationContextFactory(type->{var context=new GenericWebApplicationContext();context.setServletContext(new org.springframework.mock.web.MockServletContext());return context;});
        web=(GenericWebApplicationContext)app.run("--spring.config.location=optional:classpath:/mobile-isolation-only.properties");
        url=(String)properties.get("spring.datasource.url");IsolatedStudyLogFixture.assertH2(ctx().getBean(JdbcTemplate.class),url);assertThrows(ClassNotFoundException.class,()->Class.forName("com.mysql.cj.jdbc.Driver"));
        var seed=IsolatedRescheduleFixture.seed(ctx(),url);baseline=IsolatedRescheduleFixture.baseline(ctx());
        id=UUID.randomUUID().toString();dir=Files.createDirectory(temp.resolve(id));
        var approval=new LinkedHashMap<String,Object>(Map.of("approvalId",id,"mode",GeminiOncePolicy.MODE,"model",GeminiOncePolicy.MODEL,"maxCalls",1,"maxOutputTokens",1000,"question",GeminiOncePolicy.QUESTION,"generationApproved",true));
        approval.putAll(Map.of("requestContractVersion",1,"provider","gemini","subject","가상 운영체제","situation","복습","understanding","보통"));GeminiOncePolicy.createExclusive(dir.resolve("approval.json"),approval);GeminiOncePolicy.createExclusive(dir.resolve("process-claim.json"),Map.of("pid",12345));ledger=new GeminiOncePolicy.Ledger(dir);
        calls=new AtomicInteger();var client=new GeminiOncePolicy.OneCallClient(ledger,"synthetic-no-real-credentials",(request,payload,key)->{
            calls.incrementAndGet();return "{\"modelVersion\":\""+GeminiOncePolicy.MODEL+"\",\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":"+GeminiShutdown.JSON.writeValueAsString(BODY)+"}]}}]}";
        });
        PreparedGeminiServer.configure(ctx(),true,"synthetic-no-real-credentials",client);gate=new PreparedGeminiServer.Gate();gate.open(seed,true,ledger);
        MockMvc mvc=MockMvcBuilders.webAppContextSetup(web).addFilters(web.getBean("springSecurityFilterChain",Filter.class),gate).build();
        var csrf=mvc.perform(get("/auth/csrf")).andReturn();var session=(MockHttpSession)csrf.getRequest().getSession();String token=GeminiShutdown.JSON.readTree(csrf.getResponse().getContentAsString()).path("token").asText();
        mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN",token).param("username",GeminiOncePolicy.OWNER).param("password",PASSWORD)).andExpect(status().isOk());
        token=GeminiShutdown.JSON.readTree(mvc.perform(get("/auth/csrf").session(session)).andReturn().getResponse().getContentAsString()).path("token").asText();
        String body=GeminiShutdown.JSON.writeValueAsString(Map.of("provider","gemini","confirmPaidCall",true,"question",GeminiOncePolicy.QUESTION,"subject","가상 운영체제","situation","복습","understanding","보통"));
        mvc.perform(post("/study-question").session(session).header("X-CSRF-TOKEN",token).queryParam("goalId",""+seed.goalA()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.responseSaved").value(true));
        mvc.perform(get("/ai-responses").session(session).queryParam("goalId",""+seed.goalA()).queryParam("feature","QUESTION")).andExpect(jsonPath("$[0].responseId").isNumber());
        assertEquals(1,ctx().getBean(AiResponseRepository.class).count());assertEquals(1,ctx().getBean(StudyQuestionRepository.class).count());assertEquals(1,calls.get());assertEquals(baseline,IsolatedRescheduleFixture.baseline(ctx()));
        GeminiShutdown.atomic(dir.resolve("stop-request.json"),Map.of("runId",id,"pid",12345),GeminiShutdown.NONE);assertTrue(GeminiShutdown.stopRequested(dir,id,12345));
    }
    @AfterEach void closeAnyFailedTestContext(){if(web!=null&&web.isActive())web.close();}
    GeminiShutdown.Result finish(IsolatedRescheduleFixture.Baseline expected,GeminiShutdown.Fault fault)throws IOException{return GeminiShutdown.finish(ctx(),url,id,12345,dir,expected,ledger,gate,fault);}
    void assertDatabaseGone()throws Exception{
        assertFalse(ctx().isActive());assertThrows(SQLException.class,()->{try(var c=DriverManager.getConnection(url+";IFEXISTS=TRUE","sa","")){fail("H2 was recreated or not shut down");}});
        assertEquals(1,calls.get());assertTrue(ledger.attempted());assertThrows(IOException.class,ledger::reserve);
    }
    @Test void completeShutdownPublishesValidSnapshotProofThenStoppedAndRemovesH2()throws Exception{
        var result=finish(baseline,GeminiShutdown.NONE);assertTrue(result.preserved());
        var snapshot=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("saved-ai-responses.json")));assertEquals(1,snapshot.size());assertEquals(BODY,snapshot.get(0).path("responseBody").asText());assertTrue(snapshot.get(0).path("createdAt").isTextual());
        var proof=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("verification.json")));var stopped=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("stopped.json")));
        assertTrue(proof.path("preserved").asBoolean());assertEquals(result.snapshot().sha256(),proof.path("snapshotSha256").asText());assertTrue(stopped.path("contextClosed").asBoolean());assertTrue(stopped.path("h2Shutdown").asBoolean());assertEquals("STOPPED",stopped.path("status").asText());assertEquals(result.verification().sha256(),stopped.path("verificationSha256").asText());
        assertFalse(Files.exists(dir.resolve("shutdown-error.json")));assertDatabaseGone();
        var evidence=Path.of("build/reports/gemini-shutdown-review/synthetic-valid-snapshot.json");Files.createDirectories(evidence.getParent());Files.write(evidence,Files.readAllBytes(dir.resolve("saved-ai-responses.json")));
    }
    @Test void failedPreservationIsRecordedButNeverSuccessful()throws Exception{
        // Inject a different expected snapshot instead of modifying any actual FINAL/log row.
        var different=new IsolatedRescheduleFixture.Baseline(baseline.finals(),List.of(),baseline.subjects(),baseline.goals(),baseline.settings());
        var result=finish(different,GeminiShutdown.NONE);assertFalse(result.preserved());
        assertFalse(GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("verification.json"))).path("preserved").asBoolean());
        assertEquals("STOPPED_WITH_PRESERVATION_FAILURE",GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("stopped.json"))).path("status").asText());assertDatabaseGone();
    }
    @ParameterizedTest @ValueSource(strings={"SNAPSHOT_CAPTURE","SNAPSHOT_WRITE","AFTER_FSYNC","VERIFICATION_WRITE","STOPPED_WRITE"})
    void writeFailureClosesH2AndRecordsExactStageWithoutFalseSuccess(String stage)throws Exception{
        var failure=assertThrows(GeminiShutdown.Failure.class,()->finish(baseline,at->{if(at.equals(stage))throw new IOException("Synthetic filesystem failure");}));
        assertEquals(stage.equals("AFTER_FSYNC")?"SNAPSHOT_WRITE":stage,failure.stage);
        assertFalse(Files.exists(dir.resolve("stopped.json")));var error=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("shutdown-error.json")));assertEquals(failure.stage,error.path("failedStage").asText());assertFalse(error.path("verificationComplete").asBoolean());assertTrue(error.path("contextClosed").asBoolean());assertTrue(error.path("h2Shutdown").asBoolean());
        if(Files.exists(dir.resolve("saved-ai-responses.json")))GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("saved-ai-responses.json")));
        try(var files=Files.list(dir)){assertFalse(files.anyMatch(p->p.getFileName().toString().endsWith(".tmp")));}assertDatabaseGone();
    }
}
