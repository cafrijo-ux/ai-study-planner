package ai_study_planner;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;
import review.mobile.IsolatedStudyLogServer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest(classes=AiStudyPlannerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.MOCK)
class StudyLogIdempotencyH2Tests {
 static final String PASSWORD=UUID.randomUUID().toString();
 static final Map<String,Object> P=IsolatedStudyLogServer.properties(8104,UUID.randomUUID().toString(),new BCryptPasswordEncoder(12).encode(PASSWORD));
 @DynamicPropertySource static void config(DynamicPropertyRegistry r){P.forEach((k,v)->r.add(k,()->v));r.add("planner.study-log.idempotency.enabled",()->true);r.add("planner.study-log.idempotency.require-key",()->true);}
 @Autowired WebApplicationContext context;@Autowired JdbcTemplate jdbc;@Autowired StudyLogRepository logs;@Autowired SubjectRepository subjects;@Autowired GoalRepository goals;@Autowired StudyLogIdempotencyService service;@Autowired PlatformTransactionManager manager;
 MockMvc mvc;MockHttpSession session;String csrf;long a,b,sa,sb,other;final ObjectMapper json=new ObjectMapper();
 @BeforeEach void setup()throws Exception{
  assertThrows(ClassNotFoundException.class,()->Class.forName("com.mysql.cj.jdbc.Driver"));assertInstanceOf(org.springframework.mock.web.MockServletContext.class,context.getServletContext());
  try(var c=jdbc.getDataSource().getConnection()){assertEquals("H2 JDBC Driver",c.getMetaData().getDriverName());assertTrue(c.getMetaData().getURL().startsWith("jdbc:h2:mem:"));}
  jdbc.execute("DROP TABLE IF EXISTS study_log_requests");logs.deleteAll();subjects.deleteAll();goals.deleteAll();jdbc.update("DELETE FROM users");jdbc.update("INSERT INTO users(user_id,name) VALUES(1,'synthetic'),(2,'synthetic-other')");
  var ga=new Goal();ga.setUserId(1L);ga.setGoalName("A");ga.setExamDate(LocalDate.now().plusDays(30));a=goals.saveAndFlush(ga).getGoalId();var gb=new Goal();gb.setUserId(1L);gb.setGoalName("B");b=goals.saveAndFlush(gb).getGoalId();var go=new Goal();go.setUserId(2L);long og=goals.saveAndFlush(go).getGoalId();
  sa=subject(1,a);sb=subject(1,b);other=subject(2,og);
  try(var c=jdbc.getDataSource().getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("review/study-log-idempotency.h2.sql"));}
  mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();var r=mvc.perform(get("/auth/csrf")).andReturn();session=(MockHttpSession)r.getRequest().getSession();csrf=tree(r).path("token").asText();mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN",csrf).param("username","mobile-h2-owner").param("password",PASSWORD)).andReturn();csrf=tree(mvc.perform(get("/auth/csrf").session(session)).andReturn()).path("token").asText();
 }
 long subject(long user,long goal){var s=new Subject();s.setUserId(user);s.setGoalId(goal);s.setSubjectName("가상");return subjects.saveAndFlush(s).getSubjectId();}
 StudyLog payload(long user,long subject){var l=new StudyLog();l.setUserId(user);l.setSubjectId(subject);l.setStudyDate(LocalDate.of(2026,11,1));l.setStudyMinutes(40);l.setStudyContent("한글 · \"특수문자\"\n기록");l.setUnderstandingScore(3);l.setFocusScore(3);l.setFatigueScore(2);return l;}
 String key(){return "test_"+UUID.randomUUID().toString();}
 JsonNode tree(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsByteArray());}
 MvcResult postLog(String key,long goal,StudyLog l)throws Exception{var q=post("/study-logs").param("goalId",""+goal).session(session).header("X-CSRF-TOKEN",csrf).contentType("application/json").content(StudyLogIdempotencyService.encode(StudyLogIdempotencyService.fields(l)));if(key!=null)q.header("Idempotency-Key",key);return mvc.perform(q).andReturn();}
 void counts(int logsCount,int ledger){assertEquals(logsCount,logs.count());assertEquals(ledger,jdbc.queryForObject("SELECT COUNT(*) FROM study_log_requests",Integer.class));}
 @Test void sameKeyProducesExactlyOneLogAndOriginalResponse()throws Exception{String k=key();var p=payload(1,sa);var first=postLog(k,a,p);var again=postLog(k,a,p);assertEquals(200,first.getResponse().getStatus());assertEquals("CREATED",first.getResponse().getHeader("Idempotency-Status"));assertEquals("REPLAYED",again.getResponse().getHeader("Idempotency-Status"));assertEquals(first.getResponse().getContentAsString(),again.getResponse().getContentAsString());assertEquals(40,tree(first).path("studyMinutes").asInt());counts(1,1);}
 @Test void changedContentOrOwnedGoalConflicts()throws Exception{String k=key();assertEquals(200,postLog(k,a,payload(1,sa)).getResponse().getStatus());var changed=payload(1,sa);changed.setStudyMinutes(41);assertEquals(409,postLog(k,a,changed).getResponse().getStatus());assertEquals(409,postLog(k,b,payload(1,sb)).getResponse().getStatus());counts(1,1);}
 @Test void independentKeysAndDifferentUsersHaveIndependentScope(){String k=key();service.save(1,a,k,payload(1,sa));var otherGoal=subjects.findById(other).orElseThrow().getGoalId();service.save(2,otherGoal,k,payload(2,other));service.save(1,b,key(),payload(1,sb));counts(3,3);}
 @Test void normalizedJsonOrderAndServiceRecreationKeepSameHash()throws Exception{String k=key();var p=payload(1,sa);String first=service.save(1,a,k,p).json();var replacement=new StudyLogIdempotencyService(jdbc,logs,subjects,goals,manager,true);assertEquals(first,replacement.save(1,a,k,p).json());assertNull(p.getLogId());var m=StudyLogIdempotencyService.fields(p);var reverse=new LinkedHashMap<String,Object>();m.keySet().stream().sorted(Comparator.reverseOrder()).forEach(x->reverse.put(x,m.get(x)));var r=mvc.perform(post("/study-logs").param("goalId",""+a).session(session).header("X-CSRF-TOKEN",csrf).header("Idempotency-Key",k).contentType("application/json").content(json.writeValueAsBytes(reverse))).andReturn();assertEquals("REPLAYED",r.getResponse().getHeader("Idempotency-Status"));counts(1,1);}
 @Test void simultaneousRequestsAcrossServiceObjectsAndManyThreadsStoreOnce()throws Exception{var pool=Executors.newFixedThreadPool(6);String k=key();try{var tasks=new ArrayList<Callable<StudyLogIdempotencyService.Result>>();for(int i=0;i<12;i++)tasks.add(()->new StudyLogIdempotencyService(jdbc,logs,subjects,goals,manager,true).save(1,a,k,payload(1,sa)));var results=pool.invokeAll(tasks);var ids=new HashSet<String>();int created=0;for(var f:results){var r=f.get();ids.add(r.json());if(!r.replayed())created++;}assertEquals(1,created);assertEquals(1,ids.size());counts(1,1);}finally{pool.shutdownNow();}}
 @Test void failuresBeforeAndAfterFlushRollbackBothTables(){for(boolean after:List.of(false,true)){var failing=new StudyLogIdempotencyService(jdbc,logs,subjects,goals,manager,true){protected void beforePersist(){if(!after)throw new IllegalStateException("synthetic");}protected void afterPersist(){if(after)throw new IllegalStateException("synthetic");}};var e=assertThrows(StudyLogIdempotencyFailure.class,()->failing.save(1,a,key(),payload(1,sa)));assertEquals(503,e.status);counts(0,0);}}
 @Test void mockMvcReplyLossAfterServiceCommitNeverCreatesSecondRecord()throws Exception{
  String k=key();jakarta.servlet.Filter lost=(q,r,chain)->{chain.doFilter(q,r);if(((jakarta.servlet.http.HttpServletRequest)q).getRequestURI().equals("/study-logs"))throw new java.io.IOException("SYNTHETIC_REPLY_LOSS");};
  var broken=MockMvcBuilders.webAppContextSetup(context).addFilters(lost).apply(springSecurity()).build();
  assertThrows(Exception.class,()->broken.perform(post("/study-logs").param("goalId",""+a).session(session).header("X-CSRF-TOKEN",csrf).header("Idempotency-Key",k).contentType("application/json").content(StudyLogIdempotencyService.encode(StudyLogIdempotencyService.fields(payload(1,sa))))));
  assertEquals("COMPLETED",json.readTree(service.lookup(1,a,k)).path("state").asText());assertEquals("REPLAYED",postLog(k,a,payload(1,sa)).getResponse().getHeader("Idempotency-Status"));counts(1,1);
 }
 @Test void committedResponseLossIsRecoveredByGetWithoutPost()throws Exception{String k=key();var saved=service.save(1,a,k,payload(1,sa));var lookup=mvc.perform(get("/study-log-requests").session(session).param("goalId",""+a).param("requestKey",k)).andReturn();assertEquals("COMPLETED",tree(lookup).path("state").asText());assertEquals(json.readTree(saved.json()),tree(lookup).path("result"));counts(1,1);}
 @Test void unknownLookupIsNotSuccessAndWrongGoalIsRejected()throws Exception{String k=key();assertEquals("NOT_CONFIRMED",json.readTree(service.lookup(1,a,k)).path("state").asText());service.save(1,a,k,payload(1,sa));assertEquals(409,assertThrows(StudyLogIdempotencyFailure.class,()->service.lookup(1,b,k)).status);assertEquals("NOT_CONFIRMED",json.readTree(service.lookup(2,subjects.findById(other).orElseThrow().getGoalId(),k)).path("state").asText());}
 @Test void invalidScoresDatesTimeAndOwnershipNeverReserve()throws Exception{for(String field:List.of("minutes","score","date","owner","subject")){var l=payload(1,sa);switch(field){case "minutes"->l.setStudyMinutes(0);case "score"->l.setFocusScore(6);case "date"->l.setStudyDate(null);case "owner"->l.setUserId(2L);case "subject"->l.setSubjectId(other);}assertTrue(postLog(key(),a,l).getResponse().getStatus()>=400);}counts(0,0);assertEquals(400,postLog(null,a,payload(1,sa)).getResponse().getStatus());assertEquals(400,mvc.perform(post("/study-logs").session(session).header("X-CSRF-TOKEN",csrf).contentType("application/json").content("{\"studyDate\":\"not-a-date\"}")).andReturn().getResponse().getStatus());}
 @Test void authenticationAndCsrfCannotBypassPolicy()throws Exception{assertEquals(401,mvc.perform(get("/study-log-requests").param("goalId",""+a).param("requestKey",key())).andReturn().getResponse().getStatus());assertEquals(403,mvc.perform(post("/study-logs").session(session).contentType("application/json").content("{}")).andReturn().getResponse().getStatus());counts(0,0);}
 @Test void compatibleMissingKeyUsesExplicitLegacyHeader()throws Exception{var compatible=new StudyLogIdempotencyService(jdbc,logs,subjects,goals,manager,false);var c=context.getBean(StudyLogController.class);org.springframework.test.util.ReflectionTestUtils.setField(c,"idempotency",compatible);try{var r=postLog(null,a,payload(1,sa));assertEquals(200,r.getResponse().getStatus());assertEquals("BYPASSED_MISSING_KEY",r.getResponse().getHeader("Idempotency-Mode"));counts(1,0);}finally{org.springframework.test.util.ReflectionTestUtils.setField(c,"idempotency",service);}}
 @Test void missingLedgerFailsClosedWithoutCreatingStudyLog()throws Exception{jdbc.execute("DROP TABLE study_log_requests");assertEquals(503,postLog(key(),a,payload(1,sa)).getResponse().getStatus());assertEquals(0,logs.count());}
 @Test void twoConcurrentRequestsBeginTogetherAndStoreOnce()throws Exception{
  var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);String k=key();
  try{Callable<String> call=()->{ready.countDown();assertTrue(go.await(3,TimeUnit.SECONDS));return service.save(1,a,k,payload(1,sa)).json();};var one=pool.submit(call);var two=pool.submit(call);assertTrue(ready.await(3,TimeUnit.SECONDS));go.countDown();assertEquals(one.get(5,TimeUnit.SECONDS),two.get(5,TimeUnit.SECONDS));counts(1,1);}finally{go.countDown();pool.shutdownNow();}
 }
 @Test void lockTimeoutReportsBusyAndDoesNotLeaveReservation()throws Exception{
  try(var owner=jdbc.getDataSource().getConnection()){owner.setAutoCommit(false);try(var s=owner.prepareStatement("SELECT user_id FROM users WHERE user_id=1 FOR UPDATE")){s.executeQuery();}
   try{var outer=new org.springframework.transaction.support.TransactionTemplate(manager);var e=assertThrows(StudyLogIdempotencyFailure.class,()->outer.execute(status->{jdbc.execute("SET LOCK_TIMEOUT 100");try{return service.save(1,a,key(),payload(1,sa));}finally{jdbc.execute("SET LOCK_TIMEOUT 2000");}}));assertEquals(409,e.status);assertEquals("IDEMPOTENCY_BUSY",e.code);}finally{owner.rollback();}
  }counts(0,0);
 }
 @Test void unfinishedCommittedLedgerIsNotReplayedAsSuccess()throws Exception{
  String k=key();var p=payload(1,sa);jdbc.update("INSERT INTO study_log_requests(user_id,operation,request_key,goal_id,request_hash,request_state,created_at) VALUES(1,'STUDY_LOG_SAVE',?,?,?,'PROCESSING',CURRENT_TIMESTAMP)",k,a,StudyLogIdempotencyService.fingerprint(a,p));
  assertEquals("NOT_CONFIRMED",json.readTree(service.lookup(1,a,k)).path("state").asText());assertEquals(409,assertThrows(StudyLogIdempotencyFailure.class,()->service.save(1,a,k,p)).status);counts(0,1);
 }
 @Test void databaseUniqueKeyAndFinalPlansArePreserved()throws Exception{
  jdbc.execute("CREATE TABLE IF NOT EXISTS study_saved_plans(goal_id BIGINT,plan_status VARCHAR(10),plan_json CLOB)");jdbc.update("DELETE FROM study_saved_plans");jdbc.update("INSERT INTO study_saved_plans VALUES(?,'FINAL',?),(?,'FINAL',?)",a,"{\"virtual\":\"A\"}",b,"{\"virtual\":\"B\"}");
  var before=jdbc.queryForList("SELECT goal_id,plan_status,CAST(plan_json AS VARCHAR) AS plan_json FROM study_saved_plans ORDER BY goal_id");String k=key();service.save(1,a,k,payload(1,sa));service.save(1,a,k,payload(1,sa));
  assertEquals(before,jdbc.queryForList("SELECT goal_id,plan_status,CAST(plan_json AS VARCHAR) AS plan_json FROM study_saved_plans ORDER BY goal_id"));
  assertThrows(org.springframework.dao.DuplicateKeyException.class,()->jdbc.update("INSERT INTO study_log_requests(user_id,operation,request_key,goal_id,request_hash,request_state,created_at) VALUES(1,'STUDY_LOG_SAVE',?,?,?,'PROCESSING',CURRENT_TIMESTAMP)",k,a,"0".repeat(64)));counts(1,1);
 }
}
