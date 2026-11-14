package review.mysql;
import ai_study_planner.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.ContentCachingResponseWrapper;
import review.mobile.GeminiShutdown;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class IntegrationRuntime {
 public static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
 public static void seed(ConfigurableApplicationContext ctx){
  var j=ctx.getBean(JdbcTemplate.class);j.update("INSERT INTO users(user_id,name,email) VALUES(1,?,?),(2,?,?)","[통합] 가상 사용자","one@example.invalid","[통합] 소유권 검사 사용자","two@example.invalid");
  var g=new Goal();g.setUserId(2L);g.setGoalName("[통합] 다른 사용자 목표");g.setExamDate(LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(30));long id=ctx.getBean(GoalRepository.class).saveAndFlush(g).getGoalId();var s=new Subject();s.setUserId(2L);s.setGoalId(id);s.setSubjectName("[통합] 다른 사용자 과목");ctx.getBean(SubjectRepository.class).saveAndFlush(s);
 }
 public static Map<String,Object> snapshot(JdbcTemplate j){
  var result=new TreeMap<String,Object>();for(String table:IntegrationSchema.BUSINESS){var rows=j.queryForList("SELECT * FROM "+table);var canonical=new ArrayList<String>();for(var row:rows){var values=new TreeMap<String,String>();row.forEach((k,v)->values.put(k.toLowerCase(Locale.ROOT),v==null?"<NULL>":v.toString()));try{canonical.add(JSON.writeValueAsString(values));}catch(Exception e){throw new IllegalStateException();}}Collections.sort(canonical);result.put(table,Map.of("rows",rows.size(),"sha256",GeminiShutdown.sha(String.join("\n",canonical).getBytes(StandardCharsets.UTF_8))));}return result;
 }
 /** Test-mode-only idempotency. No changes/new tables are required in existing production schemas. */
 public static final class Gate implements Filter {
  final JdbcTemplate jdbc;final TransactionTemplate tx;final String id;final boolean http;boolean closing;int active,requests;
  final ThreadLocal<Boolean> bootstrap=ThreadLocal.withInitial(()->false);volatile AndroidSession android;volatile CalendarReadOnlySession calendar;
  public Gate(ConfigurableApplicationContext ctx,String id){this.jdbc=ctx.getBean(JdbcTemplate.class);this.tx=new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));this.id=IntegrationSchema.id(id);this.http="HTTP".equals(ctx.getEnvironment().getProperty("integration.phase","SCENARIO"));}
  public <T>T bootstrapAndroid(java.util.concurrent.Callable<T> action)throws Exception{if(!http||android!=null||calendar!=null)throw new IOException("HTTP fixture bootstrap only once");bootstrap.set(true);try{return action.call();}finally{bootstrap.remove();}}
  public void enableCalendar(CalendarReadOnlySession session){if(!http||android!=null||calendar!=null||session==null)throw new IllegalStateException("HTTP policy already set");calendar=session;}
  public void enableAndroid(IntegrationScenario.Fixture f){if(!http||android!=null||calendar!=null)throw new IllegalStateException("HTTP policy already set");android=new AndroidSession(jdbc,f);}
  public synchronized void quiesce(long millis)throws Exception{closing=true;long end=System.nanoTime()+millis*1_000_000;while(active>0){long left=end-System.nanoTime();if(left<=0)throw new IOException("Requests active");wait(Math.max(1,left/1_000_000));}}
  @Override public void doFilter(ServletRequest raw,ServletResponse out,FilterChain chain)throws IOException,ServletException{
   var q=(HttpServletRequest)raw;var r=(HttpServletResponse)out;if(!Set.of("127.0.0.1","::1").contains(q.getRemoteAddr())){reply(r,403,"루프백 전용 통합 환경입니다.");return;}
   synchronized(this){if(closing){reply(r,503,"종료 중");return;}if(++requests>1000){reply(r,429,"통합 요청 상한 초과");return;}active++;}
   try{handle(q,r,chain);}finally{synchronized(this){active--;notifyAll();}}
  }
  void handle(HttpServletRequest q,HttpServletResponse r,FilterChain chain)throws IOException,ServletException{
   String path=q.getRequestURI(),method=q.getMethod();
   if(http&&!bootstrap.get()&&android==null&&calendar==null){reply(r,503,"가상 Android 검증 자료를 준비 중입니다.");return;}
   if(calendar!=null){
    r.setHeader("X-Integration-Scenario",CalendarReadOnlySession.MODE);
    boolean asset=path.startsWith("/")&&CalendarReadOnlySession.ASSETS.contains(path.substring(1));
    // Reviewed rule-based dashboard GET helpers; never LLM generation paths.
    if(method.equals("GET")&&(asset||Set.of("/auto-reschedule","/study-progress","/ai-coaching").contains(path))){chain.doFilter(q,r);return;}
   }
   boolean read=method.equals("GET")&&Set.of("/auth/csrf","/auth/me","/goals","/subjects","/study-logs","/study-log-idempotency","/study-log-requests","/study-schedule-settings","/study-plan","/study-reschedule-preview","/study-reschedule-saved","/study-reschedule-calendar","/analysis-summary","/study-analysis","/subject-analysis","/study-question/capabilities","/llm-config-check","/gpt-weekly-coaching/preview","/ai-responses","/study-questions","/login.html","/login.js","/auth-client.js").contains(path);
   if(read){chain.doFilter(q,r);if(calendar!=null&&path.equals("/study-reschedule-calendar")&&r.getStatus()==200)calendar.observed(q.getParameter("goalId"));return;}
   if(calendar!=null&&!bootstrap.get()){reply(r,403,"CALENDAR_READONLY: 달력 비교 전용입니다. 자료 저장·AI 생성은 허용하지 않습니다.");return;}
   if(http&&!bootstrap.get()&&!Set.of("/study-logs","/study-reschedule-save").contains(path)){reply(r,403,"Android 검증은 목표 A의 기록1건과 계획1건만 저장합니다. AI 생성·자료 재생성은 차단됩니다.");return;}
   boolean write=method.equals("POST")&&(Set.of("/goal-registrations","/study-logs","/study-schedule-settings","/study-reschedule-save","/integration/fixture-plan","/integration/mock-ai-record").contains(path)||path.matches("/goals/[1-9][0-9]*/subjects"));
   if(!write){reply(r,403,"이 모드는 승인된 가상 통합 시나리오만 허용합니다. 실제 AI/다른 저장은 차단됩니다.");return;}
   if(q.getUserPrincipal()==null||!IntegrationSchema.OWNER.equals(q.getUserPrincipal().getName())){reply(r,401,"통합 가상 계정이 필요합니다.");return;}
   if(!id.equals(q.getHeader("X-Integration-Run"))){reply(r,403,"통합 실행 ID를 확인하세요.");return;}
   if(Set.of("/study-logs","/study-reschedule-save","/integration/fixture-plan","/integration/mock-ai-record").contains(path)){
    String[] selected=q.getParameterValues("goalId");if(selected==null||selected.length!=1||!selected[0].matches("[1-9][0-9]*")){reply(r,400,"이 통합 모드는 목표 번호 하나를 명시해야 합니다.");return;}
   }
   String key=q.getHeader("Idempotency-Key");if(key==null||!key.matches("[a-z0-9_-]{16,80}")){reply(r,400,"논리 저장 요청별 소문자 멱등 키가 필요합니다.");return;}
   byte[] body=q.getInputStream().readNBytes(16385);if(body.length>16384){reply(r,400,"요청 크기 초과");return;}
   String fault=q.getHeader("X-Integration-Fault");if(fault!=null&&!Set.of("ROLLBACK_AFTER_WRITE","LOSE_REPLY_AFTER_COMMIT").contains(fault)){reply(r,400,"지원되지 않는 격리 오류 주입");return;}
   if(fault!=null&&path.equals("/integration/mock-ai-record")){reply(r,400,"별도 AI 저장 트랜잭션은 이 오류 주입 대상이 아닙니다.");return;}
   String hash=GeminiShutdown.sha((path+"?"+Objects.toString(q.getQueryString(),"")+"|"+Objects.toString(fault,"")+"|"+new String(body,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
   var prior=tx.execute(status->{jdbc.queryForObject("SELECT run_id FROM integration_run_state WHERE run_id=? FOR UPDATE",String.class,id);var rows=jdbc.queryForList("SELECT * FROM integration_requests WHERE run_id=? AND request_key=?",id,key);if(!rows.isEmpty())return rows.get(0);if(jdbc.queryForObject("SELECT COUNT(*) FROM integration_requests WHERE run_id=?",Integer.class,id)>=80)throw new IllegalStateException("Request limit");jdbc.update("INSERT INTO integration_requests(run_id,request_key,request_hash,state) VALUES(?,?,?,'RESERVED')",id,key,hash);return null;});
   if(prior!=null){if(!hash.equals(prior.get("request_hash"))){reply(r,409,"같은 멱등 키에 다른 요청을 사용할 수 없습니다.");return;}if(!"COMPLETE".equals(prior.get("state"))){reply(r,409,"이전 저장 결과가 불확실합니다. POST 재전송 대신 GET으로 확인하세요.");return;}r.setStatus(((Number)prior.get("http_status")).intValue());r.setContentType("application/json;charset=UTF-8");r.setHeader("Idempotency-Replayed","true");r.getWriter().write(prior.get("response_body").toString());return;}
   var cached=new ContentCachingResponseWrapper(r);var replay=replay(q,body);
   try{
    tx.executeWithoutResult(status->{jdbc.queryForObject("SELECT run_id FROM integration_run_state WHERE run_id=? FOR UPDATE",String.class,id);try{if(android!=null&&!bootstrap.get()){String rejected=android.reject(q,body);if(rejected!=null){reply(cached,409,rejected);return;}}if(path.equals("/integration/mock-ai-record")&&(jdbc.queryForObject("SELECT COUNT(*) FROM ai_responses",Integer.class)>=2||jdbc.queryForObject("SELECT COUNT(*) FROM study_questions",Integer.class)>=2)){reply(cached,429,"가상 질문·응답 저장 한도입니다.");return;}chain.doFilter(replay,cached);}catch(Exception failure){throw new IllegalStateException("Controller failed");}if(cached.getStatus()>=400)status.setRollbackOnly();else{if("ROLLBACK_AFTER_WRITE".equals(fault))throw new IllegalStateException("Injected rollback");limits();}});
    if("LOSE_REPLY_AFTER_COMMIT".equals(fault))throw new IllegalStateException("Injected lost receipt after business commit");
    byte[] answer=cached.getContentAsByteArray();if(answer.length>262144)throw new IllegalStateException("Response too large");
    tx.executeWithoutResult(status->jdbc.update("UPDATE integration_requests SET state='COMPLETE',http_status=?,response_body=? WHERE run_id=? AND request_key=?",cached.getStatus(),new String(answer,StandardCharsets.UTF_8),id,key));
    cached.copyBodyToResponse();
   }catch(Exception failure){try{tx.executeWithoutResult(status->jdbc.update("UPDATE integration_requests SET state='UNCERTAIN' WHERE run_id=? AND request_key=?",id,key));}catch(Exception ignored){}cached.resetBuffer();cached.setContentLength(-1);reply(cached,500,"처리가 실패했거나 저장 결과가 불확실합니다. 자동 재전송하지 말고 GET으로 확인하세요.");cached.copyBodyToResponse();}
  }
  void limits(){var max=Map.of("users",2,"goals",3,"subjects",5,"study_logs",8,"study_saved_plans",8,"study_schedule_settings",5,"study_questions",2,"ai_responses",2);for(var e:max.entrySet())if(jdbc.queryForObject("SELECT COUNT(*) FROM "+e.getKey(),Integer.class)>e.getValue())throw new IllegalStateException("Approved fixture size exceeded");}
  static void reply(HttpServletResponse r,int code,String message)throws IOException{r.setStatus(code);r.setContentType("application/json;charset=UTF-8");r.getWriter().write(JSON.writeValueAsString(Map.of("message",message)));}
  static HttpServletRequest replay(HttpServletRequest q,byte[] bytes){return new HttpServletRequestWrapper(q){public ServletInputStream getInputStream(){var in=new ByteArrayInputStream(bytes);return new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener l){throw new UnsupportedOperationException();}};}public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}};}
 }
 /** HTTP-only, durable one-record/one-plan quota. SCENARIO retains its own policy. */
 public static final class AndroidSession {
  final JdbcTemplate jdbc;final long goalA,goalB,subjectA;final LocalDate today,studyDate,planDate;final Map<String,Object> baseline;final Clock clock;final Instant preparedAt;
  AndroidSession(JdbcTemplate j,IntegrationScenario.Fixture f){this(j,f,Clock.systemUTC());}
  AndroidSession(JdbcTemplate j,IntegrationScenario.Fixture f,Clock c){jdbc=j;goalA=f.goalA();goalB=f.goalB();subjectA=f.subjectA();clock=c.withZone(ZoneId.of("Asia/Seoul"));preparedAt=clock.instant();today=LocalDate.now(clock);studyDate=today.minusDays(1);planDate=today.plusDays(1);baseline=protectedRows();}
  public Map<String,Object> dateEvidence(){return Map.of("testDate",today.toString(),"studyDate",studyDate.toString(),"planDate",planDate.toString(),"preparedAtUtc",preparedAt.toString());}
  String reject(HttpServletRequest q,byte[] body)throws IOException {
   if(!LocalDate.now(clock).equals(today))return "ANDROID_TEST_DATE_EXPIRED: 검증 날짜가 변경됐습니다. 저장하지 말고 정상 종료하세요.";
   if(!Long.toString(goalA).equals(q.getParameter("goalId")))return "목표 A에만 시험 저장할 수 있습니다.";
   if(q.getRequestURI().equals("/study-logs")){
    if(jdbc.queryForObject("SELECT COUNT(*) FROM study_logs",Integer.class)!=0)return "기록1건 저장 한도를 사용했습니다. POST 대신 GET으로 확인하세요.";
    var b=JSON.readTree(body);if(b==null||!b.isObject()||(!b.path("logId").isMissingNode()&&!b.path("logId").isNull())||!studyDate.toString().equals(b.path("studyDate").asText()))return "승인된 새 공부 기록과 날짜를 확인하세요.";
    for(var e:Map.of("userId",1L,"subjectId",subjectA,"studyMinutes",40L,"understandingScore",3L,"focusScore",3L,"fatigueScore",2L).entrySet())if(!b.path(e.getKey()).isIntegralNumber()||b.path(e.getKey()).asLong()!=e.getValue())return "운영체제 과목40분·이해도3·집중도3·피로도2만 시험 저장합니다.";
   }else{
    if(body.length!=0)return "재조정 저장은 빈 본문으로 요청해야 합니다.";
    if(jdbc.queryForObject("SELECT COUNT(*) FROM study_logs WHERE user_id=1 AND subject_id=? AND study_date=? AND study_minutes=40 AND understanding_score=3 AND focus_score=3 AND fatigue_score=2",Integer.class,subjectA,java.sql.Date.valueOf(studyDate))!=1)return "먼저 목표 A의 시험 공부 기록1건을 확인하세요.";
    if(jdbc.queryForObject("SELECT COUNT(*) FROM study_saved_plans WHERE user_id=1 AND goal_id=? AND plan_date=?",Integer.class,goalA,java.sql.Date.valueOf(planDate))!=0)return "계획1건 저장 한도를 사용했습니다. 저장본 GET으로 확인하세요.";
   }return null;
  }
  Map<String,Object> rows(String sql,Object... args){var strings=new ArrayList<String>();for(var row:jdbc.queryForList(sql,args)){var v=new TreeMap<String,String>();row.forEach((k,x)->v.put(k.toLowerCase(Locale.ROOT),x==null?"<NULL>":x.toString()));try{strings.add(JSON.writeValueAsString(v));}catch(IOException e){throw new IllegalStateException("Snapshot encoding");}}Collections.sort(strings);return Map.of("rows",strings.size(),"sha256",GeminiShutdown.sha(String.join("\n",strings).getBytes(StandardCharsets.UTF_8)));}
  Map<String,Object> protectedRows(){var r=new TreeMap<String,Object>();for(String table:List.of("users","goals","subjects","study_schedule_settings","study_questions","ai_responses"))r.put(table,rows("SELECT * FROM "+table));r.put("plans",rows("SELECT * FROM study_saved_plans WHERE NOT(user_id=1 AND goal_id=? AND plan_date=?)",goalA,java.sql.Date.valueOf(planDate)));r.put("logs",rows("SELECT * FROM study_logs WHERE NOT(user_id=1 AND subject_id=?)",subjectA));return r;}
  public Map<String,Object> verify(){
   boolean preserved=baseline.equals(protectedRows());int logs=jdbc.queryForObject("SELECT COUNT(*) FROM study_logs WHERE user_id=1 AND subject_id=?",Integer.class,subjectA);var plans=jdbc.queryForList("SELECT plan_status,plan_json FROM study_saved_plans WHERE user_id=1 AND goal_id=? AND plan_date=?",goalA,java.sql.Date.valueOf(planDate));
   var proof=new LinkedHashMap<String,Object>();proof.put("mode","ANDROID_HTTP_MYSQL");proof.put("goalA",goalA);proof.put("goalB",goalB);proof.put("studyDate",studyDate.toString());proof.put("planDate",planDate.toString());proof.put("protectedFixturesPreserved",preserved);proof.put("studyRecords",logs);proof.put("savedPlans",plans.size());proof.put("goalBRecords",jdbc.queryForObject("SELECT COUNT(*) FROM study_logs l JOIN subjects s ON s.subject_id=l.subject_id WHERE s.goal_id=?",Integer.class,goalB));
   boolean matches=logs==1&&jdbc.queryForObject("SELECT COUNT(*) FROM study_logs WHERE user_id=1 AND subject_id=? AND study_date=? AND study_minutes=40 AND understanding_score=3 AND focus_score=3 AND fatigue_score=2",Integer.class,subjectA,java.sql.Date.valueOf(studyDate))==1;proof.put("studyRecordMatches",matches);
   boolean dateValid=LocalDate.now(clock).equals(today);proof.put("testDate",today.toString());proof.put("currentSeoulDate",LocalDate.now(clock).toString());proof.put("dateValid",dateValid);
   boolean complete=dateValid&&preserved&&matches&&plans.size()==1&&"FINAL".equals(plans.get(0).get("plan_status"));
   if(plans.size()==1)try{var p=JSON.readTree(plans.get(0).get("plan_json").toString());int total=p.path("studyMinutes").asInt(),carry=p.path("carryoverMinutes").asInt(),fresh=p.path("newStudyMinutes").asInt();proof.put("total",total);proof.put("carryover",carry);proof.put("newStudy",fresh);proof.put("pending",p.path("carryoverTotalPendingMinutes").asInt());complete&=total==carry+fresh&&total>=0&&carry>=0&&fresh>=0&&p.path("goalId").asLong()==goalA&&planDate.toString().equals(p.path("planDate").asText(p.path("scheduleDate").asText()));}catch(IOException e){complete=false;}
   proof.put("flowComplete",complete);proof.put("realAiCalls",0);return proof;
  }
 }
 @org.springframework.web.bind.annotation.RestController
 public static class FixtureApi {
  final ConfigurableApplicationContext ctx;
  public FixtureApi(ConfigurableApplicationContext ctx){this.ctx=ctx;}
  @org.springframework.web.bind.annotation.PostMapping("/integration/fixture-plan")
  public Map<String,Object> pastPlan(){
   long goal=Objects.requireNonNull(GoalSelection.requestedId());LocalDate day=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
   var jdbc=ctx.getBean(JdbcTemplate.class);if(jdbc.queryForObject("SELECT COUNT(*) FROM study_saved_plans WHERE user_id=1 AND goal_id=? AND plan_date=?",Integer.class,goal,java.sql.Date.valueOf(day))!=0)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"가상 과거 FINAL은 덮어쓰지 않습니다.");
   if(jdbc.update("UPDATE goals SET created_at=? WHERE user_id=1 AND goal_id=?",java.sql.Timestamp.valueOf(day.minusDays(1).atStartOfDay()),goal)!=1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"가상 목표만 지정하세요.");
   var plan=ctx.getBean(StudyPlanController.class).getStudyPlan("09:00","13:00","","",day.toString());
   ctx.getBean(StudySavedPlanService.class).save(goal,day.minusDays(1),LocalTime.of(13,0),day,"FINAL","09:00","13:00","","",plan);return Map.of("saved",true,"fixtureOnly",true,"date",day.toString(),"plan",plan);
  }
  @org.springframework.web.bind.annotation.PostMapping("/integration/mock-ai-record")
  public Map<String,Object> mockRecord(){
   var q=new StudyQuestion();q.setQuestion("[통합 가상] 프로세스와 스레드");q.setAiAdvice("[통합 검증 Mock · 실제 AI 아님] 가상 답변 저장 계약입니다.");q.setSubject("가상 운영체제");
   var a=ctx.getBean(AiResponseService.class).save(GoalSelection.requestedId(),AiResponse.Feature.QUESTION,"gemini",null,null,AiResponse.Source.UNKNOWN,q.getAiAdvice(),null,q);
   return Map.of("responseSaved",true,"responseId",a.getResponseId(),"questionId",a.getQuestionId(),"goalId",a.getGoalId(),"testMode","MYSQL_INTEGRATION_MOCK_ONLY","responseSource","UNKNOWN");
  }
 }
}
