package ai_study_planner;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.core.env.Environment;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.security.Permission;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** Isolated real persistence and security, fake outbound transport; never uses existing MySQL. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
@SuppressWarnings({"removal","unchecked"})
class StudyQuestionFlowTests {
    static final String DB="jdbc:h2:mem:question_flow_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
    static final String PASSWORD=UUID.randomUUID().toString();
    static final String HASH=new BCryptPasswordEncoder(12).encode(PASSWORD);
    static final SecurityManager PREVIOUS=System.getSecurityManager();
    static final AtomicInteger BLOCKED=new AtomicInteger();
    static final List<String> BLOCKED_ENDPOINTS=new CopyOnWriteArrayList<>();
    static final String GEMINI="{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"[MOCK] Gemini: 프로세스와 스레드는 자원 공유 범위가 다릅니다.\"}]},\"finishReason\":\"STOP\"}]}";
    static final String GPT="{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"[MOCK] GPT-4o-mini: CPU 스케줄링을 예제로 비교하세요.\"}]}]}";
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r) {
        System.setSecurityManager(new SecurityManager(){
            @Override public void checkPermission(Permission p){}
            @Override public void checkPermission(Permission p,Object c){}
            @Override public void checkConnect(String host,int port){
                // Tomcat checks its numeric local address with port -1; this is not a socket connection.
                if(port==-1 && Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(host))return;
                BLOCKED.incrementAndGet();BLOCKED_ENDPOINTS.add(host+":"+port);
                throw new SecurityException("Outbound network forbidden in question review");
            }
            @Override public void checkConnect(String host,int port,Object c){checkConnect(host,port);}
            @Override public void checkAccept(String host,int port){if(!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(host))throw new SecurityException("Loopback only");}
            @Override public void checkListen(int port){if(!Boolean.getBoolean("question.browser.enabled"))throw new SecurityException("HTTP listener requires separate opt-in");}
        });
        Map<String,Object> config=new LinkedHashMap<>(Map.of("spring.datasource.url",DB,"spring.datasource.driver-class-name","org.h2.Driver","spring.datasource.username","sa","spring.datasource.password","","spring.jpa.hibernate.ddl-auto","create-drop","spring.sql.init.mode","never","spring.jpa.database-platform","org.hibernate.dialect.H2Dialect","spring.jpa.properties.hibernate.dialect","org.hibernate.dialect.H2Dialect"));
        config.putAll(Map.of("planner.auth.username","question-test-owner","planner.auth.password-hash",HASH,"server.address","127.0.0.1","server.port","0","spring.h2.console.enabled",false,"spring.jpa.show-sql",false));
        config.putAll(Map.of("gemini.api.key","FAKE-GEMINI-TEST-ONLY","gemini.model","gemini-3.5-flash-lite","study.question.gemini.enabled",true,"study.question.gpt.enabled",true,"study.question.gpt.model","gpt-4o-mini","study.question.gpt.api-key","FAKE-GPT-QUESTION-ONLY","gpt.weekly.enabled",false,"gpt.weekly.api-key","none","llm.api.key","none","llm.demo-mode",true));
        config.forEach((k,v)->r.add(k,()->v));
    }
    @AfterAll static void restoreGuard(){System.setSecurityManager(PREVIOUS);}
    @Autowired StudyQuestionController controller;
    @Autowired StudyQuestionRepository repository;
    @Autowired AiResponseRepository responses;
    @Autowired GoalRepository goals;
    @Autowired SubjectRepository subjects;
    @Autowired StudyLogRepository logs;
    @Autowired LlmCoachingController daily;
    @Autowired GptWeeklyCoachingController weekly;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired Environment environment;
    MockMvc mvc;MockHttpSession session;String token;ObjectMapper json=new ObjectMapper();HttpClient http;
    List<HttpRequest> sent=new CopyOnWriteArrayList<>();
    @BeforeEach void setup() throws Exception {
        try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){assertTrue(c.getMetaData().getURL().startsWith("jdbc:h2:mem:question_flow_"));}
        responses.deleteAll(); repository.deleteAll(); logs.deleteAll(); subjects.deleteAll(); goals.deleteAll();
        jdbc.execute("CREATE TABLE IF NOT EXISTS study_saved_plans (user_id BIGINT,goal_id BIGINT,plan_date DATE,source_date DATE,source_end_time TIME,plan_status VARCHAR(10),start_time TIME,end_time TIME,busy_start TIME,busy_end TIME,plan_json CLOB,saved_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("DELETE FROM study_saved_plans");
        http=mock(HttpClient.class);ReflectionTestUtils.setField(controller,"httpClient",http);
        ReflectionTestUtils.setField(daily,"httpClient",http); ReflectionTestUtils.setField(daily,"provider","gemini");
        ReflectionTestUtils.setField(daily,"model","gemini-3.5-flash-lite"); ReflectionTestUtils.setField(daily,"geminiApiKey","FAKE");
        ReflectionTestUtils.setField(daily,"demoMode",false);
        ReflectionTestUtils.setField(weekly,"httpClient",http); ReflectionTestUtils.setField(weekly,"enabled",true);
        ReflectionTestUtils.setField(weekly,"apiKey","FAKE");
        ((AtomicLong)ReflectionTestUtils.getField(weekly,"lastAttemptMillis")).set(0);
        ((AtomicBoolean)ReflectionTestUtils.getField(weekly,"inFlight")).set(false);
        ReflectionTestUtils.setField(controller,"gptEnabled",true);ReflectionTestUtils.setField(controller,"geminiEnabled",true);
        ReflectionTestUtils.setField(controller,"gptApiKey","FAKE-GPT-QUESTION-ONLY");ReflectionTestUtils.setField(controller,"geminiApiKey","FAKE-GEMINI-TEST-ONLY");
        ReflectionTestUtils.setField(controller,"gptModel","gpt-4o-mini");
        for(String name:List.of("lastGptAttempt","lastGeminiAttempt"))((AtomicLong)ReflectionTestUtils.getField(controller,name)).set(0);
        for(String name:List.of("gptInFlight","geminiInFlight"))((AtomicBoolean)ReflectionTestUtils.getField(controller,name)).set(false);
        when(http.send(any(),any())).thenAnswer(invocation->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"External transport must be outside DB transactions");HttpRequest q=invocation.getArgument(0);sent.add(q);HttpResponse<String> r=mock(HttpResponse.class);when(r.statusCode()).thenReturn(200);when(r.body()).thenReturn(q.uri().getHost().equals("api.openai.com")?GPT:GEMINI);return r;});
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var csrf=mvc.perform(get("/auth/csrf")).andReturn();session=(MockHttpSession)csrf.getRequest().getSession();token=json.readTree(csrf.getResponse().getContentAsString()).path("token").asText();
        mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN",token).param("username","question-test-owner").param("password",PASSWORD)).andExpect(status().isOk());
        token=json.readTree(mvc.perform(get("/auth/csrf").session(session)).andReturn().getResponse().getContentAsString()).path("token").asText();
    }
    JsonNode ask(Object provider) throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("provider",provider);body.put("confirmPaidCall",true);body.put("subject","운영체제");body.put("question","프로세스와 스레드의 차이");
        return json.readTree(mvc.perform(post("/study-question").session(session).header("X-CSRF-TOKEN",token).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @ParameterizedTest @ValueSource(strings={"gemini","gpt"}) void selectedProviderOnlyAndRealH2History(String provider) throws Exception {
        var result=ask(provider);assertEquals(provider,result.path("requestedProvider").asText());assertEquals(provider,result.path("answerSource").asText());assertEquals("provider",result.path("responseSource").asText());assertTrue(result.path("saved").asBoolean());assertEquals(1,sent.size());
        assertEquals(provider.equals("gpt")?"api.openai.com":"generativelanguage.googleapis.com",sent.get(0).uri().getHost());
        assertEquals(provider.equals("gpt")?"gpt-4o-mini":"gemini-3.5-flash-lite",result.path("model").asText());
        assertEquals(1,repository.count());var stored=repository.findById(result.path("questionId").asLong()).orElseThrow();assertEquals(1L,stored.getUserId());assertEquals(result.path("aiAdvice").asText(),stored.getAiAdvice());assertEquals("프로세스와 스레드의 차이",stored.getQuestion());
        assertEquals(1,responses.count()); var snapshot=responses.findById(result.path("responseId").asLong()).orElseThrow();
        assertEquals(stored.getQuestionId(),snapshot.getQuestionId()); assertEquals(provider,snapshot.getRequestedProvider());
        assertEquals(provider.equals("gpt")?"openai":"gemini",snapshot.getActualProvider());
        assertEquals(AiResponse.Source.PROVIDER,snapshot.getResponseSource()); assertNull(snapshot.getGoalId());
        assertEquals(result.path("aiAdvice").asText(),snapshot.getResponseBody()); assertNotNull(snapshot.getCreatedAt());
        assertEquals("none",environment.getProperty("gpt.weekly.api-key"));assertEquals("false",environment.getProperty("gpt.weekly.enabled"));
    }
    @ParameterizedTest @ValueSource(strings={"null","12","true","[]","{}"}) void malformedProviderNeverFallsBackOrWrites(String raw) throws Exception {
        var result=ask(json.readValue(raw,Object.class));assertEquals("모델 선택 오류",result.path("apiStatus").asText());verifyNoInteractions(http);assertEquals(0,repository.count());
    }
    @Test void cooldownDoesNotBlockDifferentProviderOrDuplicatePaidRequest() throws Exception {
        ask("gemini");assertEquals("호출 간격 제한",ask("gemini").path("apiStatus").asText());ask("gpt");assertEquals(2,sent.size());assertEquals(2,repository.count());
    }
    @Test void busyProviderCannotSendOrSave() throws Exception {((AtomicBoolean)ReflectionTestUtils.getField(controller,"gptInFlight")).set(true);assertEquals("중복 요청 차단",ask("gpt").path("apiStatus").asText());verifyNoInteractions(http);assertEquals(0,repository.count());}
    @Test void questionKeyCannotFallBackToGeminiOrWeeklyKey() throws Exception {ReflectionTestUtils.setField(controller,"gptApiKey","none");assertEquals("GPT 질문 API 키 없음",ask("gpt").path("apiStatus").asText());verifyNoInteractions(http);assertEquals(0,repository.count());}
    @ParameterizedTest @ValueSource(ints={401,429,500}) void gptHttpFailureDoesNotFallbackSaveOrRetry(int code) throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(code);when(response.body()).thenReturn("DO_NOT_EXPOSE_REMOTE_BODY");doReturn(response).when(http).send(any(),any());
        var result=ask("gpt");assertEquals("GPT 질문 API 실패",result.path("apiStatus").asText());assertEquals("attempted",result.path("responseSource").asText());assertFalse(result.toString().contains("DO_NOT_EXPOSE"));assertEquals(0,repository.count());ask("gpt");verify(http,times(1)).send(any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"not-json","{}","{\"status\":\"incomplete\"}","{\"status\":\"completed\",\"output\":[]}"}) void gptMalformedOrIncompleteResponseNotSaved(String body) throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(body);doReturn(response).when(http).send(any(),any());
        assertEquals("GPT 질문 API 실패",ask("gpt").path("apiStatus").asText());assertEquals(0,repository.count());verify(http,times(1)).send(any(),any());
    }
    @Test void timeoutNeverSwitchesModelOrRetries() throws Exception {doThrow(new HttpTimeoutException("DO_NOT_EXPOSE")).when(http).send(any(),any());var r=ask("gpt");assertTrue(r.path("message").asText().contains("응답 시간 초과"));assertEquals(0,repository.count());verify(http,times(1)).send(any(),any());}
    @ParameterizedTest @ValueSource(strings={
        "{\"error\":{\"message\":\"DO_NOT_EXPOSE\"}}",
        "{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}",
        "{\"output\":[{\"type\":\"message\",\"status\":\"incomplete\",\"content\":[{\"type\":\"output_text\",\"text\":\"PARTIAL\"}]}]}",
        "{\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"DO_NOT_EXPOSE\"},{\"type\":\"output_text\",\"text\":\"PARTIAL\"}]}]}"
    }) void gptContradictoryOrRefusedOutputIsNeverSaved(String fragment) throws Exception {
        var root=json.readTree(GPT).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)root).setAll((com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(fragment));
        var remote=mock(HttpResponse.class);when(remote.statusCode()).thenReturn(200);when(remote.body()).thenReturn(root.toString());doReturn(remote).when(http).send(any(),any());
        var r=ask("gpt");assertEquals("attempted",r.path("responseSource").asText());assertFalse(r.has("responseComplete"));assertFalse(r.toString().contains("PARTIAL"));assertFalse(r.toString().contains("DO_NOT_EXPOSE"));assertEquals(0,repository.count());assertEquals(0,responses.count());verify(http,times(1)).send(any(),any());
    }
    @Test void geminiThoughtPartsAreNotShownOrStored() throws Exception {
        var remote=mock(HttpResponse.class);when(remote.statusCode()).thenReturn(200);when(remote.body()).thenReturn("{\"modelVersion\":\"gemini-3.5-flash-lite\",\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"thought\":true,\"text\":\"PRIVATE_THOUGHT\"},{\"text\":\"완성된 설명\"}]}}]}");doReturn(remote).when(http).send(any(),any());
        var r=ask("gemini");assertEquals("완성된 설명",r.path("aiAdvice").asText());assertFalse(r.toString().contains("PRIVATE_THOUGHT"));assertEquals("완성된 설명",responses.findById(r.path("responseId").asLong()).orElseThrow().getResponseBody());
    }
    @ParameterizedTest @ValueSource(strings={"THOUGHT_ONLY","PROMPT_BLOCKED","CANDIDATE_BLOCKED"})
    void geminiBlockedOrThoughtOnlyOutputNeverBecomesProviderAnswer(String kind) throws Exception {
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(GEMINI);
        if(kind.equals("THOUGHT_ONLY"))((com.fasterxml.jackson.databind.node.ObjectNode)root.path("candidates").get(0).path("content").path("parts").get(0)).put("thought",true);
        if(kind.equals("PROMPT_BLOCKED"))root.set("promptFeedback",json.readTree("{\"blockReason\":\"SAFETY\"}"));
        if(kind.equals("CANDIDATE_BLOCKED"))((com.fasterxml.jackson.databind.node.ObjectNode)root.path("candidates").get(0)).set("safetyRatings",json.readTree("[{\"blocked\":true}]"));
        var remote=mock(HttpResponse.class);when(remote.statusCode()).thenReturn(200);when(remote.body()).thenReturn(root.toString());doReturn(remote).when(http).send(any(),any());
        var r=ask("gemini");assertEquals("rule",r.path("responseSource").asText());assertFalse(r.has("responseComplete"));assertFalse(r.has("responseModel"));var stored=responses.findById(r.path("responseId").asLong()).orElseThrow();assertEquals(AiResponse.Source.RULE,stored.getResponseSource());assertNull(stored.getActualProvider());verify(http,times(1)).send(any(),any());
    }
    @Test void missingProviderDefaultsToGeminiAndClientModelCannotOverrideServer() throws Exception {
        var body=Map.of("question","개념 설명","confirmPaidCall",true,"model","unapproved-client-model");
        var r=json.readTree(mvc.perform(post("/study-question").session(session).header("X-CSRF-TOKEN",token).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("gemini",r.path("requestedProvider").asText());assertEquals("gemini-3.5-flash-lite",r.path("model").asText());assertEquals(1,sent.size());assertEquals("generativelanguage.googleapis.com",sent.get(0).uri().getHost());assertFalse(sent.get(0).uri().toString().contains("unapproved-client-model"));
    }
    @Test void keylessGeminiIsClearlyRuleOnlyAndDoesNotUseWeeklyOrGptKey() throws Exception {
        ReflectionTestUtils.setField(controller,"geminiApiKey","none");var r=ask("gemini");assertEquals("rule",r.path("responseSource").asText());assertEquals("rule-based",r.path("model").asText());assertFalse(r.has("responseComplete"));verifyNoInteractions(http);
    }
    @ParameterizedTest @ValueSource(strings={"gemini","gpt"})
    void outboundContractUsesOnlySelectedProviderAndServerBudget(String provider) throws Exception {
        var body=Map.of("provider",provider,"model","unapproved-client-model","question","계약 검증 질문","confirmPaidCall",true);
        mvc.perform(post("/study-question").session(session).header("X-CSRF-TOKEN",token)
                .contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk());
        assertEquals(1,sent.size());HttpRequest request=sent.get(0);
        var bytes=new java.io.ByteArrayOutputStream();var complete=new CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}
            public void onNext(java.nio.ByteBuffer b){byte[] data=new byte[b.remaining()];b.get(data);bytes.writeBytes(data);}
            public void onError(Throwable error){complete.completeExceptionally(error);}
            public void onComplete(){complete.complete(bytes.toString(java.nio.charset.StandardCharsets.UTF_8));}
        });
        JsonNode payload=json.readTree(complete.get(2,TimeUnit.SECONDS));
        assertEquals("POST",request.method());assertEquals(Duration.ofSeconds(60),request.timeout().orElseThrow());
        assertEquals("https",request.uri().getScheme());assertNull(request.uri().getQuery());
        assertFalse(payload.toString().contains("unapproved-client-model"));
        if(provider.equals("gpt")) {
            assertEquals("https://api.openai.com/v1/responses",request.uri().toString());
            assertEquals("gpt-4o-mini",payload.path("model").asText());assertEquals(900,payload.path("max_output_tokens").asInt());
            assertFalse(payload.path("store").asBoolean(true));assertTrue(payload.path("instructions").isTextual());
            assertTrue(payload.path("input").asText().contains("계약 검증 질문"));
            assertEquals("Bearer FAKE-GPT-QUESTION-ONLY",request.headers().firstValue("Authorization").orElseThrow());
            assertTrue(request.headers().firstValue("x-goog-api-key").isEmpty());
        } else {
            assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent",request.uri().toString());
            assertEquals(1000,payload.path("generationConfig").path("maxOutputTokens").asInt());
            assertTrue(payload.path("contents").get(0).path("parts").get(0).path("text").asText().contains("계약 검증 질문"));
            assertEquals("FAKE-GEMINI-TEST-ONLY",request.headers().firstValue("x-goog-api-key").orElseThrow());
            assertTrue(request.headers().firstValue("Authorization").isEmpty());
        }
    }
    @Test void geminiFailureClearlySavesRuleFallbackWithoutGptCall() throws Exception {doThrow(new HttpTimeoutException("test")).when(http).send(any(),any());var r=ask("gemini");assertEquals("rule",r.path("answerSource").asText());assertEquals("rule-based",r.path("model").asText());assertTrue(r.path("saved").asBoolean());verify(http,times(1)).send(argThat(q->q.uri().getHost().equals("generativelanguage.googleapis.com")),any());}
    @ParameterizedTest @ValueSource(strings={"gemini","gpt"})
    void successfulQuestionPersistsVerifiedCompletion(String provider) throws Exception {
        JsonNode result=ask(provider);
        assertTrue(result.path("responseComplete").asBoolean(), result.toString());
        assertTrue(result.path("saved").asBoolean());
        AiResponse row=responses.findById(result.path("responseId").asLong()).orElseThrow();
        assertEquals(Boolean.TRUE, row.getResponseComplete());
        assertEquals(AiResponse.Source.PROVIDER, row.getResponseSource());
        verify(http, times(1)).send(any(), any());
    }
    @ParameterizedTest @ValueSource(strings={"MAX_TOKENS","SAFETY","FINISH_REASON_UNSPECIFIED"})
    void geminiNonStopNeverStoredAsRealAnswer(String reason) throws Exception {
        String responseJson=json.writeValueAsString(Map.of("candidates",List.of(Map.of(
                "finishReason",reason,"content",Map.of("parts",List.of(Map.of("text","[PARTIAL] do not use")))))));
        HttpResponse<String> remote=mock(HttpResponse.class);
        when(remote.statusCode()).thenReturn(200);
        when(remote.body()).thenReturn(responseJson);
        doReturn(remote).when(http).send(any(),any());
        JsonNode result=ask("gemini");
        assertEquals("rule",result.path("responseSource").asText());
        assertEquals("rule",result.path("answerSource").asText());
        assertEquals("rule-based",result.path("model").asText());
        assertFalse(result.path("aiAdvice").asText().contains("[PARTIAL]"));
        assertFalse(result.has("responseComplete"));
        assertEquals(1,repository.count());
        AiResponse row=responses.findById(result.path("responseId").asLong()).orElseThrow();
        assertNull(row.getActualProvider());
        assertNull(row.getResponseComplete());
        assertEquals(AiResponse.Source.RULE,row.getResponseSource());
        verify(http,times(1)).send(argThat(q->q.uri().getHost().equals("generativelanguage.googleapis.com")),any());
    }
    @Test void databaseFailurePreservesReceivedAnswerWithoutSecondCall() throws Exception {
        jdbc.execute("ALTER TABLE study_questions ADD CONSTRAINT reject_question_review CHECK(user_id <> 1)");
        try{var result=ask("gpt");assertFalse(result.path("saved").asBoolean());assertTrue(result.path("aiAdvice").asText().contains("[MOCK]"));assertTrue(result.path("saveWarning").asText().contains("새 API 호출"));assertEquals(0,repository.count());verify(http,times(1)).send(any(),any());}
        finally{jdbc.execute("ALTER TABLE study_questions DROP CONSTRAINT reject_question_review");}
    }
    @Test void historyOnlyReturnsOwnerRows() throws Exception {var other=new StudyQuestion();other.setUserId(2L);other.setQuestion("다른 가상 사용자 질문");repository.save(other);ask("gemini");var rows=json.readTree(mvc.perform(get("/study-questions").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertEquals(1,rows.size());assertEquals(1,rows.get(0).path("userId").asInt());}

    Goal fixtureGoal(long userId) {
        Goal goal=new Goal(); goal.setUserId(userId); goal.setGoalName("가상 운영체제 목표");
        goal.setExamDate(java.time.LocalDate.now().plusDays(20)); goal.setDailyStudyTime(120);
        goal.setCreatedAt(java.time.LocalDateTime.now().minusDays(30)); goal=goals.saveAndFlush(goal);
        Subject subject=new Subject(); subject.setUserId(userId); subject.setGoalId(goal.getGoalId());
        subject.setSubjectName("운영체제"); subject=subjects.saveAndFlush(subject);
        StudyLog log=new StudyLog(); log.setUserId(userId); log.setSubjectId(subject.getSubjectId());
        log.setStudyDate(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))); log.setStudyMinutes(40);
        log.setUnderstandingScore(3);log.setFocusScore(3);log.setDifficultTopic("프로세스와 스레드");logs.saveAndFlush(log);
        return goal;
    }
    JsonNode storedHistory(Long goalId, String feature) throws Exception {
        var request=get("/ai-responses").session(session).param("feature",feature);
        if(goalId!=null)request.param("goalId",goalId.toString());
        return json.readTree(mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
    }
    JsonNode coaching(String path, Long goalId) throws Exception {
        var request=path.equals("/llm-coaching") ? get(path).servletPath(path) : post(path)
                .contentType("application/json").content("{\"confirmPaidCall\":true}");
        return json.readTree(mvc.perform(request.session(session).header("X-CSRF-TOKEN",token)
                .param("goalId",goalId.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @ParameterizedTest @ValueSource(strings={"/llm-coaching","/gpt-weekly-coaching"})
    void coachingPersistsAndGetReloadDoesNotCallAiOrChangeFinal(String path) throws Exception {
        Goal goal=fixtureGoal(1);
        String finalJson="{\"studyMinutes\":100,\"carryoverMinutes\":9,\"newStudyMinutes\":91,\"isScheduleValid\":true,\"hasConflict\":false}";
        var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        jdbc.update("INSERT INTO study_saved_plans(user_id,goal_id,plan_date,source_date,source_end_time,plan_status,start_time,end_time,plan_json) VALUES (1,?,?,?,'22:00','FINAL','10:00','12:00',?)",goal.getGoalId(),today,today.minusDays(1),finalJson);
        JsonNode answer=coaching(path,goal.getGoalId()); assertTrue(answer.path("responseSaved").asBoolean(),answer.toString());
        String feature=path.equals("/llm-coaching")?"DAILY_COACHING":"WEEKLY_COACHING";
        JsonNode rows=storedHistory(goal.getGoalId(),feature); assertEquals(1,rows.size());
        assertEquals(answer.path("llmResponse").asText(),rows.get(0).path("responseBody").asText());
        assertEquals("PROVIDER",rows.get(0).path("responseSource").asText());
        assertEquals(path.equals("/llm-coaching")?"gemini":"openai",rows.get(0).path("actualProvider").asText());
        assertEquals(path.equals("/llm-coaching")?"gemini-3.5-flash-lite":"gpt-4o-mini",rows.get(0).path("model").asText());
        storedHistory(goal.getGoalId(),feature); // a fresh GET, as after browser refresh
        mvc.perform(get("/ai-responses/"+answer.path("responseId").asLong()).session(session)).andExpect(status().isOk());
        verify(http,times(1)).send(any(),any());
        assertEquals(finalJson,jdbc.queryForObject("SELECT plan_json FROM study_saved_plans",String.class));
        assertEquals(40,logs.findAll().get(0).getStudyMinutes());assertEquals(0,repository.count());
    }
    @ParameterizedTest @ValueSource(strings={"/llm-coaching","/gpt-weekly-coaching"})
    void coachingStorageFailureRetainsAnswerAndDoesNotRetry(String path) throws Exception {
        Goal goal=fixtureGoal(1); jdbc.execute("ALTER TABLE ai_responses ADD CONSTRAINT reject_ai CHECK(user_id<>1)");
        try {
            JsonNode answer=coaching(path,goal.getGoalId()); assertFalse(answer.path("responseSaved").asBoolean());
            assertTrue(answer.path("llmResponse").asText().contains("[MOCK]"));
            assertTrue(answer.path("saveWarning").asText().contains("새 API 호출")); assertEquals(0,responses.count());
            verify(http,times(1)).send(any(),any());
        } finally {jdbc.execute("ALTER TABLE ai_responses DROP CONSTRAINT reject_ai");}
    }
    @Test void metadataFailureRollsBackLegacyQuestionTooWithoutSecondApiCall() throws Exception {
        jdbc.execute("ALTER TABLE ai_responses ADD CONSTRAINT reject_ai CHECK(user_id<>1)");
        try {
            JsonNode result=ask("gpt"); assertFalse(result.path("saved").asBoolean());
            assertFalse(result.path("responseSaved").asBoolean());assertFalse(result.has("questionId"));
            assertTrue(result.path("aiAdvice").asText().contains("[MOCK]"));
            assertEquals(0,repository.count()); assertEquals(0,responses.count()); verify(http,times(1)).send(any(),any());
        } finally {jdbc.execute("ALTER TABLE ai_responses DROP CONSTRAINT reject_ai");}
    }
    @Test void explicitlySelectedQuestionGoalIsStoredAndSeparated() throws Exception {
        Goal goal=fixtureGoal(1), other=fixtureGoal(1);
        mvc.perform(post("/study-question").param("goalId",goal.getGoalId().toString()).session(session)
                .header("X-CSRF-TOKEN",token).contentType("application/json")
                .content("{\"provider\":\"gemini\",\"question\":\"가상 질문\",\"confirmPaidCall\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true));
        assertEquals(1,storedHistory(goal.getGoalId(),"QUESTION").size());
        assertEquals(0,storedHistory(other.getGoalId(),"QUESTION").size()); verify(http,times(1)).send(any(),any());
    }
    @Test void foreignGoalIsRejectedBeforeAnyQuestionCallOrWrite() throws Exception {
        Goal other=fixtureGoal(2);
        mvc.perform(post("/study-question").param("goalId",other.getGoalId().toString()).session(session)
                .header("X-CSRF-TOKEN",token).contentType("application/json")
                .content("{\"provider\":\"gpt\",\"question\":\"가상 질문\",\"confirmPaidCall\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/ai-responses").param("goalId",other.getGoalId().toString()).session(session)).andExpect(status().isNotFound());
        assertEquals(0,responses.count()); assertEquals(0,repository.count());verifyNoInteractions(http);
    }
    @Test void responseOwnershipLoginAndCsrfAreEnforced() throws Exception {
        var other=responses.saveAndFlush(new AiResponse(2L,null,null,AiResponse.Feature.QUESTION,"gpt","openai","gpt-4o-mini",AiResponse.Source.PROVIDER,"other owner",true));
        mvc.perform(get("/ai-responses")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ai-responses").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("other").roles("OTHER"))).andExpect(status().isForbidden());
        mvc.perform(get("/ai-responses/"+other.getResponseId()).session(session)).andExpect(status().isNotFound());
        assertEquals(0,storedHistory(null,"QUESTION").size());
        mvc.perform(post("/study-question").session(session).contentType("application/json").content("{\"question\":\"x\",\"confirmPaidCall\":true}")).andExpect(status().isForbidden());
        mvc.perform(get("/llm-coaching").servletPath("/llm-coaching").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/gpt-weekly-coaching").session(session).contentType("application/json").content("{\"confirmPaidCall\":true}")).andExpect(status().isForbidden());
        verifyNoInteractions(http);
    }
    @Test void ruleSnapshotDoesNotClaimActualProviderAndOldQuestionsAreNotBackfilled() throws Exception {
        StudyQuestion old=new StudyQuestion();old.setUserId(1L);old.setQuestion("old");old.setAiAdvice("Gemini GPT words are not metadata");repository.saveAndFlush(old);
        assertEquals(0,storedHistory(null,"QUESTION").size());
        ReflectionTestUtils.setField(controller,"geminiApiKey","none");JsonNode result=ask("gemini");
        AiResponse row=responses.findById(result.path("responseId").asLong()).orElseThrow();
        assertEquals(AiResponse.Source.RULE,row.getResponseSource());assertNull(row.getActualProvider());
        assertEquals("rule-based",row.getModel());assertNull(row.getGoalId());assertEquals(2,repository.count());
        assertEquals("Gemini GPT words are not metadata",repository.findById(old.getQuestionId()).orElseThrow().getAiAdvice());verifyNoInteractions(http);
    }
    @Test void dailyRuleAndWeeklyPartialKeepTheirProvenance() throws Exception {
        Goal goal=fixtureGoal(1);ReflectionTestUtils.setField(daily,"demoMode",true);
        coaching("/llm-coaching",goal.getGoalId());JsonNode rule=storedHistory(goal.getGoalId(),"DAILY_COACHING").get(0);
        assertEquals("RULE",rule.path("responseSource").asText());assertTrue(rule.path("actualProvider").isNull());verifyNoInteractions(http);
        HttpResponse<String> partial=mock(HttpResponse.class);when(partial.statusCode()).thenReturn(200);
        when(partial.body()).thenReturn(GPT.replace("completed","incomplete"));doReturn(partial).when(http).send(any(),any());
        coaching("/gpt-weekly-coaching",goal.getGoalId());assertFalse(storedHistory(goal.getGoalId(),"WEEKLY_COACHING").get(0).path("responseComplete").asBoolean());
    }
    @Test void apiFailureAndPreviewNeverCreateStoredCoaching() throws Exception {
        Goal goal=fixtureGoal(1);HttpResponse<String> failure=mock(HttpResponse.class);when(failure.statusCode()).thenReturn(429);when(failure.body()).thenReturn("private remote error");doReturn(failure).when(http).send(any(),any());
        coaching("/llm-coaching",goal.getGoalId());coaching("/gpt-weekly-coaching",goal.getGoalId());
        mvc.perform(get("/gpt-weekly-coaching/preview").param("goalId",goal.getGoalId().toString()).session(session)).andExpect(status().isOk());
        assertEquals(0,responses.count());verify(http,times(2)).send(any(),any());
    }
    @Test void realReturnedModelVersionIsRetained() throws Exception {
        HttpResponse<String> reply=mock(HttpResponse.class);when(reply.statusCode()).thenReturn(200);
        when(reply.body()).thenReturn(GPT.replace("\"status\"","\"model\":\"gpt-4o-mini-2024-07-18\",\"status\""));doReturn(reply).when(http).send(any(),any());
        JsonNode result=ask("gpt");assertEquals("gpt-4o-mini-2024-07-18",responses.findById(result.path("responseId").asLong()).orElseThrow().getModel());
    }
    @Test void legacyOpenaiDailyKeepsValidRawResponseAndStoresAnswer() throws Exception {
        Goal goal=fixtureGoal(1); ReflectionTestUtils.setField(daily,"provider","openai");
        ReflectionTestUtils.setField(daily,"apiKey","FAKE");ReflectionTestUtils.setField(daily,"model","gpt-4o-mini");
        JsonNode result=coaching("/llm-coaching",goal.getGoalId());
        assertEquals(GPT,result.path("rawResponse").asText());assertTrue(result.path("responseSaved").asBoolean());
        assertEquals("openai",storedHistory(goal.getGoalId(),"DAILY_COACHING").get(0).path("actualProvider").asText());
        verify(http,times(1)).send(any(),any());
    }
    @Test void legacyOpenaiDailyEmptyJsonCannotBecomeSavedAnswer() throws Exception {
        Goal goal=fixtureGoal(1); ReflectionTestUtils.setField(daily,"provider","openai");
        ReflectionTestUtils.setField(daily,"apiKey","FAKE");
        HttpResponse<String> reply=mock(HttpResponse.class);when(reply.statusCode()).thenReturn(200);
        when(reply.body()).thenReturn("{}");doReturn(reply).when(http).send(any(),any());
        JsonNode result=coaching("/llm-coaching",goal.getGoalId());
        assertFalse(result.has("rawResponse"));assertFalse(result.has("llmResponse"));assertEquals(0,responses.count());
        verify(http,times(1)).send(any(),any());
    }
    @Test void defaultQuestionTestsUseMockServletContextAndRejectListen(){
        assertInstanceOf(org.springframework.mock.web.MockServletContext.class,context.getServletContext());
        assertThrows(SecurityException.class,()->System.getSecurityManager().checkListen(0));
    }
    // Called only by the separately opted-in HTTP class. Never a default JUnit test.
    void liveBrowserUsesActualSpringSecurityAndH2() throws Exception {
        var script=Path.of(System.getProperty("question.browser.script")).toAbsolutePath();var node=System.getProperty("question.browser.node");
        var pb=new ProcessBuilder(node,script.toString());pb.environment().keySet().removeIf(k->k.matches("(?i).*(OPENAI|GEMINI|GPT|PASSWORD|SECRET).*|NODE_OPTIONS"));pb.redirectErrorStream(true);
        var p=pb.start();try(var out=p.outputWriter(java.nio.charset.StandardCharsets.UTF_8)){out.write(json.writeValueAsString(Map.of("base","http://localhost:"+environment.getProperty("local.server.port"),"username","question-test-owner","password",PASSWORD)));}
        boolean completed=p.waitFor(150,TimeUnit.SECONDS);
        if(!completed){p.destroyForcibly();fail("Browser verification timeout; owned Node process stopped");}
        String output=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertEquals(0,p.exitValue(),output);
        assertEquals(2,sent.size());assertEquals(2,repository.count());assertEquals(0,BLOCKED.get(),"Blocked endpoint checks: "+BLOCKED_ENDPOINTS);
    }
}
