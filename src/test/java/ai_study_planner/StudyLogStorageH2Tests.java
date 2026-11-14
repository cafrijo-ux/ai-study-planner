package ai_study_planner;

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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** Real controller, security, JPA and in-memory H2. No listening server or external API. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
class StudyLogStorageH2Tests {
 static final String DB="jdbc:h2:mem:study_log_storage_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
 static final String PASSWORD=UUID.randomUUID().toString();
 @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
  Map<String,Object> p=new HashMap<>();
  p.put("spring.datasource.url",DB);p.put("spring.datasource.driver-class-name","org.h2.Driver");
  p.put("spring.datasource.username","sa");p.put("spring.datasource.password","");
  p.put("spring.datasource.hikari.read-only",false);
  p.put("spring.jpa.hibernate.ddl-auto","create-drop");p.put("spring.sql.init.mode","never");
  p.put("spring.jpa.database-platform","org.hibernate.dialect.H2Dialect");p.put("spring.jpa.properties.hibernate.dialect","org.hibernate.dialect.H2Dialect");
  p.put("spring.jpa.show-sql",false);p.put("planner.auth.username","storage-test");
  p.put("planner.auth.password-hash",new BCryptPasswordEncoder(12).encode(PASSWORD));
  for(String k:List.of("gpt.weekly.enabled","study.question.gpt.enabled","study.question.gemini.enabled"))p.put(k,false);
  for(String k:List.of("gpt.weekly.api-key","gemini.api.key","llm.api.key","study.question.gpt.api-key"))p.put(k,"none");
  p.put("llm.demo-mode",true);p.forEach((k,v)->r.add(k,()->v));
 }
 @Autowired WebApplicationContext context;
 @Autowired JdbcTemplate jdbc;
 @Autowired StudyLogRepository logs;
 @Autowired SubjectRepository subjects;
 @Autowired GoalRepository goals;
 MockMvc mvc; MockHttpSession session; String token;
 ObjectMapper json=new ObjectMapper();
 long goalId,subjectId,foreignSubject,foreignGoalSubject;
 final String finalJson="{\"studyMinutes\":100,\"carryoverMinutes\":9,\"newStudyMinutes\":91}";
 @BeforeEach void setup() throws Exception {
  assertEquals("org.h2.Driver",context.getEnvironment().getProperty("spring.datasource.driver-class-name"));
  try(var c=jdbc.getDataSource().getConnection()){assertEquals(DB.split(";")[0],c.getMetaData().getURL());}
  logs.deleteAll();subjects.deleteAll();goals.deleteAll();
  Goal g=new Goal();g.setUserId(1L);g.setGoalName("가상 목표");goalId=goals.save(g).getGoalId();
  Goal other=new Goal();other.setUserId(2L);other.setGoalName("다른 사용자");long otherId=goals.save(other).getGoalId();
  subjectId=subject(1,goalId);foreignSubject=subject(2,otherId);foreignGoalSubject=subject(1,otherId);
  jdbc.execute("CREATE TABLE IF NOT EXISTS study_saved_plans (goal_id BIGINT,plan_status VARCHAR(10),plan_json CLOB)");
  jdbc.update("DELETE FROM study_saved_plans");jdbc.update("INSERT INTO study_saved_plans VALUES(?,'FINAL',?)",goalId,finalJson);
  mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  var r=mvc.perform(get("/auth/csrf")).andReturn();session=(MockHttpSession)r.getRequest().getSession();token=json.readTree(r.getResponse().getContentAsString()).path("token").asText();
  mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN",token).param("username","storage-test").param("password",PASSWORD)).andExpect(status().isOk());
  token=json.readTree(mvc.perform(get("/auth/csrf").session(session)).andReturn().getResponse().getContentAsString()).path("token").asText();
 }
 long subject(long owner,long goal){Subject s=new Subject();s.setUserId(owner);s.setGoalId(goal);s.setSubjectName("운영체제");return subjects.save(s).getSubjectId();}
 Map<String,Object> payload(){return new LinkedHashMap<>(Map.of("userId",1,"subjectId",subjectId,"studyDate","2026-10-19","studyMinutes",40,"studyContent","프로세스와 스레드","understandingScore",3,"focusScore",3,"fatigueScore",2,"studyMethod","개념 복습","memo","가상 테스트"));}
 org.springframework.test.web.servlet.ResultActions save(Map<String,Object> p)throws Exception{return mvc.perform(post("/study-logs").session(session).header("X-CSRF-TOKEN",token).contentType("application/json").content(json.writeValueAsBytes(p)));}
 @AfterEach void finalUnchanged(){assertEquals(finalJson,jdbc.queryForObject("SELECT plan_json FROM study_saved_plans",String.class));assertEquals("FINAL",jdbc.queryForObject("SELECT plan_status FROM study_saved_plans",String.class));}
 @Test void flutterPayloadPersistsAndGetAndAnalysisMatch()throws Exception{
  var p=payload();var response=json.readTree(save(p).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  assertTrue(response.path("logId").asLong()>0);for(var e:p.entrySet())assertEquals(json.readTree(json.writeValueAsBytes(e.getValue())),response.get(e.getKey()));
  assertEquals(1,logs.count());assertEquals(40,logs.findById(response.path("logId").asLong()).orElseThrow().getStudyMinutes());
  mvc.perform(get("/study-logs").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].logId").value(response.path("logId").asLong())).andExpect(jsonPath("$[0].studyMinutes").value(40));
  mvc.perform(get("/analysis-summary").session(session).param("scope","current").param("goalId",""+goalId)).andExpect(status().isOk()).andExpect(jsonPath("$.recordCount").value(1)).andExpect(jsonPath("$.totalStudyMinutes").value(40)).andExpect(jsonPath("$.averageFocus").value(3.0)).andExpect(jsonPath("$.averageUnderstanding").value(3.0));
 }
 @ParameterizedTest @ValueSource(strings={"userId","subjectId","studyDate","studyMinutes","studyContent","understandingScore","focusScore","fatigueScore"}) void requiredFieldsRejected(String field)throws Exception{var p=payload();p.remove(field);save(p).andExpect(status().is4xxClientError());assertEquals(0,logs.count());}
 @ParameterizedTest @ValueSource(strings={"understandingScore","focusScore","fatigueScore"}) void scoreBounds(String field)throws Exception{for(int value:List.of(0,6)){var p=payload();p.put(field,value);save(p).andExpect(status().isBadRequest());}assertEquals(0,logs.count());}
 @Test void invalidTimeAndDate()throws Exception{var p=payload();p.put("studyMinutes",0);save(p).andExpect(status().isBadRequest());p=payload();p.put("studyDate","invalid");save(p).andExpect(status().isBadRequest());assertEquals(0,logs.count());}
 @Test void foreignUserAndSubjectAndGoalBlocked()throws Exception{var p=payload();p.put("userId",2);save(p).andExpect(status().isForbidden());p=payload();p.put("subjectId",foreignSubject);save(p).andExpect(status().isForbidden());p.put("subjectId",foreignGoalSubject);save(p).andExpect(status().isNotFound());assertEquals(0,logs.count());}
 @Test void authenticationAndCsrfRequired()throws Exception{
  mvc.perform(get("/study-logs")).andExpect(status().isUnauthorized());
  mvc.perform(post("/study-logs").session(session).contentType("application/json").content(json.writeValueAsBytes(payload()))).andExpect(status().isForbidden());
  mvc.perform(post("/study-logs").session(session).header("X-CSRF-TOKEN","wrong").contentType("application/json").content(json.writeValueAsBytes(payload()))).andExpect(status().isForbidden());assertEquals(0,logs.count());
 }
 @Test void ownOtherGoalDeterminedBySubjectNotUiSelection()throws Exception{
  Goal g=new Goal();g.setUserId(1L);g.setGoalName("두 번째 본인 목표");long id=goals.save(g).getGoalId();var p=payload();p.put("subjectId",subject(1,id));save(p).andExpect(status().isOk());
  mvc.perform(get("/analysis-summary").session(session).param("scope","current").param("goalId",""+goalId)).andExpect(status().isOk()).andExpect(jsonPath("$.recordCount").value(0));
 }
 @Test void persistenceFailureReturnsSafe500WithoutSavedRow()throws Exception{
  var p=payload();p.put("studyContent","x".repeat(256)); // Isolated JPA H2 varchar(255) boundary, no schema change.
  var response=save(p).andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();
  assertTrue(json.readTree(response).path("message").isTextual());
  assertFalse(response.contains("insert into"));assertFalse(response.contains("x".repeat(256)));
  assertEquals(0,logs.count());
 }
 @Test void repeatedHttpPostsAreNotIdempotentSoClientMustNotRetry()throws Exception{
  assertEquals(0,context.getBeanNamesForType(StudyLogIdempotencyService.class).length);
  mvc.perform(get("/study-log-idempotency").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
  var first=json.readTree(save(payload()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  var second=json.readTree(save(payload()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  assertNotEquals(first.path("logId").asLong(),second.path("logId").asLong());assertEquals(2,logs.count());
  mvc.perform(get("/analysis-summary").session(session).param("goalId",""+goalId))
   .andExpect(status().isOk()).andExpect(jsonPath("$.recordCount").value(2)).andExpect(jsonPath("$.totalStudyMinutes").value(80));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='STUDY_LOG_REQUESTS'",Integer.class));
  mvc.perform(get("/study-log-requests").session(session).param("goalId",""+goalId).param("requestKey","test_unconfigured_key")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_DISABLED"));
 }
}
