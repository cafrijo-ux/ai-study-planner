package review.mysql;
import ai_study_planner.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.support.GenericWebApplicationContext;
import review.mobile.IsolatedStudyLogServer;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual HTTP-mode policy + Controller/Security, memory H2 and MockMvc ONLY. */
class AndroidHttpH2Tests {
 static final String PASSWORD=UUID.randomUUID().toString(),HASH=new BCryptPasswordEncoder(12).encode(PASSWORD);
 GenericWebApplicationContext ctx;JdbcTemplate jdbc;IntegrationRuntime.Gate gate;IntegrationScenario api;IntegrationScenario.Fixture f;String id,url;
 @BeforeEach void open()throws Exception{
  id=UUID.randomUUID().toString();url="jdbc:h2:mem:integration_"+id+";MODE=MySQL;DB_CLOSE_DELAY=-1";assertThrows(ClassNotFoundException.class,()->Class.forName("com.mysql.cj.jdbc.Driver"));IntegrationSchema.offlineH2(url,id);
  var p=IsolatedStudyLogServer.properties(8104,id,HASH);p.put("spring.datasource.url",url);p.put("spring.jpa.hibernate.ddl-auto","none");p.put("planner.auth.username",IntegrationSchema.OWNER);p.put("integration.run-id",id);p.put("integration.phase","HTTP");
  var env=new org.springframework.core.env.StandardEnvironment();env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("androidOffline",p));var app=new SpringApplication(AiStudyPlannerApplication.class,MySqlIntegrationServer.Config.class);app.setEnvironment(env);app.setWebApplicationType(WebApplicationType.SERVLET);app.setApplicationContextFactory(t->{var c=new GenericWebApplicationContext();c.setServletContext(new MockServletContext());return c;});ctx=(GenericWebApplicationContext)app.run("--spring.config.location=optional:classpath:/integration-only.properties");jdbc=ctx.getBean(JdbcTemplate.class);gate=ctx.getBean(IntegrationRuntime.Gate.class);
  try(var c=jdbc.getDataSource().getConnection()){assertEquals("H2 JDBC Driver",c.getMetaData().getDriverName());assertEquals(url.split(";")[0],c.getMetaData().getURL());IntegrationSchema.claim(c,id);}
  IntegrationRuntime.seed(ctx);api=new IntegrationScenario(ctx,id);f=gate.bootstrapAndroid(()->api.initialize(PASSWORD));gate.enableAndroid(f);
 }
 @AfterEach void close()throws Exception{if(ctx!=null){ctx.close();try(var c=DriverManager.getConnection(url,"sa","")){c.createStatement().execute("SHUTDOWN");}}}
 Map<String,Object> flutterLog(){var b=new HashMap<String,Object>(IntegrationScenario.log(f.subjectA()));b.remove("completed");return b;}
 org.springframework.test.web.servlet.MvcResult savePlan(String key,String fault)throws Exception{
  var q=post("/study-reschedule-save").session(api.session).header("X-CSRF-TOKEN",api.token).header("X-Integration-Run",id).header("Idempotency-Key",key).queryParam("goalId",""+f.goalA()).content("");if(fault!=null)q.header("X-Integration-Fault",fault);return api.mvc.perform(q).andReturn();
 }
 void saveLog()throws Exception{api.postOk("/study-logs",f.goalA(),flutterLog(),IntegrationScenario.key());}
 @Test void exactFlutterPayloadLoginRequeryFinalAndReLoginPersistWithoutMixingB()throws Exception{
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM study_logs",Integer.class));var baseline=gate.android.verify();assertEquals(true,baseline.get("protectedFixturesPreserved"));assertEquals(false,baseline.get("flowComplete"));
  saveLog();var record=api.getOk("/study-logs",null,Map.of()).get(0);assertEquals(40,record.path("studyMinutes").asInt());assertEquals(3,record.path("understandingScore").asInt());assertEquals(3,record.path("focusScore").asInt());assertEquals(2,record.path("fatigueScore").asInt());
  var before=IntegrationRuntime.snapshot(jdbc);var preview=api.getOk("/study-reschedule-preview",f.goalA(),Map.of());assertEquals(before,IntegrationRuntime.snapshot(jdbc));assertEquals(200,preview.path("studyMinutes").asInt());assertEquals(60,preview.path("carryoverTotalPendingMinutes").asInt());assertEquals(9,preview.path("carryoverMinutes").asInt());assertEquals(191,preview.path("newStudyMinutes").asInt());
  assertEquals(200,savePlan(IntegrationScenario.key(),null).getResponse().getStatus());var stored=api.getOk("/study-reschedule-saved",f.goalA(),Map.of("date",LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString()));assertEquals("FINAL",stored.path("planStatus").asText());assertEquals(200,stored.path("studyMinutes").asInt());
  api.verifyLogout();api.login(PASSWORD);assertEquals(40,api.getOk("/analysis-summary",f.goalA(),Map.of("scope","current")).path("totalStudyMinutes").asInt());assertEquals(0,api.getOk("/analysis-summary",f.goalB(),Map.of("scope","current")).path("totalStudyMinutes").asInt());assertEquals(true,gate.android.verify().get("flowComplete"));assertEquals(true,gate.android.verify().get("protectedFixturesPreserved"));
 }
 @Test void wrongGoalSubjectScoresAndUnapprovedWritesStayBlocked()throws Exception{
  assertEquals(409,api.postRaw("/study-logs",f.goalB(),IntegrationScenario.log(f.subjectB()),IntegrationScenario.key(),null).getResponse().getStatus());
  var wrong=flutterLog();wrong.put("fatigueScore",3);assertEquals(409,api.postRaw("/study-logs",f.goalA(),wrong,IntegrationScenario.key(),null).getResponse().getStatus());
  wrong=flutterLog();wrong.put("subjectId",f.subjectB());assertEquals(409,api.postRaw("/study-logs",f.goalA(),wrong,IntegrationScenario.key(),null).getResponse().getStatus());
  for(String path:List.of("/goal-registrations","/study-schedule-settings","/integration/fixture-plan","/integration/mock-ai-record","/study-question","/gpt-weekly-coaching"))assertEquals(403,api.postRaw(path,f.goalA(),Map.of(),IntegrationScenario.key(),null).getResponse().getStatus());
  assertEquals(403,api.mvc.perform(get("/llm-coaching").session(api.session).header("X-CSRF-TOKEN",api.token)).andReturn().getResponse().getStatus());assertEquals(true,gate.android.verify().get("protectedFixturesPreserved"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM study_logs",Integer.class));
 }
 @Test void missingAuthCsrfAndRunHeadersCannotWrite()throws Exception{
  assertEquals(401,api.mvc.perform(get("/goals")).andReturn().getResponse().getStatus());assertEquals(403,api.mvc.perform(post("/study-logs").session(api.session).contentType("application/json").content(IntegrationRuntime.JSON.writeValueAsBytes(flutterLog()))).andReturn().getResponse().getStatus());
  assertEquals(403,api.mvc.perform(post("/study-logs").session(api.session).header("X-CSRF-TOKEN",api.token).contentType("application/json").content(IntegrationRuntime.JSON.writeValueAsBytes(flutterLog()))).andReturn().getResponse().getStatus());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM study_logs",Integer.class));
 }
 @Test void newKeysAndConcurrentSubmissionsStillCannotCreateTwoLogs()throws Exception{
  var pool=Executors.newFixedThreadPool(2);try{var calls=List.<Callable<Integer>>of(()->api.postRaw("/study-logs",f.goalA(),flutterLog(),IntegrationScenario.key(),null).getResponse().getStatus(),()->api.postRaw("/study-logs",f.goalA(),flutterLog(),IntegrationScenario.key(),null).getResponse().getStatus());var statuses=new ArrayList<Integer>();for(var r:pool.invokeAll(calls))statuses.add(r.get());Collections.sort(statuses);assertEquals(List.of(200,409),statuses);}finally{pool.shutdownNow();}assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM study_logs",Integer.class));
 }
 @Test void replyLossSurvivesNewClientKeyWhileGetFindsExactlyOneRecord()throws Exception{
  String key=IntegrationScenario.key();assertEquals(500,api.postRaw("/study-logs",f.goalA(),flutterLog(),key,"LOSE_REPLY_AFTER_COMMIT").getResponse().getStatus());assertEquals(409,api.postRaw("/study-logs",f.goalA(),flutterLog(),key,"LOSE_REPLY_AFTER_COMMIT").getResponse().getStatus());assertEquals(409,api.postRaw("/study-logs",f.goalA(),flutterLog(),IntegrationScenario.key(),null).getResponse().getStatus());api.verifyLogout();api.login(PASSWORD);assertEquals(1,api.getOk("/study-logs",null,Map.of()).size());assertEquals(true,gate.android.verify().get("protectedFixturesPreserved"));
 }
 @Test void planRequiresRecordEmptyBodyAndCannotOverwriteExistingTomorrowFinal()throws Exception{
  assertEquals(409,savePlan(IntegrationScenario.key(),null).getResponse().getStatus());saveLog();assertEquals(409,api.postRaw("/study-reschedule-save",f.goalA(),Map.of(),IntegrationScenario.key(),null).getResponse().getStatus());String key=IntegrationScenario.key();assertEquals(200,savePlan(key,null).getResponse().getStatus());assertEquals("true",savePlan(key,null).getResponse().getHeader("Idempotency-Replayed"));assertEquals(409,savePlan(IntegrationScenario.key(),null).getResponse().getStatus());assertEquals(true,gate.android.verify().get("flowComplete"));
 }
 @Test void stopProofDetectsProtectionChangesAndNeverClaimsIncompleteRunComplete(){
  assertEquals(false,gate.android.verify().get("flowComplete"));jdbc.update("UPDATE subjects SET subject_name=? WHERE subject_id=?","[합성 손상]",f.subjectB());assertEquals(false,gate.android.verify().get("protectedFixturesPreserved"));assertEquals(false,gate.android.verify().get("flowComplete"));
 }
 @Test void preparingAndQuiescingModesRejectPublicRequestsWithoutChangingFixtures()throws Exception{
  var snapshot=IntegrationRuntime.snapshot(jdbc);var ready=gate.android;gate.android=null;try{assertEquals(503,api.mvc.perform(get("/goals").session(api.session)).andReturn().getResponse().getStatus());}finally{gate.android=ready;}
  gate.quiesce(1000);assertEquals(503,api.mvc.perform(get("/study-logs").session(api.session)).andReturn().getResponse().getStatus());assertEquals(snapshot,IntegrationRuntime.snapshot(jdbc));
 }
 static final class TestClock extends Clock {
  Instant instant;final ZoneId zone;TestClock(Instant i,ZoneId z){instant=i;zone=z;}
  public ZoneId getZone(){return zone;}public Clock withZone(ZoneId z){assertEquals(zone,z);return this;}public Instant instant(){return instant;}
 }
 @Test void seoulMidnightRejectsBothWritesAndStillAllowsPreservationProof()throws Exception{
  var seoul=ZoneId.of("Asia/Seoul");var day=LocalDate.now(seoul);var clock=new TestClock(day.atTime(23,59,59).atZone(seoul).toInstant(),seoul);
  gate.android=new IntegrationRuntime.AndroidSession(jdbc,f,clock);var evidence=gate.android.dateEvidence();
  assertEquals(day.toString(),evidence.get("testDate"));assertEquals(day.minusDays(1).toString(),evidence.get("studyDate"));assertEquals(day.plusDays(1).toString(),evidence.get("planDate"));assertTrue(evidence.get("preparedAtUtc").toString().endsWith("Z"));
  var before=IntegrationRuntime.snapshot(jdbc);clock.instant=clock.instant.plusSeconds(2);
  var rejected=api.postRaw("/study-logs",f.goalA(),flutterLog(),IntegrationScenario.key(),null);assertEquals(409,rejected.getResponse().getStatus());assertTrue(rejected.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("ANDROID_TEST_DATE_EXPIRED"));
  assertEquals(409,savePlan(IntegrationScenario.key(),null).getResponse().getStatus());assertEquals(before,IntegrationRuntime.snapshot(jdbc));gate.quiesce(1000);
  var proof=gate.android.verify();assertEquals(true,proof.get("protectedFixturesPreserved"));assertEquals(false,proof.get("dateValid"));assertEquals(false,proof.get("flowComplete"));assertEquals(0,proof.get("studyRecords"));assertEquals(0,proof.get("savedPlans"));
 }
 @Test void nextDayNeverRewritesFixtureDatesOrClaimsExpiredFlowComplete()throws Exception{
  var seoul=ZoneId.of("Asia/Seoul");var clock=new TestClock(LocalDate.now(seoul).atTime(12,0).atZone(seoul).toInstant(),seoul);gate.android=new IntegrationRuntime.AndroidSession(jdbc,f,clock);
  var dateEvidence=gate.android.dateEvidence();saveLog();assertEquals(200,savePlan(IntegrationScenario.key(),null).getResponse().getStatus());assertEquals(true,gate.android.verify().get("flowComplete"));
  var before=IntegrationRuntime.snapshot(jdbc);clock.instant=clock.instant.plusSeconds(86400);assertEquals(dateEvidence,gate.android.dateEvidence());
  var proof=gate.android.verify();assertEquals(false,proof.get("dateValid"));assertEquals(false,proof.get("flowComplete"));assertEquals(true,proof.get("protectedFixturesPreserved"));assertEquals(1,proof.get("studyRecords"));assertEquals(1,proof.get("savedPlans"));assertEquals(before,IntegrationRuntime.snapshot(jdbc));
 }
 void calendarFixture(long goal,String date,String status)throws Exception{
  var p=Map.of("goalId",goal,"scheduleDate",date,"studyMinutes",100,"carryoverMinutes",9,"newStudyMinutes",91,"timeSlots",List.of(Map.of("subjectId",f.subjectA(),"subjectName","가상 운영체제","type","공부","allocationType","CATCHUP","activity","보충 학습","minutes",9,"startTime",date+" 09:00","endTime",date+" 09:09")));
  jdbc.update("INSERT INTO study_saved_plans(user_id,goal_id,plan_date,source_date,source_end_time,plan_status,start_time,end_time,plan_json) VALUES(1,?,?,?,'00:01',?,'09:00','11:00',?)",goal,java.sql.Date.valueOf(date),java.sql.Date.valueOf(LocalDate.parse(date).minusDays(1)),status,IntegrationRuntime.JSON.writeValueAsString(p));
 }
 @Test void calendarReadsOnlyOneGoalAndMonthIncludingDraftWithoutMutatingAnything()throws Exception{
  calendarFixture(f.goalA(),"2027-01-01","DRAFT");calendarFixture(f.goalA(),"2027-01-31","FINAL");calendarFixture(f.goalA(),"2027-02-01","FINAL");calendarFixture(f.goalB(),"2027-01-02","FINAL");
  var before=IntegrationRuntime.snapshot(jdbc);var result=api.getOk("/study-reschedule-calendar",f.goalA(),Map.of("month","2027-01"));assertEquals(f.goalA(),result.path("goalId").asLong());assertEquals(2,result.path("plans").size());assertEquals("2027-01-01",result.path("plans").get(0).path("planDate").asText());assertEquals("DRAFT",result.path("plans").get(0).path("planStatus").asText());assertEquals("FINAL",result.path("plans").get(1).path("planStatus").asText());assertTrue(result.path("plans").get(1).path("saved").asBoolean());
  assertEquals(1,api.getOk("/study-reschedule-calendar",f.goalB(),Map.of("month","2027-01")).path("plans").size());assertTrue(api.getOk("/study-reschedule-calendar",f.goalA(),Map.of("month","2027-03")).path("plans").isEmpty());assertEquals(before,IntegrationRuntime.snapshot(jdbc));
 }
 @Test void calendarRequiresAuthenticationExplicitOwnedGoalAndBoundedMonth()throws Exception{
  assertEquals(401,api.mvc.perform(get("/study-reschedule-calendar").param("goalId",""+f.goalA()).param("month","2027-01")).andReturn().getResponse().getStatus());
  assertEquals(400,api.mvc.perform(get("/study-reschedule-calendar").session(api.session).param("month","2027-01")).andReturn().getResponse().getStatus());
  long foreign=jdbc.queryForObject("SELECT goal_id FROM goals WHERE user_id=2",Long.class);
  assertEquals(404,api.mvc.perform(get("/study-reschedule-calendar").session(api.session).param("goalId",""+foreign).param("month","2027-01")).andReturn().getResponse().getStatus());
  for(String m:List.of("2027-1","2027-13","2027-01-01","1969-12","2101-01","invalid"))assertEquals(400,api.mvc.perform(get("/study-reschedule-calendar").session(api.session).param("goalId",""+f.goalA()).param("month",m)).andReturn().getResponse().getStatus());
  assertEquals(400,api.mvc.perform(get("/study-reschedule-calendar").session(api.session).param("goalId",""+f.goalA(),""+f.goalB()).param("month","2027-01")).andReturn().getResponse().getStatus());
 }
 @Test void calendarRejectsCorruptCrossGoalSnapshotInsteadOfDisplayingIt()throws Exception{
  calendarFixture(f.goalA(),"2027-01-01","FINAL");jdbc.update("UPDATE study_saved_plans SET plan_json=? WHERE goal_id=? AND plan_date='2027-01-01'","{\"goalId\":"+f.goalB()+",\"scheduleDate\":\"2027-01-01\"}",f.goalA());var before=IntegrationRuntime.snapshot(jdbc);
  assertEquals(500,api.mvc.perform(get("/study-reschedule-calendar").session(api.session).param("goalId",""+f.goalA()).param("month","2027-01")).andReturn().getResponse().getStatus());assertEquals(before,IntegrationRuntime.snapshot(jdbc));
  jdbc.update("UPDATE study_saved_plans SET plan_json=? WHERE goal_id=? AND plan_date='2027-01-01'","x".repeat(262145),f.goalA());before=IntegrationRuntime.snapshot(jdbc);
  assertEquals(500,api.mvc.perform(get("/study-reschedule-calendar").session(api.session).param("goalId",""+f.goalA()).param("month","2027-01")).andReturn().getResponse().getStatus());assertEquals(before,IntegrationRuntime.snapshot(jdbc));
 }
}
