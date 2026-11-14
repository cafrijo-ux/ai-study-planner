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
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Full application/Controller/Security beans, but MockMvc, UUID memory H2 and no HTTP listener. */
class CalendarHttpH2Tests {
 static final String PASSWORD=UUID.randomUUID().toString(),HASH=new BCryptPasswordEncoder(12).encode(PASSWORD);
 GenericWebApplicationContext ctx;JdbcTemplate jdbc;IntegrationRuntime.Gate gate;IntegrationScenario api;CalendarReadOnlySession session;String id,url;
 @BeforeEach void open()throws Exception{
  id=UUID.randomUUID().toString();url="jdbc:h2:mem:integration_"+id+";MODE=MySQL;DB_CLOSE_DELAY=-1";
  assertThrows(ClassNotFoundException.class,()->Class.forName("com.mysql.cj.jdbc.Driver"));IntegrationSchema.offlineH2(url,id);
  var p=IsolatedStudyLogServer.properties(8104,id,HASH);p.put("spring.datasource.url",url);p.put("spring.jpa.hibernate.ddl-auto","none");p.put("planner.auth.username",IntegrationSchema.OWNER);p.put("integration.run-id",id);p.put("integration.phase","HTTP");
  var env=new org.springframework.core.env.StandardEnvironment();env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("calendarOffline",p));
  var app=new SpringApplication(AiStudyPlannerApplication.class,MySqlIntegrationServer.Config.class);app.setEnvironment(env);app.setWebApplicationType(WebApplicationType.SERVLET);app.setApplicationContextFactory(t->{var c=new GenericWebApplicationContext();c.setServletContext(new MockServletContext());return c;});
  ctx=(GenericWebApplicationContext)app.run("--spring.config.location=optional:classpath:/integration-only.properties");jdbc=ctx.getBean(JdbcTemplate.class);gate=ctx.getBean(IntegrationRuntime.Gate.class);
  try(var c=jdbc.getDataSource().getConnection()){assertEquals("H2 JDBC Driver",c.getMetaData().getDriverName());assertEquals(url.split(";")[0],c.getMetaData().getURL());IntegrationSchema.claim(c,id);}
  IntegrationRuntime.seed(ctx);session=gate.bootstrapAndroid(()->CalendarReadOnlySession.initialize(ctx,id,PASSWORD));gate.enableCalendar(session);api=new IntegrationScenario(ctx,id);api.login(PASSWORD);
 }
 @AfterEach void close()throws Exception{if(ctx!=null){ctx.close();try(var c=DriverManager.getConnection(url,"sa","")){c.createStatement().execute("SHUTDOWN");}}}
 @Test void realFixtureUsesRulesAndCalendarSingleDateContractsAgree()throws Exception{
  assertNotNull(ctx.getBean(StudyRescheduleSaveController.class));assertNotNull(ctx.getBean(StudySavedPlanService.class));
  var before=IntegrationRuntime.snapshot(jdbc);var month=api.getOk("/study-reschedule-calendar",session.fixture.goalA(),Map.of("month",YearMonth.from(session.planDate).toString()));
  var plan=month.path("plans").findValues("planDate");assertFalse(plan.isEmpty());
  var selected=java.util.stream.StreamSupport.stream(month.path("plans").spliterator(),false).filter(n->n.path("planDate").asText().equals(session.planDate.toString())).findFirst().orElseThrow();
  var single=api.getOk("/study-reschedule-saved",session.fixture.goalA(),Map.of("date",session.planDate.toString()));
  for(String field:List.of("goalId","planDate","saved","planStatus","savedAt","studyMinutes","carryoverTotalPendingMinutes","carryoverMinutes","newStudyMinutes","subjectPlans","timeSlots"))assertEquals(single.get(field),selected.get(field),field);
  assertEquals(200,selected.path("studyMinutes").asInt());assertEquals(60,selected.path("carryoverTotalPendingMinutes").asInt());assertEquals(9,selected.path("carryoverMinutes").asInt());assertEquals(191,selected.path("newStudyMinutes").asInt());
  assertTrue(selected.path("savedAt").isTextual());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM study_logs",Integer.class));assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM study_saved_plans",Integer.class));assertEquals(before,IntegrationRuntime.snapshot(jdbc));
 }
 @Test void packedDashboardAndReviewedScriptsAreServedThroughSameSecurityChain()throws Exception{
  for(String name:CalendarReadOnlySession.ASSETS){var r=api.mvc.perform(get("/"+name).session(api.session)).andReturn();assertEquals(200,r.getResponse().getStatus(),name);assertTrue(r.getResponse().getContentAsByteArray().length>0,name);}
  for(String path:List.of("/auto-reschedule","/study-progress","/ai-coaching","/study-plan"))assertEquals(200,api.mvc.perform(get(path).session(api.session).param("goalId",""+session.fixture.goalA())).andReturn().getResponse().getStatus(),path);
  assertEquals(true,session.verify().get("protectedFixturesPreserved"));
 }
 @Test void everyPublicWriteAndGenerationGetStaysBlockedEvenWithValidCsrf()throws Exception{
  var before=IntegrationRuntime.snapshot(jdbc);int requests=jdbc.queryForObject("SELECT COUNT(*) FROM integration_requests",Integer.class);
  for(String path:List.of("/study-logs","/study-reschedule-save","/goal-registrations","/study-schedule-settings","/study-question","/gpt-weekly-coaching","/integration/fixture-plan","/integration/mock-ai-record"))assertEquals(403,api.postRaw(path,session.fixture.goalA(),Map.of(),IntegrationScenario.key(),null).getResponse().getStatus(),path);
  for(String path:List.of("/llm-coaching","/gemini-study-summary"))assertEquals(403,api.mvc.perform(get(path).session(api.session)).andReturn().getResponse().getStatus());
  assertEquals(requests,jdbc.queryForObject("SELECT COUNT(*) FROM integration_requests",Integer.class));assertEquals(before,IntegrationRuntime.snapshot(jdbc));
 }
 @Test void historyPageIsAuthenticatedAndUsesOnlyAlreadyReviewedReadDependencies()throws Exception{
  assertTrue(CalendarReadOnlySession.ASSETS.contains("study-log-history.html"));
  var anonymous=api.mvc.perform(get("/study-log-history.html")).andReturn();assertEquals(302,anonymous.getResponse().getStatus());assertEquals("/login.html",anonymous.getResponse().getRedirectedUrl());
  var page=api.mvc.perform(get("/study-log-history.html").session(api.session)).andReturn();assertEquals(200,page.getResponse().getStatus());
  String html=page.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  assertTrue(html.contains("'/goals','/subjects','/study-logs'"));assertTrue(html.contains("<style>"));
  var scripts=java.util.regex.Pattern.compile("<script src=\"([^\"]+)\"").matcher(html);var dependencies=new HashSet<String>();while(scripts.find())dependencies.add(scripts.group(1));assertEquals(Set.of("/auth-client.js","/goal-context.js"),dependencies);
  String dashboard=api.mvc.perform(get("/dashboard.html").session(api.session)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertTrue(dashboard.contains("href=\"/study-log-history.html\""));
  assertEquals(403,api.mvc.perform(get("/study-log-form.html").session(api.session)).andReturn().getResponse().getStatus()); // Linked input page is intentionally outside this read-only profile.
 }
 @Test void historyReadsCannotExposeAnotherUserEvenWithForgedQueryParameters()throws Exception{
  long otherSubject=jdbc.queryForObject("SELECT subject_id FROM subjects WHERE user_id=2",Long.class);
  jdbc.update("INSERT INTO study_logs(user_id,subject_id,study_date,study_minutes,study_content) VALUES(2,?,?,999,?)",otherSubject,java.sql.Date.valueOf(session.studyDate),"[가상] 다른 사용자 전용 기록");
  var before=IntegrationRuntime.snapshot(jdbc);
  assertEquals(401,api.mvc.perform(get("/study-logs").param("userId","2")).andReturn().getResponse().getStatus());
  var logs=api.getOk("/study-logs",null,Map.of("userId","2"));assertEquals(1,logs.size());assertEquals(1,logs.get(0).path("userId").asLong());assertEquals(40,logs.get(0).path("studyMinutes").asInt());
  var subjects=api.getOk("/subjects",null,Map.of());var aIds=new HashSet<Long>();var bIds=new HashSet<Long>();for(var s:subjects){assertEquals(1,s.path("userId").asLong());if(s.path("goalId").asLong()==session.fixture.goalA())aIds.add(s.path("subjectId").asLong());if(s.path("goalId").asLong()==session.fixture.goalB())bIds.add(s.path("subjectId").asLong());}
  assertTrue(aIds.contains(logs.get(0).path("subjectId").asLong()));assertFalse(bIds.contains(logs.get(0).path("subjectId").asLong()));assertFalse(aIds.contains(otherSubject));assertFalse(bIds.contains(otherSubject));assertEquals(before,IntegrationRuntime.snapshot(jdbc));
 }
 @Test void historyAndCalendarGetRefreshesPreserveAllFixturesAndRequestLedger()throws Exception{
  var before=IntegrationRuntime.snapshot(jdbc);int requests=jdbc.queryForObject("SELECT COUNT(*) FROM integration_requests",Integer.class);
  for(int refresh=0;refresh<2;refresh++){
   assertEquals(200,api.mvc.perform(get("/study-log-history.html").session(api.session)).andReturn().getResponse().getStatus());
   for(String path:List.of("/goals","/subjects","/study-logs"))api.getOk(path,null,Map.of());
   api.getOk("/study-reschedule-calendar",session.fixture.goalA(),Map.of("month",YearMonth.from(session.planDate).toString()));
  }
  assertEquals(before,IntegrationRuntime.snapshot(jdbc));assertEquals(requests,jdbc.queryForObject("SELECT COUNT(*) FROM integration_requests",Integer.class));assertEquals(true,session.verify().get("protectedFixturesPreserved"));
 }
 @Test void authenticationCsrfOwnershipAndGoalBSeparationArePreserved()throws Exception{
  var path="/study-reschedule-calendar";assertEquals(401,api.mvc.perform(get(path).param("goalId",""+session.fixture.goalA()).param("month",YearMonth.from(session.planDate).toString())).andReturn().getResponse().getStatus());
  assertEquals(403,api.mvc.perform(post("/study-reschedule-save").session(api.session)).andReturn().getResponse().getStatus());
  long foreign=jdbc.queryForObject("SELECT goal_id FROM goals WHERE user_id=2",Long.class);assertEquals(404,api.mvc.perform(get(path).session(api.session).param("goalId",""+foreign).param("month",YearMonth.from(session.planDate).toString())).andReturn().getResponse().getStatus());
  var b=api.getOk(path,session.fixture.goalB(),Map.of("month",YearMonth.from(session.studyDate).toString()));assertEquals(1,b.path("plans").size());assertEquals(session.fixture.goalB(),b.path("plans").get(0).path("goalId").asLong());
  var empty=api.getOk(path,session.fixture.goalA(),Map.of("month",YearMonth.from(session.planDate).plusMonths(2).toString()));assertTrue(empty.path("plans").isEmpty());
  api.verifyLogout();api.login(PASSWORD);assertEquals(true,session.verify().get("protectedFixturesPreserved"));
 }
 @Test void fixtureFingerprintAndReadyProofDoNotPretendClientRenderingWasVerified()throws Exception{
  var ready=session.ready();assertEquals(0,ready.get("maxStudyWrites"));assertEquals(0,ready.get("maxPlanWrites"));assertEquals(false,session.verify().get("clientComparisonVerified"));
  api.getOk("/study-reschedule-calendar",session.fixture.goalA(),Map.of("month",YearMonth.from(session.planDate).toString()));api.getOk("/study-reschedule-calendar",session.fixture.goalB(),Map.of("month",YearMonth.from(session.studyDate).toString()));
  var proof=session.verify();assertEquals(1,proof.get("monthReadsA"));assertEquals(1,proof.get("monthReadsB"));assertEquals(true,proof.get("protectedFixturesPreserved"));
  jdbc.update("UPDATE subjects SET subject_name=? WHERE subject_id=?","[합성 손상]",session.fixture.subjectB());assertEquals(false,session.verify().get("protectedFixturesPreserved"));
 }
 @Test void midnightDoesNotRewriteDatesOrBlockOrderlyStopButCannotCompleteExpiredRun()throws Exception{
  var c=new AndroidHttpH2Tests.TestClock(Instant.parse("2026-10-31T14:59:00Z"),ZoneId.of("Asia/Seoul"));var dated=new CalendarReadOnlySession(jdbc,session.fixture,c,session.contract);
  assertEquals(true,dated.verify().get("dateValid"));c.instant=Instant.parse("2026-10-31T15:01:00Z");assertEquals(false,dated.verify().get("dateValid"));assertEquals("2026-10-31",dated.ready().get("testDate"));
  gate.quiesce(1000);assertEquals(503,api.mvc.perform(get("/study-reschedule-calendar").session(api.session)).andReturn().getResponse().getStatus());assertEquals(true,session.verify().get("protectedFixturesPreserved"));
 }
 @Test void unknownOrCrossPhaseScenarioCannotEnableReadOnlyFixture(){
  assertEquals("CALENDAR_READONLY",CalendarReadOnlySession.scenario("HTTP","CALENDAR_READONLY"));assertEquals("ANDROID_WRITE",CalendarReadOnlySession.scenario("HTTP","ANDROID_WRITE"));
  assertThrows(IllegalArgumentException.class,()->CalendarReadOnlySession.scenario("SCENARIO","CALENDAR_READONLY"));assertThrows(IllegalArgumentException.class,()->CalendarReadOnlySession.scenario("HTTP","UNKNOWN"));
  assertThrows(IllegalStateException.class,()->gate.enableAndroid(session.fixture));assertThrows(IllegalStateException.class,()->gate.enableCalendar(session));
 }
}
