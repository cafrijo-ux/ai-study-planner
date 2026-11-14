package review.mobile;

import ai_study_planner.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No listening port: real security/controller/persistence, synthetic memory H2 and mocked AI transport. */
@SpringBootTest(classes=AiStudyPlannerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.MOCK)
class AndroidAiContractH2Tests {
    static final String PASSWORD=UUID.randomUUID().toString();
    static final Map<String,Object> PROPS=IsolatedStudyLogServer.properties(8099,UUID.randomUUID().toString(),new BCryptPasswordEncoder(12).encode(PASSWORD));
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){PROPS.forEach((k,v)->r.add(k,()->v));}
    @Autowired WebApplicationContext ctx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StudyLogRepository logs;
    @Autowired SubjectRepository subjects;
    @Autowired GoalRepository goals;
    IsolatedRescheduleFixture.Seed seed;
    IsolatedRescheduleFixture.Baseline baseline;
    HttpClient http;
    MockMvc mvc;
    MockHttpSession session;
    String token;
    final ObjectMapper json=new ObjectMapper();
    ConfigurableApplicationContext context(){return (ConfigurableApplicationContext)ctx;}
    void set(Object bean,String name,Object value){ReflectionTestUtils.setField(bean,name,value);}
    @BeforeEach void prepare()throws Exception{
        IsolatedStudyLogFixture.assertH2(jdbc,(String)PROPS.get("spring.datasource.url"));
        assertThrows(ClassNotFoundException.class,()->Class.forName("com.mysql.cj.jdbc.Driver"));
        jdbc.update("DELETE FROM ai_responses");jdbc.update("DELETE FROM study_questions");
        logs.deleteAll();subjects.deleteAll();goals.deleteAll();jdbc.update("DELETE FROM users");
        jdbc.execute("DROP TABLE IF EXISTS study_saved_plans");jdbc.execute("DROP TABLE IF EXISTS study_schedule_settings");
        seed=IsolatedRescheduleFixture.seed(context(),(String)PROPS.get("spring.datasource.url"));
        baseline=IsolatedRescheduleFixture.baseline(context());
        http=mock(HttpClient.class);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{
            HttpRequest request=inv.getArgument(0);
            boolean gemini=request.uri().getHost().equals("generativelanguage.googleapis.com");
            assertTrue(gemini||request.uri().getHost().equals("api.openai.com"));
            HttpResponse<String> response=mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(gemini?
                "{\"modelVersion\":\"gemini-test\",\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"[Mock] 기록 40분을 바탕으로 한 학습 권고\"}]}}]}":
                "{\"status\":\"completed\",\"model\":\"gpt-4o-mini-test\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"[Mock] 과거 FINAL과 실제 기록은 다릅니다.\"}]}]}");
            return response;
        });
        var q=ctx.getBean(StudyQuestionController.class);
        set(q,"httpClient",http);set(q,"geminiEnabled",true);set(q,"geminiApiKey","synthetic-only");set(q,"geminiModel","gemini-test");set(q,"gptEnabled",true);set(q,"gptApiKey","synthetic-only");set(q,"gptModel","gpt-4o-mini");
        ((AtomicLong)ReflectionTestUtils.getField(q,"lastGeminiAttempt")).set(0);((AtomicLong)ReflectionTestUtils.getField(q,"lastGptAttempt")).set(0);
        var d=ctx.getBean(LlmCoachingController.class);
        set(d,"httpClient",http);set(d,"provider","gemini");set(d,"model","gemini-test");set(d,"geminiApiKey","synthetic-only");set(d,"demoMode",false);
        var w=ctx.getBean(GptWeeklyCoachingController.class);
        set(w,"httpClient",http);set(w,"enabled",true);set(w,"model","gpt-4o-mini");set(w,"apiKey","synthetic-only");((AtomicLong)ReflectionTestUtils.getField(w,"lastAttemptMillis")).set(0);
        mvc=MockMvcBuilders.webAppContextSetup(ctx).addFilters(ctx.getBean("springSecurityFilterChain",Filter.class)).build();
        var csrf=mvc.perform(get("/auth/csrf")).andReturn();session=(MockHttpSession)csrf.getRequest().getSession();token=json.readTree(csrf.getResponse().getContentAsString()).path("token").asText();
        mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN",token).param("username","mobile-h2-owner").param("password",PASSWORD)).andExpect(status().isOk());
        token=json.readTree(mvc.perform(get("/auth/csrf").session(session)).andReturn().getResponse().getContentAsString()).path("token").asText();
    }
    @AfterEach void finalAndOtherGoalUnchanged(){assertEquals(baseline,IsolatedRescheduleFixture.baseline(context()));}
    @ParameterizedTest @ValueSource(strings={"gemini","gpt"})
    void chosenProviderPersistsOneQuestionAndOneScopedResponse(String provider)throws Exception{
        mvc.perform(get("/study-question/capabilities").session(session)).andExpect(jsonPath("$.goalScopedResponse").value(true));
        mvc.perform(post("/study-question").session(session).queryParam("goalId",""+seed.goalA()).header("X-CSRF-TOKEN",token).contentType("application/json")
            .content(json.writeValueAsString(Map.of("provider",provider,"confirmPaidCall",true,"question","프로세스와 스레드의 차이","subject","운영체제","situation","복습","understanding","보통","goalId",seed.goalB()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.goalId").value(seed.goalA())).andExpect(jsonPath("$.requestedProvider").value(provider)).andExpect(jsonPath("$.responseSaved").value(true));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM study_questions",Integer.class));
        checkHistory("QUESTION",provider.equals("gpt")?"openai":"gemini");
        var capture=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http,times(1)).send(capture.capture(),any());
        assertEquals(provider.equals("gpt")?"api.openai.com":"generativelanguage.googleapis.com",capture.getValue().uri().getHost());
    }
    @ParameterizedTest @ValueSource(strings={"daily","weekly"})
    void coachingReusesExistingContractAndDoesNotModifyFinal(String feature)throws Exception{
        boolean daily=feature.equals("daily");
        var request=daily?get("/llm-coaching").servletPath("/llm-coaching"):post("/gpt-weekly-coaching").contentType("application/json").content("{\"confirmPaidCall\":true}");
        mvc.perform(request.session(session).queryParam("goalId",""+seed.goalA()).header("X-CSRF-TOKEN",token)).andExpect(status().isOk()).andExpect(jsonPath("$.responseSource").value("provider")).andExpect(jsonPath("$.responseSaved").value(true));
        checkHistory(daily?"DAILY_COACHING":"WEEKLY_COACHING",daily?"gemini":"openai");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM study_questions",Integer.class));verify(http,times(1)).send(any(),any());
    }
    void checkHistory(String feature,String provider)throws Exception{
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_responses",Integer.class));
        for(int i=0;i<2;i++)mvc.perform(get("/ai-responses").session(session).queryParam("goalId",""+seed.goalA()).queryParam("feature",feature)).andExpect(status().isOk()).andExpect(jsonPath("$[0].goalId").value(seed.goalA())).andExpect(jsonPath("$[0].actualProvider").value(provider)).andExpect(jsonPath("$[0].responseSource").value("PROVIDER"));
        mvc.perform(get("/ai-responses").session(session).queryParam("goalId",""+seed.goalB()).queryParam("feature",feature)).andExpect(status().isOk()).andExpect(content().json("[]"));
    }
    @Test void authenticationCsrfAndForeignGoalBlockBeforeProvider()throws Exception{
        mvc.perform(get("/study-question/capabilities")).andExpect(status().isUnauthorized());
        mvc.perform(post("/study-question").session(session).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        mvc.perform(get("/llm-coaching").servletPath("/llm-coaching").session(session)).andExpect(status().isForbidden());
        var foreign=new Goal();foreign.setUserId(2L);foreign.setGoalName("다른 사용자");goals.save(foreign);
        // This extra H2 row is not part of the protected synthetic fixture.
        try{
            mvc.perform(post("/study-question").session(session).queryParam("goalId",""+foreign.getGoalId()).header("X-CSRF-TOKEN",token).contentType("application/json").content("{\"provider\":\"gpt\",\"confirmPaidCall\":true,\"question\":\"가상 질문\"}")).andExpect(status().isNotFound());
        }finally{goals.delete(foreign);}
        verifyNoInteractions(http);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ai_responses",Integer.class));
    }
}
