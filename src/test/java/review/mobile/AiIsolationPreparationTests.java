package review.mobile;

import ai_study_planner.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=AiStudyPlannerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.MOCK)
class AiIsolationPreparationTests {
    static final String PASSWORD=UUID.randomUUID().toString();
    static final Map<String,Object> PROPS=IsolatedAiServer.properties(8099,UUID.randomUUID().toString(),new BCryptPasswordEncoder(12).encode(PASSWORD));
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){PROPS.forEach((k,v)->r.add(k,()->v));}
    @Autowired WebApplicationContext ctx;@Autowired JdbcTemplate jdbc;
    IsolatedRescheduleFixture.Seed seed;IsolatedRescheduleFixture.Baseline baseline;
    IsolatedAiServer.Installed installed;IsolatedAiServer.Gate gate;
    MockMvc mvc;MockHttpSession session;String token;
    final ObjectMapper json=new ObjectMapper();
    ConfigurableApplicationContext context(){return (ConfigurableApplicationContext)ctx;}
    @BeforeEach void fixture()throws Exception{
        IsolatedStudyLogFixture.assertH2(jdbc,(String)PROPS.get("spring.datasource.url"));
        assertThrows(ClassNotFoundException.class,()->Class.forName("com.mysql.cj.jdbc.Driver"));
        for(String table:List.of("ai_responses","study_questions","study_logs","subjects","goals","users"))jdbc.update("DELETE FROM "+table);
        jdbc.execute("DROP TABLE IF EXISTS study_saved_plans");jdbc.execute("DROP TABLE IF EXISTS study_schedule_settings");
        seed=IsolatedRescheduleFixture.seed(context(),(String)PROPS.get("spring.datasource.url"));baseline=IsolatedRescheduleFixture.baseline(context());
        installed=IsolatedAiServer.install(context());
        // Independent JUnit cases use fresh synthetic fixtures. The launch harness never resets these limits.
        for(String field:List.of("lastGeminiAttempt","lastGptAttempt"))((AtomicLong)ReflectionTestUtils.getField(ctx.getBean(StudyQuestionController.class),field)).set(0);
        ((AtomicLong)ReflectionTestUtils.getField(ctx.getBean(GptWeeklyCoachingController.class),"lastAttemptMillis")).set(0);
        gate=new IsolatedAiServer.Gate();gate.open(seed);mvc=secured(gate);
        var csrf=mvc.perform(get("/auth/csrf")).andReturn();session=(MockHttpSession)csrf.getRequest().getSession();token=tree(csrf).path("token").asText();
        mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN",token).param("username",IsolatedAiServer.OWNER).param("password",PASSWORD)).andExpect(status().isOk());
        token=tree(mvc.perform(get("/auth/csrf").session(session)).andReturn()).path("token").asText();
    }
    MockMvc secured(Filter... extra){var builder=MockMvcBuilders.webAppContextSetup(ctx).addFilters(ctx.getBean("springSecurityFilterChain",Filter.class));builder.addFilters(extra);return builder.build();}
    JsonNode tree(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
    JsonNode request(String feature)throws Exception{
        var builder=feature.equals("daily")?get("/llm-coaching").servletPath("/llm-coaching"):
            post(feature.equals("weekly")?"/gpt-weekly-coaching":"/study-question").contentType("application/json").content(json.writeValueAsString(Map.of("confirmPaidCall",true,"provider",feature,"question","가상 프로세스 질문","subject","가상 운영체제","understanding","보통","situation","복습")));
        return tree(mvc.perform(builder.session(session).header("X-CSRF-TOKEN",token).queryParam("goalId",""+seed.goalA())).andExpect(status().isOk()).andReturn());
    }
    JsonNode history(String feature,long goal)throws Exception{return tree(mvc.perform(get("/ai-responses").session(session).queryParam("goalId",""+goal).queryParam("feature",feature)).andExpect(status().isOk()).andReturn());}
    @AfterEach void preserve(){assertEquals(baseline,IsolatedRescheduleFixture.baseline(context()));}
    @Test void fourFunctionsPersistExactAndroidMetadataWithNoCrossGoalOrExtraCalls()throws Exception{
        var caps=tree(mvc.perform(get("/study-question/capabilities").session(session).queryParam("goalId",""+seed.goalA())).andReturn());assertTrue(caps.path("goalScopedResponse").asBoolean());assertEquals(IsolatedAiServer.MARKER,caps.path("testMode").asText());
        var evidence=new LinkedHashMap<String,Object>();evidence.put("syntheticOnly",true);evidence.put("goalA",seed.goalA());evidence.put("goalB",seed.goalB());evidence.put("capabilities",caps);
        evidence.put("subjects",tree(mvc.perform(get("/subjects").session(session).queryParam("goalId",""+seed.goalA())).andReturn()));
        evidence.put("dailyConfig",tree(mvc.perform(get("/llm-config-check").session(session).queryParam("goalId",""+seed.goalA())).andReturn()));
        evidence.put("weeklyPreview",tree(mvc.perform(get("/gpt-weekly-coaching/preview").session(session).queryParam("goalId",""+seed.goalA())).andReturn()));
        for(String provider:List.of("gemini","gpt")){
            var r=request(provider);assertEquals(seed.goalA(),r.path("goalId").asLong());assertEquals(provider,r.path("requestedProvider").asText());assertEquals(provider,r.path("answerSource").asText());
            evidence.put(provider,r);
            assertEquals("provider",r.path("responseSource").asText());assertTrue(r.path("responseSaved").asBoolean());assertTrue(r.path("questionId").asLong()>0);assertTrue(r.path("responseId").asLong()>0);
            assertEquals(provider.equals("gpt")?"gpt-4o-mini":"gemini-isolation-mock",r.path("model").asText());assertTrue(r.path("responseModel").asText().contains("mock"));
            assertTrue(r.path("responseComplete").asBoolean(),"Completed provider replies expose verified completion metadata");
        }
        var daily=request("daily");assertTrue(daily.path("responseSaved").asBoolean());assertEquals("gemini",daily.path("provider").asText());
        var weekly=request("weekly");assertTrue(weekly.path("responseComplete").asBoolean());assertTrue(weekly.path("responseSaved").asBoolean());
        evidence.put("daily",daily);evidence.put("weekly",weekly);
        var before=installed.totals();assertEquals(Map.of("QUESTION_GEMINI",1,"QUESTION_GPT",1,"DAILY_COACHING",1,"WEEKLY_COACHING",1),before);
        for(String feature:List.of("QUESTION","DAILY_COACHING","WEEKLY_COACHING")){
            var rows=history(feature,seed.goalA());assertEquals(feature.equals("QUESTION")?2:1,rows.size());assertEquals(0,history(feature,seed.goalB()).size());
            evidence.put("history"+feature,rows);
            for(var row:rows){assertEquals(IsolatedAiServer.MARKER,row.path("testMode").asText());assertTrue(row.path("responseBody").asText().contains("격리 Mock"));assertEquals(seed.goalA(),row.path("goalId").asLong());}
            history(feature,seed.goalA());
        }
        assertEquals(before,installed.totals());assertEquals(4,ctx.getBean(AiResponseRepository.class).count());assertEquals(2,ctx.getBean(StudyQuestionRepository.class).count());
        // Bridge fixture contains only synthetic controller responses, never credentials or session/CSRF tokens.
        var path=java.nio.file.Path.of("mobile_flutter/test/fixtures/ai_h2_contract.json");java.nio.file.Files.createDirectories(path.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),evidence);
    }
    @Test void previewsUseSevenDaysAndDatedFinalButNeverGenerate()throws Exception{
        var result=tree(mvc.perform(get("/gpt-weekly-coaching/preview").session(session).queryParam("goalId",""+seed.goalA())).andExpect(status().isOk()).andReturn());
        assertEquals(40,result.path("totalStudyMinutes").asInt());assertEquals(1,result.path("recordCount").asInt());assertEquals(7,result.path("dailyMetrics").size());
        assertTrue(result.path("prompt").asText().contains(seed.today().minusDays(1).toString()));assertEquals("preview",result.path("responseSource").asText());
        mvc.perform(get("/llm-config-check").session(session).queryParam("goalId",""+seed.goalA())).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true)).andExpect(jsonPath("$.model").value("gemini-isolation-mock")).andExpect(jsonPath("$.demoMode").value(false));
        history("QUESTION",seed.goalA());assertTrue(installed.totals().isEmpty());assertTrue(gate.totals().isEmpty());
    }
    @Test void authenticationCsrfGoalAndOtherWriteGuardsRunBeforeProvider()throws Exception{
        mvc.perform(get("/study-question/capabilities")).andExpect(status().isUnauthorized());
        mvc.perform(post("/study-question").session(session).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        mvc.perform(get("/llm-coaching").servletPath("/llm-coaching").session(session).queryParam("goalId",""+seed.goalA())).andExpect(status().isForbidden());
        mvc.perform(post("/study-question").session(session).header("X-CSRF-TOKEN",token).queryParam("goalId",""+seed.goalB()).contentType("application/json").content("{\"provider\":\"gpt\",\"confirmPaidCall\":true,\"question\":\"가상 질문\"}")).andExpect(status().isForbidden());
        for(String path:List.of("/study-logs","/study-reschedule-save","/goals/register"))mvc.perform(post(path).session(session).header("X-CSRF-TOKEN",token)).andExpect(status().isForbidden());
        mvc.perform(post("/study-question").session(session).header("X-CSRF-TOKEN",token).queryParam("goalId",""+seed.goalA()).contentType("application/json").content("{\"provider\":\"invalid\",\"confirmPaidCall\":true,\"question\":\"가상 질문\"}")).andExpect(status().isBadRequest());
        assertTrue(installed.totals().isEmpty());assertTrue(gate.totals().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"PARTIAL","FAILURE","NETWORK"})
    void weeklyPartialOrFailureNeverRetries(String mode)throws Exception{
        installed.weekly().replyMode=IsolatedAiServer.ReplyMode.valueOf(mode);var r=request("weekly");
        if(mode.equals("PARTIAL")){assertFalse(r.path("responseComplete").asBoolean());assertTrue(r.path("responseSaved").asBoolean());assertFalse(history("WEEKLY_COACHING",seed.goalA()).get(0).path("responseComplete").asBoolean());}
        else{assertFalse(r.has("llmResponse"));assertEquals(0,history("WEEKLY_COACHING",seed.goalA()).size());}
        mvc.perform(post("/gpt-weekly-coaching").session(session).header("X-CSRF-TOKEN",token).queryParam("goalId",""+seed.goalA()).contentType("application/json").content("{\"confirmPaidCall\":true}")).andExpect(status().isConflict());
        assertEquals(Map.of("WEEKLY_COACHING",1),installed.totals());
    }
    @Test void failedPersistenceKeepsAnswerAndDoesNotRetryProvider()throws Exception{
        jdbc.execute("ALTER TABLE ai_responses ADD CONSTRAINT synthetic_reject CHECK (response_id < 0)");
        try{var r=request("gpt");assertTrue(r.path("aiAdvice").asText().contains("격리 Mock"));assertFalse(r.path("responseSaved").asBoolean());assertTrue(r.has("saveWarning"));assertEquals(0,history("QUESTION",seed.goalA()).size());assertEquals(0,ctx.getBean(StudyQuestionRepository.class).count());assertEquals(Map.of("QUESTION_GPT",1),installed.totals());}
        finally{jdbc.execute("ALTER TABLE ai_responses DROP CONSTRAINT synthetic_reject");}
    }
    @Test void nativeCooldownRemainsEvenWithoutExtraHarnessBudget()throws Exception{
        request("weekly");mvc=secured();var r=request("weekly");assertEquals("잠시 대기",r.path("apiStatus").asText());assertEquals(Map.of("WEEKLY_COACHING",1),installed.totals());
    }
    @Test void ruleDemoStaysRuleAndNeverUsesMockProvider()throws Exception{
        ReflectionTestUtils.setField(ctx.getBean(LlmCoachingController.class),"demoMode",true);
        var r=request("daily");assertEquals("rule",r.path("responseSource").asText());assertTrue(r.path("demoMode").asBoolean());assertTrue(r.path("responseSaved").asBoolean());
        var row=history("DAILY_COACHING",seed.goalA()).get(0);assertEquals("RULE",row.path("responseSource").asText());assertTrue(row.path("actualProvider").isNull());assertEquals("rule-based",row.path("model").asText());assertTrue(installed.totals().isEmpty());
    }
    @Test void defaultMainAndNetworkGuardCannotStartOrConnect(){
        IsolatedAiServer.main(new String[0]);assertTrue(installed.totals().isEmpty());
        var guard=new IsolatedAiServer.NetworkGuard(8099);
        for(String host:List.of("api.openai.com","generativelanguage.googleapis.com","localhost","127.0.0.1"))assertThrows(SecurityException.class,()->guard.checkConnect(host,443));
        assertThrows(SecurityException.class,()->guard.checkListen(8080));assertDoesNotThrow(()->guard.checkListen(8099));
    }
}
