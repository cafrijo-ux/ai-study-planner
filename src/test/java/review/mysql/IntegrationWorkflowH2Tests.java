package review.mysql;
import ai_study_planner.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.support.GenericWebApplicationContext;
import review.mobile.IsolatedStudyLogServer;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class IntegrationWorkflowH2Tests {
 static final String PASSWORD=UUID.randomUUID().toString(),HASH=new BCryptPasswordEncoder(12).encode(PASSWORD);
 GenericWebApplicationContext ctx;JdbcTemplate jdbc;String id,url;IntegrationScenario api;
 @TempDir Path temp;
 @BeforeEach void open()throws Exception{
  id=UUID.randomUUID().toString();url="jdbc:h2:mem:integration_"+id+";MODE=MySQL;DB_CLOSE_DELAY=-1";assertThrows(ClassNotFoundException.class,()->Class.forName("com.mysql.cj.jdbc.Driver"));IntegrationSchema.offlineH2(url,id);
  var p=IsolatedStudyLogServer.properties(8104,id,HASH);p.put("spring.datasource.url",url);p.put("spring.jpa.hibernate.ddl-auto","none");p.put("planner.auth.username",IntegrationSchema.OWNER);p.put("integration.run-id",id);
  var env=new org.springframework.core.env.StandardEnvironment();env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("offlineH2Only",p));var app=new SpringApplication(AiStudyPlannerApplication.class,MySqlIntegrationServer.Config.class);app.setEnvironment(env);app.setWebApplicationType(WebApplicationType.SERVLET);app.setApplicationContextFactory(type->{var c=new GenericWebApplicationContext();c.setServletContext(new MockServletContext());return c;});ctx=(GenericWebApplicationContext)app.run("--spring.config.location=optional:classpath:/integration-only.properties");jdbc=ctx.getBean(JdbcTemplate.class);
  try(var c=jdbc.getDataSource().getConnection()){assertEquals("H2 JDBC Driver",c.getMetaData().getDriverName());assertEquals(url.split(";")[0],c.getMetaData().getURL());IntegrationSchema.claim(c,id);}
  IntegrationRuntime.seed(ctx);api=new IntegrationScenario(ctx,id);
 }
 @AfterEach void close()throws Exception{if(ctx!=null){ctx.close();try(var c=DriverManager.getConnection(url,"sa","")){c.createStatement().execute("SHUTDOWN");}}}
 IntegrationScenario.Result run()throws Exception{return api.run(PASSWORD);}
 @Test void fullControllerWorkflowReportsDerivedNumbersAndPreservesB()throws Exception{
  var result=run();assertEquals(2,ctx.getBean(StudyLogRepository.class).count());assertEquals(1,ctx.getBean(AiResponseRepository.class).count());assertEquals(1,ctx.getBean(StudyQuestionRepository.class).count());assertEquals(3,ctx.getBean(GoalRepository.class).count());
  assertEquals((int)result.proof().get("total"),(int)result.proof().get("carryover")+(int)result.proof().get("newStudy"));assertTrue((int)result.proof().get("pending")>= (int)result.proof().get("carryover"));
  Path report=Path.of("build/reports/mysql-integration-preparation/h2-workflow-results.json");Files.createDirectories(report.getParent());Files.write(report,IntegrationRuntime.JSON.writeValueAsBytes(result.proof()));
  api.verifyLogout();
 }
 @Test void rollbackAfterControllerAndUncertainRetryNeverCreatePartialGoal()throws Exception{
  api.login(PASSWORD);int goals=jdbc.queryForObject("SELECT COUNT(*) FROM goals",Integer.class),subjects=jdbc.queryForObject("SELECT COUNT(*) FROM subjects",Integer.class);String key=IntegrationScenario.key();var body=Map.of("goal",IntegrationScenario.goal("오류"),"subject",IntegrationScenario.subject("가상 오류"));
  assertEquals(500,api.postRaw("/goal-registrations",null,body,key,"ROLLBACK_AFTER_WRITE").getResponse().getStatus());assertEquals(goals,jdbc.queryForObject("SELECT COUNT(*) FROM goals",Integer.class));assertEquals(subjects,jdbc.queryForObject("SELECT COUNT(*) FROM subjects",Integer.class));assertEquals(409,api.postRaw("/goal-registrations",null,body,key,"ROLLBACK_AFTER_WRITE").getResponse().getStatus());
 }
 @Test void duplicatesGoalSubjectAndConflictingReplayAreExplicit()throws Exception{
  api.login(PASSWORD);var body=Map.of("goal",IntegrationScenario.goal("A"),"subject",IntegrationScenario.subject("가상 운영체제"));var first=api.postOk("/goal-registrations",null,body,IntegrationScenario.key());long ga=first.path("goal").path("goalId").asLong();assertTrue(api.postOk("/goal-registrations",null,body,IntegrationScenario.key()).path("duplicate").asBoolean());
  assertTrue(api.postOk("/goals/"+ga+"/subjects",null,IntegrationScenario.subject("가상 운영체제"),IntegrationScenario.key()).path("duplicate").asBoolean());var changed=new HashMap<>(IntegrationScenario.subject("가상 운영체제"));changed.put("difficulty","어려움");assertEquals(409,api.postRaw("/goals/"+ga+"/subjects",null,changed,IntegrationScenario.key(),null).getResponse().getStatus());
  String key=IntegrationScenario.key();api.postOk("/goal-registrations",null,body,key);assertEquals(409,api.postRaw("/goal-registrations",null,Map.of("goal",IntegrationScenario.goal("다른"),"subject",IntegrationScenario.subject("다른")),key,null).getResponse().getStatus());
 }
 @Test void invalidOwnershipDateScoresAndMissingLogCannotWrite()throws Exception{
  var r=run();long before=ctx.getBean(StudyLogRepository.class).count();var wrong=new HashMap<>(IntegrationScenario.log(r.subjectB()));assertEquals(400,api.postRaw("/study-logs",r.goalA(),wrong,IntegrationScenario.key(),null).getResponse().getStatus());
  wrong=new HashMap<>(IntegrationScenario.log(r.subjectA()));wrong.put("focusScore",6);assertEquals(400,api.postRaw("/study-logs",r.goalA(),wrong,IntegrationScenario.key(),null).getResponse().getStatus());wrong.put("focusScore",3);wrong.put("logId",999999);assertEquals(404,api.postRaw("/study-logs",r.goalA(),wrong,IntegrationScenario.key(),null).getResponse().getStatus());
  long foreign=jdbc.queryForObject("SELECT goal_id FROM goals WHERE user_id=2",Long.class);assertEquals(404,api.mvc.perform(get("/study-plan").session(api.session).param("goalId",""+foreign)).andReturn().getResponse().getStatus());assertEquals(400,api.mvc.perform(get("/study-plan").session(api.session).param("date","bad")).andReturn().getResponse().getStatus());assertEquals(400,api.mvc.perform(get("/subjects").session(api.session).param("goalId",""+r.goalA(),""+r.goalB())).andReturn().getResponse().getStatus());assertEquals(before,ctx.getBean(StudyLogRepository.class).count());
 }
 @Test void missingAuthCsrfRunIdAndKeyAndRealAiPathsAreRejected()throws Exception{
  assertEquals(401,api.mvc.perform(get("/goals")).andReturn().getResponse().getStatus());api.login(PASSWORD);
  assertEquals(403,api.mvc.perform(post("/goal-registrations").session(api.session).contentType("application/json").content("{}")).andReturn().getResponse().getStatus());assertEquals(403,api.mvc.perform(post("/goal-registrations").session(api.session).header("X-CSRF-TOKEN",api.token).contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
  assertEquals(403,api.postRaw("/study-question",null,Map.of(),IntegrationScenario.key(),null).getResponse().getStatus());assertEquals(403,api.mvc.perform(get("/llm-coaching").session(api.session).header("X-CSRF-TOKEN",api.token)).andReturn().getResponse().getStatus());assertEquals(0,ctx.getBean(AiResponseRepository.class).count());
 }
 @Test void concurrentFinalSavesSerializeWithoutDuplicateRow()throws Exception{
  var r=run();var pool=Executors.newFixedThreadPool(2);try{var calls=List.<Callable<Integer>>of(()->api.postRaw("/study-reschedule-save",r.goalA(),Map.of(),IntegrationScenario.key(),null).getResponse().getStatus(),()->api.postRaw("/study-reschedule-save",r.goalA(),Map.of(),IntegrationScenario.key(),null).getResponse().getStatus());for(var f:pool.invokeAll(calls))assertEquals(200,f.get());}finally{pool.shutdownNow();}
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM study_saved_plans WHERE goal_id=? AND plan_date=?",Integer.class,r.goalA(),java.sql.Date.valueOf(LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1))));
 }
 @Test void foreignKeysUniqueAndSavedPlanTransactionRollbackAreRealH2Checks()throws Exception{
  var r=run();assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO subjects(user_id,goal_id,subject_name) VALUES(2,?,?)",r.goalA(),"불일치"));
  var old=IntegrationRuntime.snapshot(jdbc);var plans=ctx.getBean(StudySavedPlanService.class);var day=LocalDate.now(ZoneId.of("Asia/Seoul"));var copy=plans.find(r.goalA(),day.plusDays(1)).plan();var tx=new org.springframework.transaction.support.TransactionTemplate(ctx.getBean(org.springframework.transaction.PlatformTransactionManager.class));assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{plans.save(r.goalA(),day,LocalTime.of(0,1),day.plusDays(2),"FINAL","09:00","13:00","","",copy);throw new IllegalStateException("Injected transaction error");}));assertEquals(old,IntegrationRuntime.snapshot(jdbc));
 }
 @Test void everyEntityColumnMatchesPreparedSchemaAndIdentityKeys()throws Exception{
  try(var c=jdbc.getDataSource().getConnection()){
   for(Class<?> type:List.of(User.class,Goal.class,Subject.class,StudyLog.class,StudyQuestion.class,AiResponse.class)){
    String table=type.getAnnotation(jakarta.persistence.Table.class).name();var actual=new HashSet<String>();try(var r=c.getMetaData().getColumns(null,"PUBLIC",table.toUpperCase(Locale.ROOT),null)){while(r.next())actual.add(r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));}
    var expected=new HashSet<String>();for(var f:type.getDeclaredFields()){if(java.lang.reflect.Modifier.isStatic(f.getModifiers())||f.isAnnotationPresent(jakarta.persistence.Transient.class))continue;var a=f.getAnnotation(jakarta.persistence.Column.class);String name=a!=null&&!a.name().isBlank()?a.name():f.getName().replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);expected.add(name);}
    assertEquals(expected,actual,table);
   }
   assertEquals(12,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='PUBLIC' AND table_name='AI_RESPONSES'",Integer.class));
  }
 }
 @Test void simultaneousGoalRegistrationAndReservedReceiptAreSafe()throws Exception{
  api.login(PASSWORD);var body=Map.of("goal",IntegrationScenario.goal("동시"),"subject",IntegrationScenario.subject("가상 동시 과목"));var pool=Executors.newFixedThreadPool(2);
  try{var calls=List.<Callable<Integer>>of(()->api.postRaw("/goal-registrations",null,body,IntegrationScenario.key(),null).getResponse().getStatus(),()->api.postRaw("/goal-registrations",null,body,IntegrationScenario.key(),null).getResponse().getStatus());for(var f:pool.invokeAll(calls))assertEquals(200,f.get());}finally{pool.shutdownNow();}
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM goals WHERE user_id=1",Integer.class));
  String key=IntegrationScenario.key();byte[] bytes=IntegrationRuntime.JSON.writeValueAsBytes(body);String hash=review.mobile.GeminiShutdown.sha(("/goal-registrations?|"+"|"+new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  jdbc.update("INSERT INTO integration_requests VALUES(?,?,?,'RESERVED',NULL,NULL)",id,key,hash);assertEquals(409,api.postRaw("/goal-registrations",null,body,key,null).getResponse().getStatus());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM goals WHERE user_id=1",Integer.class));
 }
 @Test void committedStudyLogWithLostReplyIsNeverWrittenTwice()throws Exception{
  var f=api.initialize(PASSWORD);var baseline=jdbc.queryForList("SELECT plan_json FROM study_saved_plans ORDER BY plan_id");String key=IntegrationScenario.key();var body=IntegrationScenario.log(f.subjectA());
  assertEquals(500,api.postRaw("/study-logs",f.goalA(),body,key,"LOSE_REPLY_AFTER_COMMIT").getResponse().getStatus());assertEquals(1,ctx.getBean(StudyLogRepository.class).count());assertEquals(409,api.postRaw("/study-logs",f.goalA(),body,key,"LOSE_REPLY_AFTER_COMMIT").getResponse().getStatus());assertEquals(1,ctx.getBean(StudyLogRepository.class).count());assertEquals(40,api.getOk("/analysis-summary",f.goalA(),Map.of("scope","current")).path("totalStudyMinutes").asInt());assertEquals(baseline,jdbc.queryForList("SELECT plan_json FROM study_saved_plans ORDER BY plan_id"));
 }
 @Test void requiresNewAiStorageQuotaIsCheckedBeforeItsCommit()throws Exception{
  var f=api.initialize(PASSWORD);api.postOk("/integration/mock-ai-record",f.goalA(),Map.of(),IntegrationScenario.key());api.postOk("/integration/mock-ai-record",f.goalA(),Map.of(),IntegrationScenario.key());assertEquals(429,api.postRaw("/integration/mock-ai-record",f.goalA(),Map.of(),IntegrationScenario.key(),null).getResponse().getStatus());assertEquals(2,ctx.getBean(AiResponseRepository.class).count());assertEquals(2,ctx.getBean(StudyQuestionRepository.class).count());
 }
}
