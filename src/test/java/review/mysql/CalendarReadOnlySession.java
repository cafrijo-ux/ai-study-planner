package review.mysql;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** New-schema fixture writes require separate approval; public validation is GET-only. */
public final class CalendarReadOnlySession {
 public static final String MODE="CALENDAR_READONLY";
 public static final Set<String> ASSETS=Set.of("login.html","login.js","auth-client.js","dashboard.html","goal-context.js",
  "study-plan-calendar.js","study-plan-calendar.css","study-analysis-charts.js","gpt-weekly-cards.js","review-priority.js","ai-response-history.js","study-log-history.html");
 final JdbcTemplate jdbc; final IntegrationScenario.Fixture fixture; final Clock clock;
 final LocalDate today,studyDate,planDate; final Instant preparedAt;
 final Map<String,Object> baseline,contract;
 final AtomicInteger monthReadsA=new AtomicInteger(),monthReadsB=new AtomicInteger();
 public static String scenario(String phase,String value){
  if(!Set.of("ANDROID_WRITE",MODE).contains(value)||(!phase.equals("HTTP")&&!value.equals("ANDROID_WRITE")))throw new IllegalArgumentException("Unapproved HTTP scenario");
  return value;
 }
 public static CalendarReadOnlySession initialize(ConfigurableApplicationContext ctx,String id,String password)throws Exception{
  var api=new IntegrationScenario(ctx,id);var before=LocalDate.now(ZoneId.of("Asia/Seoul"));var f=api.initialize(password);
  api.postOk("/study-logs",f.goalA(),IntegrationScenario.log(f.subjectA()),IntegrationScenario.key());
  var saved=api.mvc.perform(post("/study-reschedule-save").session(api.session).header("X-CSRF-TOKEN",api.token)
   .header("X-Integration-Run",id).header("Idempotency-Key",IntegrationScenario.key()).queryParam("goalId",""+f.goalA())).andReturn();
  IntegrationScenario.require(saved.getResponse().getStatus()==200,"CALENDAR_FIXTURE_SAVE");
  var stored=api.getOk("/study-reschedule-saved",f.goalA(),Map.of("date",before.plusDays(1).toString()));
  IntegrationScenario.require(stored.path("saved").asBoolean()&&stored.path("planStatus").asText().equals("FINAL"),"CALENDAR_FIXTURE_FINAL");
  // Regression assertions only: clients always display the actual server response.
  IntegrationScenario.require(stored.path("studyMinutes").asInt()==200&&stored.path("carryoverMinutes").asInt()==9
   &&stored.path("newStudyMinutes").asInt()==191&&stored.path("carryoverTotalPendingMinutes").asInt()==60,"CALENDAR_FIXTURE_RULES");
  if(!before.equals(LocalDate.now(ZoneId.of("Asia/Seoul"))))throw new IllegalStateException("CALENDAR_FIXTURE_DATE_CHANGED");
  var result=new CalendarReadOnlySession(ctx.getBean(JdbcTemplate.class),f,Clock.systemUTC(),IntegrationRuntime.JSON.convertValue(stored,Map.class));
  if(!before.equals(result.today))throw new IllegalStateException("CALENDAR_FIXTURE_DATE_CHANGED");return result;
 }
 CalendarReadOnlySession(JdbcTemplate j,IntegrationScenario.Fixture f,Clock c,Map<String,Object> plan){
  jdbc=j;fixture=f;clock=c.withZone(ZoneId.of("Asia/Seoul"));preparedAt=clock.instant();today=LocalDate.now(clock);studyDate=today.minusDays(1);planDate=today.plusDays(1);
  contract=Collections.unmodifiableMap(new LinkedHashMap<>(plan));baseline=IntegrationRuntime.snapshot(j);
 }
 public Map<String,Object> ready(){
  var r=new LinkedHashMap<String,Object>();r.put("httpScenario",MODE);r.put("androidMode",MODE);r.put("testDate",today.toString());r.put("studyDate",studyDate.toString());r.put("planDate",planDate.toString());r.put("preparedAtUtc",preparedAt.toString());
  r.put("initialStudyLogs",1);r.put("maxStudyWrites",0);r.put("maxPlanWrites",0);r.put("calendarMonth",YearMonth.from(planDate).toString());r.put("publicBusinessWritesAllowed",0);r.put("fixturePlan",contract);return r;
 }
 public void observed(String goal){if(Long.toString(fixture.goalA()).equals(goal))monthReadsA.incrementAndGet();else if(Long.toString(fixture.goalB()).equals(goal))monthReadsB.incrementAndGet();}
 public Map<String,Object> verify(){
  var r=new LinkedHashMap<String,Object>();r.put("mode",MODE);r.put("protectedFixturesPreserved",baseline.equals(IntegrationRuntime.snapshot(jdbc)));
  r.put("dateValid",today.equals(LocalDate.now(clock)));r.put("testDate",today.toString());r.put("currentSeoulDate",LocalDate.now(clock).toString());r.put("realAiCalls",0);
  r.put("goalA",fixture.goalA());r.put("goalB",fixture.goalB());r.put("monthReadsA",monthReadsA.get());r.put("monthReadsB",monthReadsB.get());
  r.put("clientComparisonVerified",false); // GET counts do not prove physical clients or rendered values.
  r.put("baseline",baseline);r.put("fixturePlan",contract);return r;
 }
}
