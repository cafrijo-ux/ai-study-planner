package review.mysql;
import ai_study_planner.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Same controller chain for offline H2 and separately approved real-MySQL in-process API tests. No HTTP port. */
public final class IntegrationScenario {
 public record Result(long goalA,long goalB,long subjectA,long subjectB,Map<String,Object> proof){}
 public record Fixture(long goalA,long goalB,long subjectA,long subjectB,com.fasterxml.jackson.databind.JsonNode past){}
 final MockMvc mvc;final String runId;MockHttpSession session;String token;final ConfigurableApplicationContext ctx;
 public IntegrationScenario(ConfigurableApplicationContext ctx,String id){this.ctx=ctx;this.runId=id;this.mvc=MockMvcBuilders.webAppContextSetup((org.springframework.web.context.WebApplicationContext)ctx).addFilters(ctx.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class),ctx.getBean(IntegrationRuntime.Gate.class)).build();}
 com.fasterxml.jackson.databind.JsonNode tree(MvcResult r)throws Exception{return IntegrationRuntime.JSON.readTree(r.getResponse().getContentAsByteArray());}
 static void require(boolean value,String stage){if(!value)throw new IllegalStateException(stage);}
 public void login(String password)throws Exception{var r=mvc.perform(get("/auth/csrf")).andReturn();session=(MockHttpSession)r.getRequest().getSession();token=tree(r).path("token").asText();r=mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN",token).param("username",IntegrationSchema.OWNER).param("password",password)).andReturn();require(r.getResponse().getStatus()==200,"LOGIN");token=tree(mvc.perform(get("/auth/csrf").session(session)).andReturn()).path("token").asText();}
 public void verifyLogout()throws Exception{require(mvc.perform(post("/auth/logout").session(session).header("X-CSRF-TOKEN",token)).andReturn().getResponse().getStatus()==200,"LOGOUT");require(mvc.perform(get("/goals")).andReturn().getResponse().getStatus()==401,"UNAUTHENTICATED_AFTER_LOGOUT");}
 public MvcResult postRaw(String path,Long goal,Object value,String key,String fault)throws Exception{var q=post(path).session(session).header("X-CSRF-TOKEN",token).header("X-Integration-Run",runId).header("Idempotency-Key",key).contentType("application/json").content(IntegrationRuntime.JSON.writeValueAsBytes(value));if(goal!=null)q.queryParam("goalId",""+goal);if(fault!=null)q.header("X-Integration-Fault",fault);return mvc.perform(q).andReturn();}
 public com.fasterxml.jackson.databind.JsonNode postOk(String path,Long goal,Object value,String key)throws Exception{var r=postRaw(path,goal,value,key,null);require(r.getResponse().getStatus()==200,"POST_"+path);return tree(r);}
 public com.fasterxml.jackson.databind.JsonNode getOk(String path,Long goal,Map<String,String> params)throws Exception{var q=get(path).session(session);if(goal!=null)q.queryParam("goalId",""+goal);params.forEach(q::queryParam);var r=mvc.perform(q).andReturn();require(r.getResponse().getStatus()==200,"GET_"+path);return tree(r);}
 public static Map<String,Object> goal(String label){return Map.of("userId",1,"goalName","[통합] 목표 "+label,"goalType","시험 대비","examDate",LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(30).toString(),"targetScore","80","currentLevel","보통","weakPoint","개념","dailyStudyTime",240,"preferredStudyMethod","복습");}
 public static Map<String,Object> subject(String label){return Map.of("subjectName",label,"difficulty","보통","priorityLevel","높음","currentConfidence","보통");}
 public static Map<String,Object> log(long subject){return Map.of("userId",1,"subjectId",subject,"studyDate",LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1).toString(),"studyMinutes",40,"studyContent","[통합 가상] 학습","understandingScore",3,"focusScore",3,"fatigueScore",2,"completed","미완료");}
 public static String key(){return UUID.randomUUID().toString();}
 public Fixture initialize(String password)throws Exception{
  if(!LocalTime.now(ZoneId.of("Asia/Seoul")).isAfter(LocalTime.of(0,1)))throw new IllegalStateException("FINAL_WINDOW_NOT_OPEN");login(password);
  String registerKey=key();var a=postOk("/goal-registrations",null,Map.of("goal",goal("A"),"subject",subject("가상 운영체제")),registerKey);long ga=a.path("goal").path("goalId").asLong(),sa=a.path("subject").path("subjectId").asLong();
  var replay=postRaw("/goal-registrations",null,Map.of("goal",goal("A"),"subject",subject("가상 운영체제")),registerKey,null);require("true".equals(replay.getResponse().getHeader("Idempotency-Replayed")),"GOAL_REPLAY");
  postOk("/goals/"+ga+"/subjects",null,subject("가상 네트워크"),key());var b=postOk("/goal-registrations",null,Map.of("goal",goal("B"),"subject",subject("가상 자료구조")),key());long gb=b.path("goal").path("goalId").asLong(),sb=b.path("subject").path("subjectId").asLong();
  var past=postOk("/integration/fixture-plan",ga,Map.of(),key()).path("plan");postOk("/integration/fixture-plan",gb,Map.of(),key());
  LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));postOk("/study-schedule-settings",null,Map.of("scheduleDate",today.toString(),"startTime","00:00","endTime","00:01"),key());postOk("/study-schedule-settings",null,Map.of("scheduleDate",today.plusDays(1).toString(),"startTime","09:00","endTime","13:00"),key());
  return new Fixture(ga,gb,sa,sb,past);
 }
 public Result run(String password)throws Exception{
  var seed=initialize(password);long ga=seed.goalA(),gb=seed.goalB(),sa=seed.subjectA(),sb=seed.subjectB();var past=seed.past();LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
  String logKey=key();var saved=postOk("/study-logs",ga,log(sa),logKey);var second=postRaw("/study-logs",ga,log(sa),logKey,null);require(tree(second).path("logId").equals(saved.path("logId")),"LOG_REPLAY");postOk("/study-logs",gb,log(sb),key());
  var summary=getOk("/analysis-summary",ga,Map.of("scope","current"));require(summary.path("totalStudyMinutes").asInt()==40&&summary.path("averageFocus").asDouble()==3,"ANALYSIS_A");
  var j=ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class);String bBefore=j.queryForObject("SELECT plan_json FROM study_saved_plans WHERE goal_id=?",String.class,gb);String aPast=j.queryForObject("SELECT plan_json FROM study_saved_plans WHERE goal_id=?",String.class,ga);
  var preview=getOk("/study-reschedule-preview",ga,Map.of());require(preview.path("isScheduleValid").asBoolean()&&!preview.path("hasConflict").asBoolean(),"PREVIEW_VALID");
  require(aPast.equals(j.queryForObject("SELECT plan_json FROM study_saved_plans WHERE goal_id=?",String.class,ga)),"PREVIEW_DOES_NOT_SAVE");int total=preview.path("studyMinutes").asInt(),carry=preview.path("carryoverMinutes").asInt(),fresh=preview.path("newStudyMinutes").asInt(),pending=preview.path("carryoverTotalPendingMinutes").asInt();require(total==carry+fresh&&carry>0&&carry<=pending&&pending>0,"CALCULATION_RELATIONS");
  String planKey=key();var plan=postOk("/study-reschedule-save",ga,Map.of(),planKey);require(plan.path("planStatus").asText().equals("FINAL"),"FINAL_FROM_SERVER");postOk("/study-reschedule-save",ga,Map.of(),planKey);
  var stored=getOk("/study-reschedule-saved",ga,Map.of("date",today.plusDays(1).toString()));require(stored.path("studyMinutes").asInt()==total&&stored.path("carryoverMinutes").asInt()==carry&&stored.path("newStudyMinutes").asInt()==fresh,"SAVED_MATCH");require(bBefore.equals(j.queryForObject("SELECT plan_json FROM study_saved_plans WHERE goal_id=?",String.class,gb)),"B_FINAL_PRESERVED");
  postOk("/integration/mock-ai-record",ga,Map.of(),key());require(getOk("/ai-responses",ga,Map.of("feature","QUESTION")).size()==1&&getOk("/ai-responses",gb,Map.of("feature","QUESTION")).isEmpty(),"AI_HISTORY_SCOPE");
  var bs=getOk("/analysis-summary",gb,Map.of("scope","current"));require(bs.path("totalStudyMinutes").asInt()==40,"ANALYSIS_B");getOk("/study-reschedule-saved",ga,Map.of("date",today.plusDays(1).toString()));require(aPast.equals(j.queryForObject("SELECT plan_json FROM study_saved_plans WHERE goal_id=? AND plan_date=?",String.class,ga,java.sql.Date.valueOf(today.minusDays(1)))),"PAST_FINAL_PRESERVED");
  var proof=new LinkedHashMap<String,Object>();proof.put("goalA",ga);proof.put("goalB",gb);proof.put("inputPastStudyMinutes",past.path("studyMinutes").asInt());proof.put("actualMinutesA",40);proof.put("pending",pending);proof.put("carryover",carry);proof.put("newStudy",fresh);proof.put("total",total);proof.put("pastSubjectAllocations",past.path("subjectPlans"));proof.put("carryoverCalculation",preview.path("carryoverCalculation"));proof.put("unrecordedSubjects",preview.path("unrecordedSubjects"));proof.put("goalBPreserved",true);proof.put("realAiCalls",0);proof.put("note","MockMvc controller chain; H2/approved MySQL backend is identified separately");return new Result(ga,gb,sa,sb,proof);
 }
}
