package review.mobile;

import ai_study_planner.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No listener and no actual Wire: latest controllers/security/services + independent memory H2. */
@SpringBootTest(classes=AiStudyPlannerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.MOCK)
class GeminiOncePreparationTests {
    static final String PASSWORD=UUID.randomUUID().toString(),FAKE_KEY="synthetic-key-not-a-provider-key";
    static final Map<String,Object> PROPS=PreparedGeminiServer.properties(8101,UUID.randomUUID().toString(),new BCryptPasswordEncoder(12).encode(PASSWORD));
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){PROPS.forEach((k,v)->r.add(k,()->v));}
    @Autowired WebApplicationContext ctx;@Autowired JdbcTemplate jdbc;
    @TempDir Path temp;
    Path directory;GeminiOncePolicy.Ledger ledger;AtomicInteger wires;String mode="success";
    IsolatedRescheduleFixture.Seed seed;IsolatedRescheduleFixture.Baseline baseline;
    PreparedGeminiServer.Gate gate;MockMvc mvc;MockHttpSession session;String token;
    ConfigurableApplicationContext context(){return (ConfigurableApplicationContext)ctx;}
    @BeforeEach void prepare()throws Exception{
        IsolatedStudyLogFixture.assertH2(jdbc,(String)PROPS.get("spring.datasource.url"));assertThrows(ClassNotFoundException.class,()->Class.forName("com.mysql.cj.jdbc.Driver"));
        for(String table:List.of("ai_responses","study_questions","study_logs","subjects","goals","users"))jdbc.update("DELETE FROM "+table);
        jdbc.execute("DROP TABLE IF EXISTS study_saved_plans");jdbc.execute("DROP TABLE IF EXISTS study_schedule_settings");
        seed=IsolatedRescheduleFixture.seed(context(),(String)PROPS.get("spring.datasource.url"));baseline=IsolatedRescheduleFixture.baseline(context());
        String id=UUID.randomUUID().toString();directory=Files.createDirectory(temp.resolve(id));
        var approval=new LinkedHashMap<String,Object>(Map.of("approvalId",id,"mode",GeminiOncePolicy.MODE,"model",GeminiOncePolicy.MODEL,"maxCalls",1,"maxOutputTokens",1000,"question",GeminiOncePolicy.QUESTION,"generationApproved",true));
        approval.putAll(Map.of("requestContractVersion",1,"provider","gemini","subject","가상 운영체제","situation","복습","understanding","보통"));
        GeminiOncePolicy.createExclusive(directory.resolve("approval.json"),approval);
        GeminiOncePolicy.createExclusive(directory.resolve("process-claim.json"),Map.of("testOnly",true));ledger=new GeminiOncePolicy.Ledger(directory);wires=new AtomicInteger();
        var client=new GeminiOncePolicy.OneCallClient(ledger,FAKE_KEY,(request,body,key)->{
            wires.incrementAndGet();assertEquals(FAKE_KEY,key);assertTrue(body.contains(GeminiOncePolicy.QUESTION));
            if(mode.equals("timeout"))throw new HttpTimeoutException("Offline simulated timeout");
            return "{\"modelVersion\":\""+(mode.equals("mismatch")?"another-model":GeminiOncePolicy.MODEL)+"\",\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"[Offline fake Wire, not real AI] 프로세스는 독립 실행 단위이고 스레드는 그 내부 실행 흐름입니다."+(mode.equals("keyEcho")?FAKE_KEY:"")+"\"}]}}]}";
        });
        PreparedGeminiServer.configure(context(),true,FAKE_KEY,client);
        // Per-JUnit synthetic case setup only; the actual launch code never changes cooldown state.
        ((AtomicLong)org.springframework.test.util.ReflectionTestUtils.getField(ctx.getBean(StudyQuestionController.class),"lastGeminiAttempt")).set(0);
        gate=new PreparedGeminiServer.Gate();gate.open(seed,true,ledger);mvc=MockMvcBuilders.webAppContextSetup(ctx).addFilters(ctx.getBean("springSecurityFilterChain",Filter.class),gate).build();
        var csrf=mvc.perform(get("/auth/csrf")).andReturn();session=(MockHttpSession)csrf.getRequest().getSession();token=tree(csrf).path("token").asText();
        mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN",token).param("username",GeminiOncePolicy.OWNER).param("password",PASSWORD)).andExpect(status().isOk());
        token=tree(mvc.perform(get("/auth/csrf").session(session)).andReturn()).path("token").asText();
    }
    com.fasterxml.jackson.databind.JsonNode tree(MvcResult r)throws Exception{return IsolatedStudyLogServer.JSON.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
    String payload(String provider){return "{\"provider\":\""+provider+"\",\"confirmPaidCall\":true,\"question\":\""+GeminiOncePolicy.QUESTION+"\",\"subject\":\"가상 운영체제\",\"situation\":\"복습\",\"understanding\":\"보통\"}";}
    ResultActions send(long goal,String provider)throws Exception{return mvc.perform(post("/study-question").session(session).queryParam("goalId",""+goal).header("X-CSRF-TOKEN",token).contentType("application/json").content(payload(provider)));}
    @AfterEach void protectedRowsUnchanged(){assertEquals(baseline,IsolatedRescheduleFixture.baseline(context()));}
    @Test void latestContractOneQuestionAndGetHistoryNoAdditionalWire()throws Exception{
        mvc.perform(get("/study-question/capabilities").session(session).queryParam("goalId",""+seed.goalA())).andExpect(jsonPath("$.goalScopedResponse").value(true)).andExpect(jsonPath("$.gemini.model").value(GeminiOncePolicy.MODEL)).andExpect(jsonPath("$.gpt.enabled").value(false));
        var r=tree(send(seed.goalA(),"gemini").andExpect(status().isOk()).andReturn());assertEquals(seed.goalA(),r.path("goalId").asLong());assertEquals("gemini",r.path("requestedProvider").asText());assertEquals("gemini",r.path("answerSource").asText());assertEquals("provider",r.path("responseSource").asText());assertEquals(GeminiOncePolicy.MODEL,r.path("responseModel").asText());assertTrue(r.path("responseSaved").asBoolean());assertTrue(r.path("responseId").asLong()>0);assertTrue(r.path("questionId").asLong()>0);assertTrue(r.path("responseComplete").asBoolean());
        for(int i=0;i<2;i++)mvc.perform(get("/ai-responses").session(session).queryParam("goalId",""+seed.goalA()).queryParam("feature","QUESTION")).andExpect(jsonPath("$[0].goalId").value(seed.goalA()));
        mvc.perform(get("/ai-responses").session(session).queryParam("goalId",""+seed.goalB()).queryParam("feature","QUESTION")).andExpect(content().json("[]"));
        send(seed.goalA(),"gemini").andExpect(status().isConflict());assertEquals(1,wires.get());assertTrue(ledger.attempted());
        assertEquals("RESPONSE_RECEIVED_MODEL_VALIDATED",IsolatedStudyLogServer.JSON.readTree(directory.resolve("outcome.json").toFile()).path("status").asText());
    }
    @ParameterizedTest @ValueSource(strings={"timeout","mismatch","keyEcho"})
    void uncertaintyOrWrongModelCannotSpendAgainEvenAfterLedgerReopen(String failure)throws Exception{
        mode=failure;var r=tree(send(seed.goalA(),"gemini").andReturn());assertEquals("rule",r.path("responseSource").asText());assertNotEquals("gemini",r.path("answerSource").asText());assertTrue(ledger.attempted());assertEquals(1,wires.get());
        assertEquals(failure.equals("timeout")?"TIMEOUT_CALL_UNCERTAIN":"RESPONSE_REJECTED",IsolatedStudyLogServer.JSON.readTree(directory.resolve("outcome.json").toFile()).path("status").asText());
        assertFalse(r.toString().contains(FAKE_KEY),"Credential echo must never reach answer/storage");
        assertThrows(IOException.class,()->new GeminiOncePolicy.Ledger(directory).reserve());
        assertThrows(IOException.class,()->GeminiOncePolicy.createExclusive(directory.resolve("process-claim.json"),Map.of("restart",true)));
        send(seed.goalA(),"gemini").andExpect(status().isConflict());assertEquals(1,wires.get());
    }
    @Test void persistenceFailureKeepsAnswerAndReservedAttempt()throws Exception{
        jdbc.execute("ALTER TABLE ai_responses ADD CONSTRAINT reject_fake_save CHECK (response_id<0)");
        try{var r=tree(send(seed.goalA(),"gemini").andReturn());assertTrue(r.path("aiAdvice").asText().contains("Offline fake"));assertFalse(r.path("responseSaved").asBoolean());assertTrue(r.has("saveWarning"));assertEquals(0,ctx.getBean(StudyQuestionRepository.class).count());assertEquals(1,wires.get());assertThrows(IOException.class,ledger::reserve);}
        finally{jdbc.execute("ALTER TABLE ai_responses DROP CONSTRAINT reject_fake_save");}
    }
    @Test void defaultDisabledAndOtherModelsGoalsCsrfWritesBlocked()throws Exception{
        mvc.perform(post("/study-question").session(session).queryParam("goalId",""+seed.goalA()).contentType("application/json").content(payload("gemini"))).andExpect(status().isForbidden());
        send(seed.goalB(),"gemini").andExpect(status().isForbidden());send(seed.goalA(),"gpt").andExpect(status().isBadRequest());
        for(String path:List.of("/gpt-weekly-coaching","/study-logs","/study-reschedule-save"))mvc.perform(post(path).session(session).header("X-CSRF-TOKEN",token).queryParam("goalId",""+seed.goalA()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/llm-coaching").servletPath("/llm-coaching").session(session).header("X-CSRF-TOKEN",token).queryParam("goalId",""+seed.goalA())).andExpect(status().isForbidden());
        PreparedGeminiServer.configure(context(),false,"",null);gate.enabled=false;
        mvc.perform(get("/study-question/capabilities").session(session).queryParam("goalId",""+seed.goalA())).andExpect(jsonPath("$.gemini.enabled").value(false));
        send(seed.goalA(),"gemini").andExpect(status().isForbidden());assertEquals(0,wires.get());assertFalse(ledger.attempted());
    }
    @Test void constrainedEndpointAndTokenLimitRejectedBeforeReserving()throws Exception{
        for(String target:List.of("https://api.openai.com/v1/responses","https://"+GeminiOncePolicy.HOST+GeminiOncePolicy.PATH+"?key=forbidden","http://"+GeminiOncePolicy.HOST+GeminiOncePolicy.PATH)){
            var q=HttpRequest.newBuilder(URI.create(target)).POST(HttpRequest.BodyPublishers.ofString("{}")).build();assertThrows(IOException.class,()->GeminiOncePolicy.OneCallClient.validate(q,"{}"));
        }
        var q=HttpRequest.newBuilder(URI.create("https://"+GeminiOncePolicy.HOST+GeminiOncePolicy.PATH)).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertThrows(IOException.class,()->GeminiOncePolicy.OneCallClient.validate(q,"{\"contents\":[],\"generationConfig\":{\"maxOutputTokens\":1001}}"));assertFalse(ledger.attempted());assertEquals(0,wires.get());
        var guard=new GeminiOncePolicy.NetworkGuard(8101);for(String host:List.of(GeminiOncePolicy.HOST,"api.openai.com","127.0.0.1"))assertThrows(SecurityException.class,()->guard.checkConnect(host,443));
        PreparedGeminiServer.main(new String[0]);assertThrows(IllegalArgumentException.class,()->new GeminiOncePolicy.OneCallClient(ledger,"none",(a,b,c)->""));
    }
    @Test void concurrentLedgerReservationsAllowOnlyOneAttempt()throws Exception{
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try{var work=new ArrayList<java.util.concurrent.Callable<Boolean>>();for(int i=0;i<8;i++)work.add(()->{try{new GeminiOncePolicy.Ledger(directory).reserve();return true;}catch(IOException rejected){return false;}});
            long allowed=0;for(var result:pool.invokeAll(work))if(result.get())allowed++;assertEquals(1,allowed);
        }finally{pool.shutdownNow();}
    }
    @Test void reportedQuestionVariantIsRejectedBeforeControllerTransportAndLedger()throws Exception{
        String variant="프로세스와 스레드의 차이를 두문장으로 설명해주세요";
        mvc.perform(post("/study-question").session(session).queryParam("goalId",""+seed.goalA()).header("X-CSRF-TOKEN",token).contentType("application/json").content(payload("gemini").replace(GeminiOncePolicy.QUESTION,variant))).andExpect(status().isBadRequest());
        assertEquals(0,wires.get());assertFalse(ledger.attempted());assertFalse(Files.exists(directory.resolve("outcome.json")));assertFalse(gate.submitted.get());
        assertEquals(0,ctx.getBean(AiResponseRepository.class).count());assertEquals(0,ctx.getBean(StudyQuestionRepository.class).count());
    }
    com.fasterxml.jackson.databind.node.ObjectNode preflightPayload()throws Exception{
        var b=(com.fasterxml.jackson.databind.node.ObjectNode)IsolatedStudyLogServer.JSON.readTree(payload("gemini"));b.remove("confirmPaidCall");b.put("model",GeminiOncePolicy.MODEL);b.put("serverMode",GeminiOncePolicy.MODE);return b;
    }
    com.fasterxml.jackson.databind.JsonNode preflight(long goal,com.fasterxml.jackson.databind.JsonNode body)throws Exception{
        return tree(mvc.perform(post("/prepared-gemini/preflight").session(session).header("X-CSRF-TOKEN",token).queryParam("goalId",""+goal).contentType("application/json").content(JSON_STRING(body))).andExpect(status().isOk()).andReturn());
    }
    String JSON_STRING(Object body)throws Exception{return IsolatedStudyLogServer.JSON.writeValueAsString(body);}
    @Test void approvalMetadataAndRepeatedPreflightAreAuthenticatedNonGeneratingReads()throws Exception{
        mvc.perform(get("/prepared-gemini/approval").queryParam("goalId",""+seed.goalA())).andExpect(status().isUnauthorized());
        var contract=tree(mvc.perform(get("/prepared-gemini/approval").session(session).queryParam("goalId",""+seed.goalA())).andExpect(status().isOk()).andReturn());
        assertEquals(GeminiOncePolicy.QUESTION,contract.path("question").asText());assertEquals("가상 운영체제",contract.path("subject").asText());assertEquals("복습",contract.path("situation").asText());assertEquals("보통",contract.path("understanding").asText());assertEquals("gemini",contract.path("provider").asText());assertEquals(seed.goalA(),contract.path("approvedGoalId").asLong());assertEquals(seed.goalA(),contract.path("selectedGoalId").asLong());
        assertFalse(contract.toString().contains(FAKE_KEY));assertFalse(contract.toString().contains(directory.toString()));
        mvc.perform(post("/prepared-gemini/preflight").session(session).queryParam("goalId",""+seed.goalA()).contentType("application/json").content(JSON_STRING(preflightPayload()))).andExpect(status().isForbidden());
        for(int i=0;i<4;i++){var check=preflight(seed.goalA(),preflightPayload());assertTrue(check.path("valid").asBoolean());assertEquals(0,check.path("providerCalls").asInt());}
        assertEquals(0,wires.get());assertFalse(ledger.attempted());assertFalse(gate.submitted.get());assertEquals(0,ctx.getBean(AiResponseRepository.class).count());
    }
    @ParameterizedTest @ValueSource(strings={"questionSpace","questionDot","questionReported","subject","situation","understanding","provider","goalId","model","serverMode"})
    void eachMismatchIsReportedWithoutReservation(String field)throws Exception{
        var b=preflightPayload();String expected=field;
        if(field.equals("questionSpace")){b.put("question",GeminiOncePolicy.QUESTION.replace("두 문장","두문장"));expected="question";}
        else if(field.equals("questionDot")){b.put("question",GeminiOncePolicy.QUESTION.substring(0,GeminiOncePolicy.QUESTION.length()-1));expected="question";}
        else if(field.equals("questionReported")){b.put("question","프로세스와 스레드의 차이를 두문장으로 설명해주세요");expected="question";}
        else if(!field.equals("goalId"))b.put(field,field.equals("provider")?"gpt":"승인값과 다른 입력");
        var check=preflight(field.equals("goalId")?seed.goalB():seed.goalA(),b);assertFalse(check.path("valid").asBoolean());
        var fields=new HashSet<String>();for(var error:check.path("errors"))fields.add(error.path("field").asText());assertTrue(fields.contains(expected));
        assertEquals(0,wires.get());assertFalse(ledger.attempted());assertFalse(gate.submitted.get());assertFalse(Files.exists(directory.resolve("outcome.json")));
    }
    @Test void validPreflightDoesNotReplacePaidConsentAndFinalGuardRemains()throws Exception{
        assertTrue(preflight(seed.goalA(),preflightPayload()).path("valid").asBoolean());
        mvc.perform(post("/study-question").session(session).queryParam("goalId",""+seed.goalA()).header("X-CSRF-TOKEN",token).contentType("application/json").content(payload("gemini").replace("\"confirmPaidCall\":true","\"confirmPaidCall\":false"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("confirmPaidCall"));
        assertEquals(0,wires.get());assertFalse(ledger.attempted());assertFalse(gate.submitted.get());
        String wrong=payload("gemini").replace(GeminiOncePolicy.QUESTION,"프로세스와 스레드의 차이를 두문장으로 설명해주세요");
        mvc.perform(post("/study-question").session(session).queryParam("goalId",""+seed.goalA()).header("X-CSRF-TOKEN",token).contentType("application/json").content(wrong)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("APPROVED_REQUEST_MISMATCH")).andExpect(jsonPath("$.errors[0].field").value("question"));
        assertFalse(ledger.attempted());assertEquals(0,wires.get());
    }
    @Test void reservedLedgerAndDisabledModeFailPreflightWithoutReset()throws Exception{
        ledger.reserve();var check=preflight(seed.goalA(),preflightPayload());assertFalse(check.path("valid").asBoolean());assertEquals("callLimit",check.path("errors").get(0).path("field").asText());
        assertThrows(IOException.class,()->new GeminiOncePolicy.Ledger(directory).reserve());assertEquals(0,wires.get());
        gate.enabled=false;var disabled=preflight(seed.goalA(),preflightPayload());assertFalse(disabled.path("valid").asBoolean());assertEquals("serverMode",disabled.path("errors").get(0).path("field").asText());
    }
    @Test void invalidPreflightNeverEchoesSensitiveInput()throws Exception{
        var b=preflightPayload();b.put("question",FAKE_KEY);b.put("apiKey",FAKE_KEY);
        var result=preflight(seed.goalA(),b);assertFalse(result.path("valid").asBoolean());assertFalse(result.toString().contains(FAKE_KEY));assertFalse(result.has("apiKey"));assertEquals(0,wires.get());assertFalse(ledger.attempted());
    }
    @Test void legacyShutdownSerializationReproducesTruncationAtCreatedAt()throws Exception{
        send(seed.goalA(),"gemini").andExpect(status().isOk());
        var path=temp.resolve("synthetic-legacy-snapshot.json");
        var failure=assertThrows(com.fasterxml.jackson.databind.exc.InvalidDefinitionException.class,()->IsolatedStudyLogServer.JSON.writeValue(path.toFile(),ctx.getBean(AiResponseRepository.class).findAll()));
        assertTrue(failure.getPath().stream().anyMatch(p->"createdAt".equals(p.getFieldName())));
        String text=Files.readString(path);assertTrue(text.endsWith("\"createdAt\""));
        var result=GeminiSnapshotAudit.audit(path);assertEquals(false,result.get("valid"));assertEquals(1,wires.get());
    }
}
