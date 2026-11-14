package review.mysql;
import ai_study_planner.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

/** Dedicated fake transport and gate. Does not install or alter any older AI scenario. */
public final class DualLedgerRuntime {
 public static final String MARKER="AI_MOCK_DUAL_LEDGER_ONLY"; // No claim about the actual JDBC backend; READY records H2/MySQL separately.
 public record Fixture(long goalA,long goalB,long subjectA,long subjectB,LocalDate day){}
 public static Map<String,Object> fixtureJson(Fixture f){return Map.of("goalA",f.goalA(),"goalB",f.goalB(),"subjectA",f.subjectA(),"subjectB",f.subjectB(),"day",f.day().toString());}
 public static Fixture seed(ConfigurableApplicationContext ctx)throws Exception{
  var j=ctx.getBean(JdbcTemplate.class);for(String t:DualLedgerProfile.TABLES)if(!t.equals("integration_run_state")&&j.queryForObject("SELECT COUNT(*) FROM "+t,Integer.class)!=0)throw new IllegalStateException("Seed requires empty dedicated schema");
  var tx=new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
  return tx.execute(status->{try{
   IntegrationRuntime.seed(ctx);LocalDate day=LocalDate.now(ZoneId.of("Asia/Seoul"));var goals=ctx.getBean(GoalRepository.class);var subjects=ctx.getBean(SubjectRepository.class);long[] gs=new long[2],ss=new long[2];
   for(int i=0;i<2;i++){var g=new Goal();g.setUserId(1L);g.setGoalName("[가상 두 원장] 목표 "+(i==0?"A":"B"));g.setGoalType("시험 대비");g.setExamDate(day.plusDays(30));g.setDailyStudyTime(240);g.setCurrentLevel("보통");g.setCreatedAt(day.minusDays(2).atStartOfDay());gs[i]=goals.saveAndFlush(g).getGoalId();var s=new Subject();s.setUserId(1L);s.setGoalId(gs[i]);s.setSubjectName(i==0?"[가상] 운영체제 A":"[가상] 네트워크 B");s.setDifficulty("보통");s.setPriorityLevel("높음");s.setCurrentConfidence("보통");ss[i]=subjects.saveAndFlush(s).getSubjectId();
    var req=new MockHttpServletRequest();req.setParameter("goalId",""+gs[i]);var prior=RequestContextHolder.getRequestAttributes();try{RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));var plan=ctx.getBean(StudyPlanController.class).getStudyPlan("09:00","13:00","","",day.minusDays(1).toString());ctx.getBean(StudySavedPlanService.class).save(gs[i],day.minusDays(2),LocalTime.of(13,0),day.minusDays(1),"FINAL","09:00","13:00","","",plan);}finally{if(prior==null)RequestContextHolder.resetRequestAttributes();else RequestContextHolder.setRequestAttributes(prior);}
   }
   var b=new StudyLog();b.setUserId(1L);b.setSubjectId(ss[1]);b.setStudyDate(day.minusDays(1));b.setStudyMinutes(30);b.setStudyContent("[가상] B 보존 기록");b.setUnderstandingScore(3);b.setFocusScore(3);b.setFatigueScore(2);ctx.getBean(StudyLogRepository.class).saveAndFlush(b);
   // save() also creates yesterday's settings. Today/tomorrow are new, explicit test-only settings.
   for(var date:List.of(day,day.plusDays(1)))j.update("INSERT INTO study_schedule_settings(user_id,schedule_date,start_time,end_time) VALUES(1,?,'09:00:00','13:00:00')",java.sql.Date.valueOf(date));
   return new Fixture(gs[0],gs[1],ss[0],ss[1],day);
  }catch(Exception e){throw new IllegalStateException("Synthetic fixture failed",e);}});
 }
 static Object safe(Object v)throws Exception{if(v==null)return null;if(v instanceof java.sql.Clob c)return c.getSubString(1,Math.toIntExact(c.length()));if(v instanceof byte[] b)return HexFormat.of().formatHex(b);return v.toString();}
 public static Map<String,Object> snapshot(JdbcTemplate j,Collection<String> tables){var result=new TreeMap<String,Object>();for(String t:tables){if(!DualLedgerProfile.TABLES.contains(t))throw new IllegalArgumentException();var rows=j.queryForList("SELECT * FROM "+t);result.put(t,digest(rows));}return result;}
 static Map<String,Object> digest(List<Map<String,Object>> rows){var lines=new ArrayList<String>();for(var row:rows){var values=new TreeMap<String,Object>();try{for(var e:row.entrySet())values.put(e.getKey().toLowerCase(Locale.ROOT),safe(e.getValue()));lines.add(IntegrationRuntime.JSON.writeValueAsString(values));}catch(Exception e){throw new IllegalStateException("Snapshot failed");}}Collections.sort(lines);return Map.of("rows",rows.size(),"sha256",review.mobile.GeminiShutdown.sha(String.join("\n",lines).getBytes(StandardCharsets.UTF_8)));}
 public static Map<String,Object> protectedRows(JdbcTemplate j,Fixture f){var m=new TreeMap<>(snapshot(j,List.of("users","goals","subjects","study_saved_plans","study_schedule_settings","study_questions")));m.put("otherStudyLogs",digest(j.queryForList("SELECT * FROM study_logs WHERE subject_id<>?",f.subjectA)));return m;}
 public static Wire install(ConfigurableApplicationContext ctx){var wire=new Wire();wire.reservationCheck=feature->{int n=ctx.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM ai_generation_requests WHERE feature=? AND request_state='DISPATCH_RESERVED' AND dispatch_reserved=TRUE",Integer.class,feature);if(n!=1)throw new IllegalStateException("Committed dispatch reservation required before FakeWire");};ReflectionTestUtils.setField(ctx.getBean(LlmCoachingController.class),"httpClient",wire);ReflectionTestUtils.setField(ctx.getBean(GeminiStudySummaryController.class),"httpClient",new Wire(true,wire));return wire;}
 public static final class Wire extends HttpClient {
  final AtomicInteger daily,summary;public final AtomicBoolean timeoutNext;final boolean isSummary;java.util.function.Consumer<String> reservationCheck=f->{};
  public Wire(){daily=new AtomicInteger();summary=new AtomicInteger();timeoutNext=new AtomicBoolean();isSummary=false;}
  Wire(boolean summary,Wire owner){daily=owner.daily;this.summary=owner.summary;timeoutNext=owner.timeoutNext;isSummary=summary;reservationCheck=owner.reservationCheck;}
  public Map<String,Integer> counts(){return Map.of("DAILY_COACHING",daily.get(),"STUDY_SUMMARY",summary.get(),"realAiCalls",0);}
  @Override @SuppressWarnings("unchecked") public <T>HttpResponse<T> send(HttpRequest q,HttpResponse.BodyHandler<T> h)throws IOException{
   if(!q.method().equals("POST")||!q.uri().getHost().equals("generativelanguage.googleapis.com")||!q.uri().getPath().contains(DualLedgerProfile.MODEL))throw new IOException("Unapproved fake wire request");
   // Feature is fixed at installation, not inferred from user text. No socket/SSL/DNS API is used.
   var payload=IntegrationRuntime.JSON.readTree(body(q));if(payload.at("/generationConfig/maxOutputTokens").asInt()!=100)throw new IOException("Unapproved fake token contract");reservationCheck.accept(isSummary?"STUDY_SUMMARY":"DAILY_COACHING");var counter=isSummary?summary:daily;int n=counter.incrementAndGet();if(n>(isSummary?2:4))throw new IOException("Fake-only run budget consumed");
   if(timeoutNext.getAndSet(false))throw new HttpTimeoutException("Synthetic dispatch timeout; no network");
   String json="{\"modelVersion\":\""+DualLedgerProfile.MODEL+"\",\"usageMetadata\":{\"promptTokenCount\":11,\"candidatesTokenCount\":7,\"totalTokenCount\":18},\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"[격리 Mock · 실제 AI 아님] 두 원장 가상 검증 응답입니다. FINAL은 변경하지 않습니다.\"}]}}]}";
   return new HttpResponse<T>(){public int statusCode(){return 200;}public HttpRequest request(){return q;}public Optional<HttpResponse<T>>previousResponse(){return Optional.empty();}public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}public T body(){return (T)json;}public Optional<SSLSession>sslSession(){return Optional.empty();}public URI uri(){return q.uri();}public Version version(){return Version.HTTP_1_1;}};
  }
  static String body(HttpRequest q)throws IOException{var out=new ByteArrayOutputStream();var done=new CompletableFuture<String>();q.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<java.nio.ByteBuffer>(){public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(java.nio.ByteBuffer b){byte[] a=new byte[b.remaining()];b.get(a);out.writeBytes(a);}public void onError(Throwable e){done.completeExceptionally(e);}public void onComplete(){done.complete(out.toString(StandardCharsets.UTF_8));}});try{return done.get(2,TimeUnit.SECONDS);}catch(Exception e){throw new IOException("Fake payload unavailable");}}
  public <T>CompletableFuture<HttpResponse<T>>sendAsync(HttpRequest r,HttpResponse.BodyHandler<T>h){return CompletableFuture.failedFuture(new IOException("Async forbidden"));}public <T>CompletableFuture<HttpResponse<T>>sendAsync(HttpRequest r,HttpResponse.BodyHandler<T>h,HttpResponse.PushPromiseHandler<T>p){return sendAsync(r,h);}
  public Optional<CookieHandler>cookieHandler(){return Optional.empty();}public Optional<Duration>connectTimeout(){return Optional.empty();}public Redirect followRedirects(){return Redirect.NEVER;}public Optional<ProxySelector>proxy(){return Optional.empty();}public SSLContext sslContext(){throw new UnsupportedOperationException();}public SSLParameters sslParameters(){return new SSLParameters();}public Optional<Authenticator>authenticator(){return Optional.empty();}public Version version(){return Version.HTTP_1_1;}public Optional<Executor>executor(){return Optional.empty();}
 }
 public static final class Gate implements Filter {
  final JdbcTemplate j;final TransactionTemplate tx;final String id;final boolean recovery;volatile Fixture fixture;boolean closing;int active;
  public Gate(ConfigurableApplicationContext c,String id,boolean recovery){j=c.getBean(JdbcTemplate.class);tx=new TransactionTemplate(c.getBean(PlatformTransactionManager.class));this.id=IntegrationSchema.id(id);this.recovery=recovery;}
  public void open(Fixture f){if(fixture!=null)throw new IllegalStateException();fixture=f;}
  public synchronized void quiesce(long timeout)throws Exception{closing=true;long end=System.currentTimeMillis()+timeout;while(active>0&&System.currentTimeMillis()<end)wait(20);if(active>0)throw new IOException("Requests still active");}
  public void doFilter(ServletRequest q,ServletResponse r,FilterChain c)throws IOException,ServletException{synchronized(this){if(closing){IntegrationRuntime.Gate.reply((HttpServletResponse)r,503,"종료 중입니다.");return;}active++;}try{handle((HttpServletRequest)q,(HttpServletResponse)r,c);}finally{synchronized(this){active--;notifyAll();}}}
  void handle(HttpServletRequest q,HttpServletResponse r,FilterChain chain)throws IOException,ServletException{
   String p=q.getRequestURI(),m=q.getMethod();if(m.equals("GET")&&Set.of("/auth/csrf","/auth/me","/login.html","/login.js","/auth-client.js").contains(p)){chain.doFilter(q,r);return;}if(m.equals("POST")&&Set.of("/auth/login","/auth/logout").contains(p)){chain.doFilter(q,r);return;}
   if(fixture==null){IntegrationRuntime.Gate.reply(r,503,"가상 fixture 준비 중");return;}
   if(q.getUserPrincipal()==null||!IntegrationSchema.OWNER.equals(q.getUserPrincipal().getName())){IntegrationRuntime.Gate.reply(r,401,"새 가상 계정 로그인이 필요합니다.");return;}
   boolean read=m.equals("GET")&&(Set.of("/goals","/subjects","/study-logs","/analysis-summary","/study-plan","/study-reschedule-preview","/study-reschedule-saved","/study-reschedule-calendar","/ai-responses","/llm-config-check","/ai-generation-policy","/ai-generation-requests","/study-log-idempotency","/study-log-requests").contains(p)||(CalendarReadOnlySession.ASSETS.contains(p.substring(1))||Set.of("/study-log-form.html","/study-log-requests.js").contains(p)));
   if(read){q.getSession().setAttribute("dual.run-id",id);if(Set.of("/ai-responses","/ai-generation-requests","/ai-generation-policy","/llm-config-check").contains(p))tagged(q,r,chain);else chain.doFilter(q,r);return;}
   boolean write=m.equals("POST")&&Set.of("/study-logs","/llm-coaching","/study-log-summary").contains(p);
   if(!write||recovery){IntegrationRuntime.Gate.reply(r,403,"두 원장 전용 모드: 다른 저장·질문·주간 생성은 차단됩니다.");return;}
   String header=q.getHeader("X-Integration-Run");if(header!=null?!id.equals(header):!id.equals(q.getSession().getAttribute("dual.run-id"))){IntegrationRuntime.Gate.reply(r,403,"실행 UUID 또는 인증 후 조회 세션 바인딩이 필요합니다.");return;}
   if(!LocalDate.now(ZoneId.of("Asia/Seoul")).equals(fixture.day)){IntegrationRuntime.Gate.reply(r,409,"DUAL_TEST_DATE_EXPIRED: 쓰지 말고 정상 종료하세요.");return;}
   if(!Long.toString(fixture.goalA).equals(q.getParameter("goalId"))||q.getParameterValues("goalId").length!=1){IntegrationRuntime.Gate.reply(r,403,"새 쓰기는 가상 목표 A만 허용합니다.");return;}
   String key=q.getHeader("Idempotency-Key");if(key==null||!key.matches("[a-z0-9][a-z0-9_-]{15,79}")){IntegrationRuntime.Gate.reply(r,400,"요청 키를 확인하세요.");return;}
   byte[] body=q.getInputStream().readNBytes(8193);if(body.length>8192){IntegrationRuntime.Gate.reply(r,400,"입력 한도 초과");return;}var payload=IntegrationRuntime.JSON.readTree(body);
   if(p.equals("/study-logs")&&(payload.hasNonNull("logId")||payload.path("subjectId").asLong()!=fixture.subjectA||!payload.path("studyDate").asText().equals(fixture.day.minusDays(1).toString()))){IntegrationRuntime.Gate.reply(r,403,"가상 과목 A의 새 기록과 승인 날짜만 허용합니다.");return;}
   String kind=p.equals("/study-logs")?"DUAL_STUDY_SLOT":p.equals("/llm-coaching")?"DUAL_DAILY_SLOT":"DUAL_SUMMARY_SLOT";int max=p.equals("/study-log-summary")?2:4;String slot=review.mobile.GeminiShutdown.sha((p+"|"+key).getBytes(StandardCharsets.UTF_8));
   boolean admitted=tx.execute(s->{j.queryForObject("SELECT run_id FROM integration_run_state WHERE run_id=? FOR UPDATE",String.class,id);if(j.queryForObject("SELECT COUNT(*) FROM integration_requests WHERE run_id=? AND request_key=?",Integer.class,id,slot)>0)return true;if(j.queryForObject("SELECT COUNT(*) FROM integration_requests WHERE run_id=? AND state=?",Integer.class,id,kind)>=max)return false;j.update("INSERT INTO integration_requests(run_id,request_key,request_hash,state) VALUES(?,?,?,?)",id,slot,review.mobile.GeminiShutdown.sha(body),kind);return true;});
   if(!admitted){IntegrationRuntime.Gate.reply(r,429,"이 승인 프로파일의 가상 요청 슬롯 한도입니다.");return;}
   if(p.equals("/study-logs"))chain.doFilter(IntegrationRuntime.Gate.replay(q,body),r);else tagged(IntegrationRuntime.Gate.replay(q,body),r,chain);
  }
  static void tag(com.fasterxml.jackson.databind.JsonNode node){if(node.isArray()){node.forEach(Gate::tag);return;}if(!node.isObject())return;var o=(com.fasterxml.jackson.databind.node.ObjectNode)node;o.put("testMode",MARKER).put("mockResponse",true).put("storageMode",DualLedgerProfile.MODE).put("realAiCalls",0);if(o.has("result"))tag(o.get("result"));}
  static void tagged(HttpServletRequest q,HttpServletResponse r,FilterChain chain)throws IOException,ServletException{var cached=new org.springframework.web.util.ContentCachingResponseWrapper(r);chain.doFilter(q,cached);if(cached.getContentType()!=null&&cached.getContentType().contains("json")&&cached.getContentAsByteArray().length>0){var node=IntegrationRuntime.JSON.readTree(cached.getContentAsByteArray());tag(node);byte[] bytes=IntegrationRuntime.JSON.writeValueAsBytes(node);cached.resetBuffer();cached.setContentLength(bytes.length);cached.getOutputStream().write(bytes);}cached.copyBodyToResponse();}
 }
}
