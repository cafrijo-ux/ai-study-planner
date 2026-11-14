package ai_study_planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** No Spring context, DataSource or live provider. Every HTTP transport and repository is mocked. */
class AiIntegrationTests {
    final LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
    final ObjectMapper json=new ObjectMapper();
    GoalRepository goals;SubjectRepository subjects;StudyLogRepository logs;StudySavedPlanService plans;
    HttpClient http;GptWeeklyCoachingController gpt;
    Goal goal;Subject os,network;
    @BeforeEach void setup(){
        goals=mock(GoalRepository.class);subjects=mock(SubjectRepository.class);logs=mock(StudyLogRepository.class);plans=mock(StudySavedPlanService.class);http=mock(HttpClient.class);
        goal=GoalWorkflowTests.goal(1);goal.setGoalName("가상 시험");goal.setWeakPoint("CPU 스케줄링");goal.setCreatedAt(today.minusDays(40).atStartOfDay());
        os=subject(10,"운영체제");network=subject(20,"네트워크");
        when(goals.findAll()).thenReturn(List.of(goal));when(subjects.findAll()).thenReturn(List.of(os,network));
        when(logs.findAll()).thenReturn(List.of(log(1,10,today.minusDays(6),20,2,3),log(2,10,today,40,4,5),log(3,20,today,60,3,4)));
        gpt=new GptWeeklyCoachingController(goals,subjects,logs,plans);set(gpt,"model","gpt-4o-mini");set(gpt,"apiKey","synthetic-test-key");set(gpt,"enabled",true);set(gpt,"httpClient",http);
        var request=new MockHttpServletRequest();request.setParameter("goalId","1");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @AfterEach void noWrites(){
        RequestContextHolder.resetRequestAttributes();Thread.interrupted();
        verify(goals,never()).save(any());verify(subjects,never()).save(any());verify(logs,never()).save(any());
        assertTrue(mockingDetails(plans).getInvocations().stream().noneMatch(i->i.getMethod().getName().equals("save")),"Coaching must never save FINAL");
    }
    static void set(Object o,String name,Object value){ReflectionTestUtils.setField(o,name,value);}
    Subject subject(long id,String name){Subject s=GoalWorkflowTests.subject();s.setSubjectId(id);s.setGoalId(1L);s.setUserId(1L);s.setSubjectName(name);return s;}
    StudyLog log(long id,long subject,LocalDate day,Integer minutes,Integer understanding,Integer focus){
        StudyLog l=new StudyLog();l.setLogId(id);l.setUserId(1L);l.setSubjectId(subject);l.setStudyDate(day);l.setStudyMinutes(minutes);l.setUnderstandingScore(understanding);l.setFocusScore(focus);l.setDifficultTopic("CPU 스케줄링");return l;
    }
    Map<String,Object> plan(int minutes){return new LinkedHashMap<>(Map.of("studyMinutes",minutes,"carryoverMinutes",9,"newStudyMinutes",minutes-9,"isScheduleValid",true,"hasConflict",false,"scheduleDate",today.toString()));}
    StudySavedPlanService.SavedPlan saved(String status,Map<String,Object> data){return new StudySavedPlanService.SavedPlan(data,today.minusDays(1),LocalTime.of(20,0),status,"10:00","12:00","","",today.toString());}
    @SuppressWarnings("unchecked") List<Map<String,Object>> maps(Object o){return (List<Map<String,Object>>)o;}
    @SuppressWarnings("unchecked") Map<String,Object> map(Object o){return (Map<String,Object>)o;}
    void respond(int status,String body)throws Exception{
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
    @Test void legacyCoachingAndSummaryGetRepeatWithoutLedgerDemonstratesRisk()throws Exception{
        respond(200,GEMINI_REPLY);var coaching=new LlmCoachingController(goals,subjects,logs,plans);set(coaching,"provider","gemini");set(coaching,"model","gemini-3.5-flash-lite");set(coaching,"geminiApiKey","synthetic-test-key");set(coaching,"demoMode",false);set(coaching,"httpClient",http);
        coaching.getLlmCoaching();coaching.getLlmCoaching();var summary=summary();summary.summarizeStudyLogs();summary.summarizeStudyLogs();verify(http,times(4)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    String body(HttpRequest request)throws Exception{
        var output=new java.io.ByteArrayOutputStream();var done=new CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);} public void onNext(ByteBuffer b){byte[] bytes=new byte[b.remaining()];b.get(bytes);output.writeBytes(bytes);}
            public void onError(Throwable t){done.completeExceptionally(t);}public void onComplete(){done.complete(output.toString(StandardCharsets.UTF_8));}
        });return done.get(2,TimeUnit.SECONDS);
    }
    HttpRequest sent()throws Exception{var captor=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http).send(captor.capture(),any());return captor.getValue();}
    static final String GPT_REPLY="""
        {"status":"completed","model":"gpt-4o-mini-2024-07-18","output":[{"type":"message","content":[{"type":"output_text","text":"[Mock] 기록이 있는 날만 분석했습니다. CPU 스케줄링을 복습하세요. 다음 주 권고이며 FINAL은 유지합니다."}]}],"usage":{"input_tokens":500,"output_tokens":100,"total_tokens":600}}
        """;
    static final String GEMINI_REPLY="""
        {"modelVersion":"gemini-3.5-flash-lite","candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"[Mock] CPU 스케줄링 예제를 그림으로 정리하세요. 이해도 2점에 맞춘 다음 활동 권고입니다."}]}}],"usageMetadata":{"promptTokenCount":300,"candidatesTokenCount":80,"totalTokenCount":380}}
        """;
    @Test void previewIncludesDailySubjectTrendAndNeverCallsProvider() throws Exception {
        when(plans.findFinalPlans(eq(1L),any(),any())).thenReturn(Map.of(today.minusDays(6),plan(80),today,plan(100)));
        var result=gpt.preview();assertEquals(120,result.get("totalStudyMinutes"));assertEquals(3,result.get("recordCount"));assertEquals(7,maps(result.get("dailyMetrics")).size());
        assertEquals(60,maps(result.get("subjectMetrics")).get(0).get("studyMinutes"));assertEquals(60,maps(result.get("subjectMetrics")).get(1).get("studyMinutes"));
        assertEquals(1.5,map(result.get("focusTrend")).get("delta"));assertEquals(1.5,map(result.get("understandingTrend")).get("delta"));
        assertEquals(180,map(result.get("weeklyPlanComparison")).get("comparablePlannedMinutes"));assertEquals(120,map(result.get("weeklyPlanComparison")).get("comparableActualMinutes"));
        assertTrue(result.get("prompt").toString().contains("CPU 스케줄링"));assertTrue(result.get("prompt").toString().contains("다음 주"));assertEquals("preview",result.get("responseSource"));verifyNoInteractions(http);
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/ai-evidence"));java.nio.file.Files.writeString(java.nio.file.Path.of("build/ai-evidence/weekly-preview.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
    @Test void weekFiltersOldFutureForeignGoalAndForeignUser(){
        var input=new ArrayList<>(logs.findAll());input.add(log(4,10,today.minusDays(7),999,1,1));input.add(log(5,10,today.plusDays(1),999,1,1));input.add(log(6,99,today,999,1,1));var foreign=log(7,10,today,999,1,1);foreign.setUserId(2L);input.add(foreign);when(logs.findAll()).thenReturn(input);
        assertEquals(120,gpt.preview().get("totalStudyMinutes"));
    }
    @Test void explicitGoalAndFallbackRejectAnotherUsersLatestGoal(){
        RequestContextHolder.resetRequestAttributes();Goal other=GoalWorkflowTests.goal(99);other.setUserId(2L);when(goals.findAll()).thenReturn(List.of(goal,other));assertEquals(1L,gpt.preview().get("goalId"));
    }
    @Test void recordsBeforeGoalCreationAreExcluded(){goal.setCreatedAt(today.atStartOfDay());assertEquals(100,gpt.preview().get("totalStudyMinutes"));}
    @Test void multipleRecordsOnSameDayAreAddedWithoutInventingExtraDays(){assertEquals(2,gpt.preview().get("recordedDays"));assertEquals(100,maps(gpt.preview().get("dailyMetrics")).get(6).get("studyMinutes"));}
    @Test void missingSubjectAndMissingDayAreUnknownNotZero(){
        when(logs.findAll()).thenReturn(List.of(log(1,10,today,0,null,null)));
        when(plans.findFinalPlans(eq(1L),any(),any())).thenReturn(Map.of(today.minusDays(1),plan(100)));
        var r=gpt.preview();assertEquals(0,maps(r.get("subjectMetrics")).get(0).get("studyMinutes"));assertNull(maps(r.get("subjectMetrics")).get(1).get("studyMinutes"));
        assertNull(maps(r.get("dailyMetrics")).get(5).get("studyMinutes"));assertNull(map(r.get("focusTrend")).get("delta"));assertNull(map(r.get("weeklyPlanComparison")).get("comparableActualMinutes"));
    }
    @Test void absentTimeIsNotComparableEvenIfScoreExists(){
        when(logs.findAll()).thenReturn(List.of(log(1,10,today,null,2,3)));when(plans.findFinalPlans(eq(1L),any(),any())).thenReturn(Map.of(today,plan(100)));
        assertEquals(0,map(gpt.preview().get("weeklyPlanComparison")).get("comparableDays"));
    }
    @Test void invalidScoresNotInterpretedAsMeasuredValues(){when(logs.findAll()).thenReturn(List.of(log(1,10,today,40,0,8)));var r=gpt.preview();assertNull(r.get("averageUnderstanding"));assertNull(r.get("averageFocus"));}
    @Test void noLogsWarnsAboutInsufficientEvidence(){when(logs.findAll()).thenReturn(List.of());var r=gpt.preview();assertNull(r.get("averageFocus"));assertTrue(r.get("prompt").toString().contains("신뢰도가 낮"));}
    @Test void tomorrowFinalPreferredOverTodayDraftWithoutChangingEither(){
        var current=plan(100);var tomorrow=plan(120);when(plans.find(1L,today)).thenReturn(saved("DRAFT",current));when(plans.find(1L,today.plusDays(1))).thenReturn(saved("FINAL",tomorrow));
        var copy=new LinkedHashMap<>(tomorrow);var r=gpt.preview();assertEquals("FINAL",r.get("savedPlanStatus"));assertEquals(120,r.get("savedPlanStudyMinutes"));assertEquals(copy,tomorrow);
    }
    @Test void oldFinalMissingFieldsRemainUnknown(){when(plans.find(1L,today)).thenReturn(saved("FINAL",new LinkedHashMap<>()));var r=gpt.preview();assertNull(r.get("savedPlanStudyMinutes"));assertNull(map(r.get("savedPlanEvidence")).get("carryoverTotalPendingMinutes"));assertEquals(false,r.get("savedPlanUsable"));}
    @Test void malformedSavedJsonReportedWithoutReplacement(){when(plans.find(1L,today)).thenThrow(new IllegalStateException("synthetic-secret"));var r=gpt.preview();assertTrue(r.containsKey("savedPlanReadWarning"));assertFalse(r.toString().contains("synthetic-secret"));}
    @Test void snapshotSeparatesTotalDebtAndAssignedCatchup(){var p=plan(100);p.put("carryoverTotalPendingMinutes",60);p.put("carryoverUnattributedMinutes",30);when(plans.find(1L,today)).thenReturn(saved("FINAL",p));var r=gpt.preview();assertEquals(9,r.get("savedPlanCarryoverMinutes"));assertEquals(60,map(r.get("savedPlanEvidence")).get("carryoverTotalPendingMinutes"));}
    @Test void emptyGoalStopsBeforeProvider(){when(subjects.findAll()).thenReturn(List.of());assertEquals("학습 데이터 부족",gpt.generate(Map.of("confirmPaidCall",true)).get("apiStatus"));verifyNoInteractions(http);}
    @Test void explicitPaidConfirmationRequired(){gpt.generate(Map.of("confirmPaidCall",false));verifyNoInteractions(http);}
    @Test void disabledGptNeverCallsProvider(){set(gpt,"enabled",false);assertEquals("GPT API 비활성화",gpt.generate(Map.of("confirmPaidCall",true)).get("apiStatus"));verifyNoInteractions(http);}
    @Test void missingKeyNeverCallsProvider(){set(gpt,"apiKey","none");assertEquals("OpenAI API 키 없음",gpt.generate(Map.of("confirmPaidCall",true)).get("apiStatus"));verifyNoInteractions(http);}
    @Test void gptMockUsesResponsesModelBudgetAndUsageWithoutPersisting()throws Exception{
        respond(200,GPT_REPLY);var r=gpt.generate(Map.of("confirmPaidCall",true));var request=sent();var payload=json.readTree(body(request));
        assertEquals("https://api.openai.com/v1/responses",request.uri().toString());assertEquals("gpt-4o-mini",payload.path("model").asText());assertEquals(900,payload.path("max_output_tokens").asInt());assertFalse(payload.path("store").asBoolean());
        assertEquals("provider",r.get("responseSource"));assertEquals(600L,map(r.get("usage")).get("total_tokens"));assertTrue(r.get("llmResponse").toString().startsWith("[Mock]"));assertEquals(Duration.ofSeconds(45),request.timeout().orElseThrow());
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,404,429,500,503}) void gptHttpFailuresAreSafeAndKorean(int status)throws Exception{
        respond(status,"<html>synthetic-secret</html>");var r=gpt.generate(Map.of("confirmPaidCall",true));assertEquals(status,r.get("statusCode"));assertFalse(r.toString().contains("synthetic-secret"));assertTrue(r.get("message").toString().contains("확인")||r.get("message").toString().contains("다시")||status==404);
    }
    @ParameterizedTest @ValueSource(strings={"", "not-json", "null", "{}", "{\"output\":[]}"}) void gptBadOrEmptyResponseIsNotSuccess(String body)throws Exception{respond(200,body);assertFalse(gpt.generate(Map.of("confirmPaidCall",true)).containsKey("llmResponse"));}
    @Test void truncatedGptResponseIsMarkedPartial()throws Exception{respond(200,GPT_REPLY.replace("completed","incomplete"));var r=gpt.generate(Map.of("confirmPaidCall",true));assertEquals(false,r.get("responseComplete"));assertEquals("GPT 응답 일부 수신",r.get("apiStatus"));}
    @ParameterizedTest @ValueSource(strings={"timeout","network","interrupted"}) void gptTransportFailureDoesNotLeak(String kind)throws Exception{
        Exception e=switch(kind){case "timeout"->new HttpTimeoutException("synthetic-secret");case "interrupted"->new InterruptedException("synthetic-secret");default->new java.io.IOException("synthetic-secret");};
        when(http.send(any(),any())).thenThrow(e);var r=gpt.generate(Map.of("confirmPaidCall",true));assertFalse(r.toString().contains("synthetic-secret"));if(kind.equals("interrupted"))assertTrue(Thread.currentThread().isInterrupted());
    }
    @Test void repeatedRequestDoesNotCauseSecondProviderCall()throws Exception{respond(200,GPT_REPLY);gpt.generate(Map.of("confirmPaidCall",true));assertEquals("잠시 대기",gpt.generate(Map.of("confirmPaidCall",true)).get("apiStatus"));verify(http,times(1)).send(any(),any());}
    @Test void inFlightGuardDoesNotCauseDuplicateCall(){set(gpt,"inFlight",new java.util.concurrent.atomic.AtomicBoolean(true));assertEquals("요청 진행 중",gpt.generate(Map.of("confirmPaidCall",true)).get("apiStatus"));verifyNoInteractions(http);}
    GeminiStudySummaryController summary(){var c=new GeminiStudySummaryController(logs,subjects,goals);set(c,"geminiApiKey","synthetic-test-key");set(c,"geminiModel","gemini-3.5-flash-lite");set(c,"httpClient",http);return c;}
    @Test void geminiSummaryMockCarriesRealFixtureDetailsAndDistinctRole()throws Exception{
        respond(200,GEMINI_REPLY);var r=summary().summarizeStudyLogs();var request=sent();var payload=json.readTree(body(request));var prompt=payload.path("contents").get(0).path("parts").get(0).path("text").asText();
        assertTrue(prompt.contains("CPU 스케줄링"));assertTrue(prompt.contains("120분"));assertTrue(prompt.contains("이해도: 2/5"));assertTrue(prompt.contains("집중도: 3/5"));assertTrue(prompt.contains("학습 기록 요약"));assertFalse(prompt.contains("다음 주 학습 전략"));
        assertTrue(prompt.contains("기준 날짜(Asia/Seoul): " + today));
        assertTrue(prompt.contains("기록 없는 날짜는 미공부로 단정하지 말고"));
        assertEquals(1000,payload.path("generationConfig").path("maxOutputTokens").asInt());assertTrue(request.uri().toString().endsWith("gemini-3.5-flash-lite:generateContent"));assertEquals(380L,map(r.get("usage")).get("totalTokenCount"));assertEquals("provider",r.get("responseSource"));
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/ai-evidence"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/ai-evidence/gemini-synthetic-request.json"),payload.toPrettyString());
    }
    @Test void geminiSummaryNoKeyNoCall(){var c=summary();set(c,"geminiApiKey","none");assertEquals("Gemini API 키 미설정",c.summarizeStudyLogs().get("apiStatus"));verifyNoInteractions(http);}
    @Test void geminiSummaryNoRecordsNoCall(){when(logs.findAll()).thenReturn(List.of());summary().summarizeStudyLogs();verifyNoInteractions(http);}
    @ParameterizedTest @ValueSource(ints={0,1,6}) void geminiRelativeDatesDoNotInventMissingDayRecords(int daysAgo)throws Exception{
        LocalDate reference=LocalDate.of(2026,10,20), recorded=reference.minusDays(daysAgo);
        when(logs.findAll()).thenReturn(List.of(log(1,10,recorded,120,2,3)));
        var c=summary();set(c,"clock",Clock.fixed(Instant.parse("2026-10-20T03:00:00Z"),ZoneOffset.UTC));respond(200,GEMINI_REPLY);
        var r=c.summarizeStudyLogs();String prompt=body(sent());
        assertEquals("2026-10-20",r.get("referenceDate"));assertEquals(1,r.get("analyzedRecordCount"));
        assertTrue(prompt.contains("기준 날짜(Asia/Seoul): 2026-10-20"));assertTrue(prompt.contains("날짜: "+recorded));
        assertTrue(prompt.contains("기록 없는 날짜는 미공부로 단정하지 말고"));
        assertEquals(1,prompt.split("날짜: ",-1).length-1);
    }
    @Test void geminiExplicitZeroRecordsRemainDifferentFromMissingDays()throws Exception{
        LocalDate reference=LocalDate.of(2026,10,20);when(logs.findAll()).thenReturn(List.of(
                log(1,10,reference.minusDays(3),0,null,null),log(2,10,reference.minusDays(2),0,null,null),log(3,10,reference.minusDays(1),0,null,null)));
        var c=summary();set(c,"clock",Clock.fixed(Instant.parse("2026-10-20T03:00:00Z"),ZoneOffset.UTC));respond(200,GEMINI_REPLY);
        assertEquals(3,c.summarizeStudyLogs().get("analyzedRecordCount"));String prompt=body(sent());
        assertEquals(3,prompt.split(java.util.regex.Pattern.quote(" | 시간: 0분"),-1).length-1);assertFalse(prompt.contains("날짜: 2026-10-20"));
        assertTrue(prompt.contains("명시적으로 0분을 기록한 날과 기록 자체가 없는 날을 구분"));
    }
    @ParameterizedTest @ValueSource(strings={"2026-10-19T14:59:59Z","2026-10-19T15:00:00Z"}) void geminiSeoulMidnightBoundaryUsesKoreaNotHostZone(String instant)throws Exception{
        Instant time=Instant.parse(instant);LocalDate expected=time.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        when(logs.findAll()).thenReturn(List.of(log(1,10,LocalDate.of(2026,10,19),20,2,3),log(2,10,LocalDate.of(2026,10,20),40,3,4)));
        var c=summary();set(c,"clock",Clock.fixed(time,ZoneOffset.UTC));respond(200,GEMINI_REPLY);var r=c.summarizeStudyLogs();
        assertEquals(expected.toString(),r.get("referenceDate"));assertEquals("Asia/Seoul",r.get("referenceTimeZone"));
        assertEquals(expected.getDayOfMonth()==19?1:2,r.get("analyzedRecordCount"));assertTrue(body(sent()).contains("기준 날짜(Asia/Seoul): "+expected));
    }
    @Test void geminiFutureOnlyRecordsDoNotCallProvider(){
        when(logs.findAll()).thenReturn(List.of(log(1,10,LocalDate.of(2026,10,21),120,2,3)));
        var c=summary();set(c,"clock",Clock.fixed(Instant.parse("2026-10-20T03:00:00Z"),ZoneOffset.UTC));
        assertEquals(0,c.summarizeStudyLogs().get("analyzedRecordCount"));verifyNoInteractions(http);
    }
    @Test void geminiRequestCapturesDateOnlyOnceEvenAcrossMidnight()throws Exception{
        var reads=new java.util.concurrent.atomic.AtomicInteger();Clock crossing=new Clock(){
            public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return Instant.parse(reads.getAndIncrement()==0?"2026-10-19T14:59:59Z":"2026-10-19T15:00:00Z");}
        };
        when(logs.findAll()).thenReturn(List.of(log(1,10,LocalDate.of(2026,10,19),40,3,3),log(2,10,LocalDate.of(2026,10,20),60,4,4)));
        var c=summary();set(c,"clock",crossing);respond(200,GEMINI_REPLY);var r=c.summarizeStudyLogs();
        assertEquals(1,reads.get());assertEquals(1,r.get("analyzedRecordCount"));assertEquals("2026-10-19",r.get("referenceDate"));
        assertTrue(body(sent()).contains("기준 날짜(Asia/Seoul): 2026-10-19"));
    }
    @ParameterizedTest @ValueSource(strings={"timeout","network","interrupted"}) void geminiTransportFailureSafe(String kind)throws Exception{
        Exception e=switch(kind){case "timeout"->new HttpTimeoutException("synthetic-secret");case "interrupted"->new InterruptedException("synthetic-secret");default->new java.io.IOException("synthetic-secret");};
        when(http.send(any(),any())).thenThrow(e);var r=summary().summarizeStudyLogs();assertFalse(r.toString().contains("synthetic-secret"));assertFalse(r.containsKey("summary"));if(kind.equals("interrupted"))assertTrue(Thread.currentThread().isInterrupted());
    }
    @Test void dailyMissingScoresStayUnknownInPrompt(){when(logs.findAll()).thenReturn(List.of(log(1,10,today,40,null,null)));var c=daily();set(c,"demoMode",true);var r=c.getLlmCoaching();assertTrue(r.get("prompt").toString().contains("평균 이해도: 정보 없음"));assertFalse(r.get("prompt").toString().contains("평균 이해도: 0.0"));}
    @ParameterizedTest @ValueSource(ints={400,401,403,404,429,500,503}) void geminiSummaryHttpErrorsDoNotLeak(int status)throws Exception{respond(status,"not-json synthetic-secret");var r=summary().summarizeStudyLogs();assertEquals(status,r.get("statusCode"));assertFalse(r.toString().contains("synthetic-secret"));}
    @ParameterizedTest @ValueSource(strings={"", "not-json", "null", "{}"}) void geminiSummaryBadResponseDoesNotClaimSuccess(String text)throws Exception{respond(200,text);assertFalse(summary().summarizeStudyLogs().containsKey("summary"));}
    @Test void geminiSummaryUsesOnlyLatest15EligibleRecords()throws Exception{
        var records=new ArrayList<StudyLog>();for(int i=0;i<18;i++)records.add(log(i,10,today.minusDays(i),1,2,3));records.add(log(99,10,today.plusDays(1),999,1,1));records.add(log(100,99,today,999,1,1));when(logs.findAll()).thenReturn(records);respond(200,GEMINI_REPLY);assertEquals(15,summary().summarizeStudyLogs().get("analyzedRecordCount"));
    }
    LlmCoachingController daily(){var c=new LlmCoachingController(goals,subjects,logs,plans);set(c,"provider","gemini");set(c,"model","gemini-3.5-flash-lite");set(c,"geminiApiKey","synthetic-test-key");set(c,"demoMode",false);set(c,"httpClient",http);return c;}
    @Test void dailyGeminiMockUsesGoalAndSavedFinal()throws Exception{when(plans.find(1L,today)).thenReturn(saved("FINAL",plan(100)));respond(200,GEMINI_REPLY);var r=daily().getLlmCoaching();assertEquals(true,r.get("savedPlanReferenced"));assertEquals(1L,r.get("goalId"));assertTrue(body(sent()).contains("CPU 스케줄링"));assertEquals("provider",r.get("responseSource"));}
    @Test void dailyDemoDoesNotMixOtherGoalsOrClaimProvider(){when(logs.findAll()).thenReturn(List.of(log(9,99,today,999,1,1)));var c=daily();set(c,"demoMode",true);var r=c.getLlmCoaching();assertEquals("rule",r.get("responseSource"));assertTrue(r.get("llmResponse").toString().contains("충분하지"));verifyNoInteractions(http);}
    @Test void dailyMissingSubjectsNeverSpendsTokens(){when(subjects.findAll()).thenReturn(List.of());assertEquals("학습 데이터 부족",daily().getLlmCoaching().get("apiStatus"));verifyNoInteractions(http);}
    @Test void dailyGeminiHttpErrorSafe()throws Exception{respond(429,"synthetic-secret");assertFalse(daily().getLlmCoaching().toString().contains("synthetic-secret"));}
    @Test void questionMockUsesQuestionContextAndOnlyMockRepositorySave()throws Exception{
        var repository=mock(StudyQuestionRepository.class);when(repository.save(any())).thenAnswer(i->{StudyQuestion q=i.getArgument(0);q.setQuestionId(1L);return q;});
        var c=new StudyQuestionController(repository);set(c,"geminiApiKey","synthetic-test-key");set(c,"geminiModel","gemini-3.5-flash-lite");set(c,"httpClient",http);set(c,"geminiEnabled",true);respond(200,GEMINI_REPLY);
        var r=c.answerStudyQuestion(Map.of("subject","운영체제","question","CPU 스케줄링의 RR 예제를 설명해 주세요","situation","하루 120분, 집중도 3점","understanding","낮음 2점","confirmPaidCall",true));
        assertEquals("gemini",r.get("answerSource"));assertTrue(body(sent()).contains("CPU 스케줄링"));verify(repository).save(any());
    }
    @Test void questionFailureClearlyUsesRuleFallback()throws Exception{
        var repository=mock(StudyQuestionRepository.class);when(repository.save(any())).thenAnswer(i->i.getArgument(0));var c=new StudyQuestionController(repository);set(c,"geminiApiKey","synthetic-test-key");set(c,"geminiModel","gemini-3.5-flash-lite");set(c,"httpClient",http);set(c,"geminiEnabled",true);respond(401,"synthetic-secret");var r=c.answerStudyQuestion(Map.of("question","CPU 스케줄링","confirmPaidCall",true));assertEquals("rule",r.get("answerSource"));assertTrue(r.get("apiStatus").toString().contains("인증"));assertFalse(r.toString().contains("synthetic-secret"));
    }
    @Test void questionRequiresExplicitPaidCallConfirmationWithoutAnyProviderRequest(){
        var repository=mock(StudyQuestionRepository.class);
        var c=new StudyQuestionController(repository);
        set(c,"geminiEnabled",true);
        set(c,"geminiApiKey","synthetic-test-key");
        set(c,"geminiModel","gemini-3.5-flash-lite");
        set(c,"httpClient",http);
        var r=c.answerStudyQuestion(Map.of("provider","gemini","question","CPU 스케줄링"));
        assertEquals("API 호출 확인 필요",r.get("apiStatus"));
        assertEquals("not_called",r.get("responseSource"));
        verifyNoInteractions(http);
        verify(repository,never()).save(any());
    }
    @Test void questionGptDisabledByDefaultNeverContactsOpenAiOrSaves(){
        var repository=mock(StudyQuestionRepository.class);
        var c=new StudyQuestionController(repository);
        set(c,"gptModel","gpt-4o-mini");
        set(c,"gptApiKey","synthetic-test-key");
        set(c,"httpClient",http);
        // Spring @Value is not injected in this unit test. Default boolean=false is the safe state.
        var r=c.answerStudyQuestion(Map.of("provider","gpt","question","CPU 스케줄링","confirmPaidCall",true));
        assertEquals("GPT 질문 기능 비활성화",r.get("apiStatus"));
        assertEquals("not_called",r.get("responseSource"));
        verifyNoInteractions(http);
        verify(repository,never()).save(any());
    }
    @Test void configCheckRecognizesGeminiWithoutExposingKey(){var c=new LlmConfigController();set(c,"provider","gemini");set(c,"geminiApiKey","synthetic-test-key");set(c,"demoMode",false);var r=c.checkLlmConfig();assertEquals(true,r.get("ready"));assertFalse(r.toString().contains("synthetic-test-key"));}

    @Test
    void weeklyPromptSeparatesHistoricalFinalFromFuturePlan() {
        // 테스트용 과거 FINAL: 총 100분, 보충 9분, 신규 91분
        var historical = new java.util.LinkedHashMap<>(plan(100));
        historical.put("carryoverMinutes", 9);
        historical.put("newStudyMinutes", 91);

        // 어제의 FINAL만 존재한다고 가정
        when(plans.findFinalPlans(eq(1L), any(), any()))
                .thenReturn(java.util.Map.of(
                        today.minusDays(1), historical
                ));

        // 오늘과 내일은 저장된 계획 없음
        when(plans.find(1L, today)).thenReturn(null);
        when(plans.find(1L, today.plusDays(1))).thenReturn(null);

        var result = gpt.preview();
        String prompt = (String) result.get("prompt");

        // 오늘/내일 저장 계획과 과거 FINAL을 구분
        assertEquals(false, result.get("savedPlanReferenced"));

        assertTrue(prompt.contains(
                "오늘과 내일에 저장된 계획 없음"
        ));

        // 과거 FINAL의 실제 저장 값이 프롬프트에 포함되는지
        assertTrue(prompt.contains(
                "최근 7일의 저장된 FINAL 계획"
        ));
        assertTrue(prompt.contains("총 계획: 100분"));
        assertTrue(prompt.contains("보충 배정: 9분"));
        assertTrue(prompt.contains("신규 학습: 91분"));

        // 과거 계획을 미래의 확정 시간표로 취급하지 않도록 지시
        assertTrue(prompt.contains(
                "과거 FINAL 계획을 다음 학습일의 확정 시간표로 간주하지 마"
        ));
    }

    @Test
    void tomorrowFinalIsNotListedAsHistoricalEvidence() {
        var tomorrow = new LinkedHashMap<>(plan(100));
        tomorrow.put("scheduleDate", today.plusDays(1).toString());
        when(plans.findFinalPlans(eq(1L), any(), any()))
                .thenReturn(Map.of(today.plusDays(1), tomorrow));
        when(plans.find(1L, today.plusDays(1))).thenReturn(saved("FINAL", tomorrow));
        var result = gpt.preview();
        assertEquals(true, result.get("savedPlanReferenced"));
        assertEquals(today.plusDays(1).toString(), result.get("savedPlanDate"));
        assertFalse(result.get("prompt").toString().contains("[최근 7일의 저장된 FINAL 계획"));
        verifyNoInteractions(http);
    }
}
