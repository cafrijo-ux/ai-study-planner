package review.mobile;

import ai_study_planner.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.ContentCachingResponseWrapper;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.Permission;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit test entry. No provider key input, real Wire, paid approval or production configuration. */
@SuppressWarnings("removal")
public final class FakeGeminiShutdownServer {
    public static final String MODE="GEMINI_SHUTDOWN_FAKE_H2",MARKER="AI_MOCK_H2_ONLY",OWNER="gemini-shutdown-fake-owner";
    public static final String MODEL="gemini-shutdown-fake",QUESTION="프로세스와 스레드의 차이를 두 문장으로 설명해 주세요.";
    public static final String ANSWER="[격리 Mock · 실제 AI 아님] 프로세스는 독립된 실행 단위이고 스레드는 프로세스 안에서 실행됩니다.\n스레드는 자원을 공유합니다. \"가상 답변\" \\ 경로 · 한국어 😀";
    static final String SYNTHETIC="synthetic-not-an-api-key";
    public static final String BASE="gemini-fake-shutdown-approvals";

    public static Map<String,Object> properties(int port,String id,String hash){
        var p=IsolatedStudyLogServer.properties(port,id,hash);p.put("planner.auth.username",OWNER);return p;
    }
    public static final class NetworkGuard extends SecurityManager {
        private final int port;
        public NetworkGuard(int port){IsolatedStudyLogServer.validatePort(port);this.port=port;}
        public void checkPermission(Permission p){if(p.getName().equals("setSecurityManager"))throw new SecurityException("Guard cannot be removed");}
        public void checkPermission(Permission p,Object c){checkPermission(p);}
        public void checkConnect(String host,int p){throw new SecurityException("Fake Wire: ALL outbound/DNS forbidden");}
        public void checkConnect(String host,int p,Object c){checkConnect(host,p);}
        public void checkListen(int p){if(p!=port)throw new SecurityException("Protected port");}
        public void checkAccept(String host,int p){if(!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(host))throw new SecurityException("Loopback only");}
    }
    public static final class Ledger {
        final Path dir;final String id,approvalHash;final int port;
        public Ledger(Path directory)throws IOException{
            dir=directory.toAbsolutePath().normalize();var a=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("approval.json")));
            id=a.path("approvalId").asText();port=a.path("port").asInt();IsolatedStudyLogServer.validatePort(port);
            if(!UUID.fromString(id).toString().equals(id)||!dir.getFileName().toString().equals(id)||!dir.getParent().getFileName().toString().equals(BASE)||
               !MODE.equals(a.path("mode").asText())||!MODEL.equals(a.path("model").asText())||!QUESTION.equals(a.path("question").asText())||
               !a.path("fakeOnly").asBoolean()||a.path("maxFakeCalls").asInt()!=1||!a.has("maxRealCalls")||a.path("maxRealCalls").asInt()!=0)throw new IOException("Fake approval contract rejected");
            approvalHash=GeminiShutdown.sha(Files.readAllBytes(dir.resolve("approval.json")));
        }
        void unchanged()throws IOException{if(!approvalHash.equals(GeminiShutdown.sha(Files.readAllBytes(dir.resolve("approval.json")))))throw new IOException("Approval changed; no recovery/retry");}
        void assertUnused()throws IOException{unchanged();for(String n:List.of("start-reserved.json","process-claim.json","attempt.json","stopped.json","shutdown-error.json"))if(Files.exists(dir.resolve(n)))throw new IOException("Fake execution already reserved");}
        void claim(long pid)throws IOException{
            unchanged();var start=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("start-reserved.json")));
            if(!id.equals(start.path("runId").asText())||!MODE.equals(start.path("mode").asText())||!approvalHash.equalsIgnoreCase(start.path("approvalSha256").asText()))throw new IOException("Start reservation mismatch");
            if(Files.exists(dir.resolve("attempt.json"))||Files.exists(dir.resolve("stopped.json"))||Files.exists(dir.resolve("shutdown-error.json")))throw new IOException("Spent fake execution");
            GeminiShutdown.atomic(dir.resolve("process-claim.json"),Map.of("runId",id,"pid",pid,"mode",MODE,"approvalSha256",approvalHash),GeminiShutdown.NONE);
        }
        synchronized void reserve()throws IOException{
            unchanged();var claim=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("process-claim.json")));
            if(!id.equals(claim.path("runId").asText())||!MODE.equals(claim.path("mode").asText())||!approvalHash.equals(claim.path("approvalSha256").asText()))throw new IOException("Fake process claim mismatch");
            GeminiShutdown.atomic(dir.resolve("attempt.json"),Map.of("runId",id,"mode",MODE,"status","FAKE_RESERVED_NO_RETRY","fakeWireCalls",1,"realAiCalls",0,"approvalSha256",approvalHash),GeminiShutdown.NONE);
        }
        public boolean attempted(){return Files.exists(dir.resolve("attempt.json"));}
        void outcome()throws IOException{unchanged();GeminiShutdown.atomic(dir.resolve("outcome.json"),Map.of("runId",id,"mode",MODE,"status","SYNTHETIC_RESPONSE","fakeWireCalls",1,"realAiCalls",0),GeminiShutdown.NONE);}
    }
    public static final class FakeClient extends HttpClient {
        final Ledger ledger;
        public FakeClient(Ledger ledger){this.ledger=ledger;}
        @Override @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler)throws IOException{
            var u=request.uri();String body=GeminiOncePolicy.OneCallClient.readBody(request);
            var input=GeminiShutdown.validJson(body.getBytes(StandardCharsets.UTF_8));
            if(!"POST".equals(request.method())||!"https".equals(u.getScheme())||!"generativelanguage.googleapis.com".equals(u.getHost())||
               !u.getPath().equals("/v1beta/models/"+MODEL+":generateContent")||u.getRawQuery()!=null||u.getPort()!=-1||u.getUserInfo()!=null||u.getFragment()!=null||
               !body.contains(QUESTION)||input.size()!=2||input.path("generationConfig").path("maxOutputTokens").asInt()!=1000)throw new IOException("Fake transport contract mismatch");
            ledger.reserve(); // Persistent FAKE ledger; there is no DNS, URLConnection or real Wire.
            String reply=GeminiShutdown.JSON.writeValueAsString(Map.of("modelVersion",MODEL,"candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text",ANSWER)))))));
            ledger.outcome();
            return new HttpResponse<>(){
                public int statusCode(){return 200;}public HttpRequest request(){return request;}public T body(){return (T)reply;}
                public Optional<HttpResponse<T>> previousResponse(){return Optional.empty();}public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}
                public Optional<javax.net.ssl.SSLSession> sslSession(){return Optional.empty();}public URI uri(){return u;}public Version version(){return Version.HTTP_1_1;}
            };
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){return CompletableFuture.failedFuture(new IOException("No async/retry"));}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){return sendAsync(r,h);}
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();}public Optional<java.time.Duration> connectTimeout(){return Optional.empty();}public Redirect followRedirects(){return Redirect.NEVER;}public Optional<ProxySelector> proxy(){return Optional.empty();}
        public javax.net.ssl.SSLContext sslContext(){throw new UnsupportedOperationException();}public javax.net.ssl.SSLParameters sslParameters(){return new javax.net.ssl.SSLParameters();}public Optional<Authenticator> authenticator(){return Optional.empty();}public Version version(){return Version.HTTP_1_1;}public Optional<Executor> executor(){return Optional.empty();}
    }
    public static final class Gate implements Filter {
        IsolatedRescheduleFixture.Seed seed;Ledger ledger;final AtomicBoolean submitted=new AtomicBoolean();private boolean closing;private int active;
        synchronized boolean isClosing(){return closing;}
        public void open(IsolatedRescheduleFixture.Seed seed,Ledger ledger){if(this.seed!=null)throw new IllegalStateException();this.seed=seed;this.ledger=ledger;}
        public synchronized void quiesce(long millis)throws Exception{closing=true;long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(active>0){long left=end-System.nanoTime();if(left<=0)throw new IOException("Active fake requests");wait(Math.max(1,TimeUnit.NANOSECONDS.toMillis(left)));}}
        @Override public void doFilter(ServletRequest raw,ServletResponse out,FilterChain chain)throws IOException,ServletException{
            synchronized(this){if(closing){IsolatedStudyLogServer.Gate.error((HttpServletResponse)out,503,"격리 Mock 종료 중");return;}active++;}
            try{handle((HttpServletRequest)raw,(HttpServletResponse)out,chain);}finally{synchronized(this){active--;notifyAll();}}
        }
        void handle(HttpServletRequest q,HttpServletResponse r,FilterChain chain)throws IOException,ServletException{
            if(seed==null){IsolatedStudyLogServer.Gate.error(r,503,"격리 Mock 준비 중");return;}
            String path=q.getRequestURI(),method=q.getMethod();
            if(method.equals("GET")&&Set.of("/auth/csrf","/auth/me","/login.html","/login.js","/auth-client.js").contains(path)){chain.doFilter(q,r);return;}
            if(q.getUserPrincipal()==null||!OWNER.equals(q.getUserPrincipal().getName())){IsolatedStudyLogServer.Gate.error(r,401,"격리 Mock 전용 계정 필요");return;}
            boolean generate=method.equals("POST")&&path.equals("/study-question");
            boolean read=method.equals("GET")&&Set.of("/goals","/subjects","/analysis-summary","/study-logs","/study-plan","/study-reschedule-saved","/study-question/capabilities","/ai-responses","/study-questions").contains(path);
            if(!read&&!generate){IsolatedStudyLogServer.Gate.error(r,403,"이 시나리오는 질문 Fake Wire 1회만 허용합니다.");return;}
            // Existing Flutter joins user-scoped logs/questions with validated goal subjects/response IDs.
            if(!Set.of("/goals","/study-logs","/study-questions").contains(path)){
                String[] goals=q.getParameterValues("goalId");if(goals==null||goals.length!=1||!(goals[0].equals(""+seed.goalA())||goals[0].equals(""+seed.goalB()))){IsolatedStudyLogServer.Gate.error(r,400,"가상 목표 A/B를 지정하세요.");return;}
                if(generate&&!goals[0].equals(""+seed.goalA())){IsolatedStudyLogServer.Gate.error(r,403,"격리 생성은 목표 A만 허용됩니다.");return;}
            }
            if(generate){
                var body=PreparedGeminiServer.Gate.input(q);
                if(body==null||!body.isObject()||body.size()!=6||!QUESTION.equals(body.path("question").asText())||!"gemini".equals(body.path("provider").asText())||!"가상 운영체제".equals(body.path("subject").asText())||!"복습".equals(body.path("situation").asText())||!"보통".equals(body.path("understanding").asText())||!body.path("confirmPaidCall").isBoolean()||!body.path("confirmPaidCall").asBoolean()){
                    IsolatedStudyLogServer.Gate.error(r,400,"승인된 가상 질문·과목·상황·이해도·Gemini 및 명시적 확인이 필요합니다. 실제 AI 비용은 발생하지 않습니다.");return;}
                ledger.unchanged();if(ledger.attempted()||!submitted.compareAndSet(false,true)){IsolatedStudyLogServer.Gate.error(r,409,"Fake Wire 1회 한도 사용됨 · GET으로 확인하세요.");return;}
                q=PreparedGeminiServer.Gate.replay(q,GeminiShutdown.JSON.writeValueAsBytes(body));
            }
            var cached=new ContentCachingResponseWrapper(r);chain.doFilter(q,cached);
            if(cached.getContentType()!=null&&cached.getContentType().contains("application/json")&&cached.getContentAsByteArray().length>0){
                var tree=GeminiShutdown.JSON.readTree(cached.getContentAsByteArray());if(tree.isObject())mark(tree);else if(tree.isArray())for(var row:tree)if(row.isObject())mark(row);
                byte[] bytes=GeminiShutdown.JSON.writeValueAsBytes(tree);cached.resetBuffer();cached.setContentLength(bytes.length);cached.getOutputStream().write(bytes);
            }cached.copyBodyToResponse();
        }
        static void mark(com.fasterxml.jackson.databind.JsonNode node){var o=(com.fasterxml.jackson.databind.node.ObjectNode)node;o.put("testMode",MARKER).put("mockResponse",true).put("executionMode",MODE).put("realAiCalls",0);}
    }
    @Configuration(proxyBeanMethods=false) public static class RuntimeConfiguration {
        @Bean Gate fakeShutdownGate(){return new Gate();}
        @Bean FilterRegistrationBean<Gate> fakeShutdownFilter(Gate gate){var f=new FilterRegistrationBean<>(gate);f.setOrder(-90);return f;}
    }
    public static void install(ConfigurableApplicationContext ctx,Ledger ledger){
        var q=ctx.getBean(StudyQuestionController.class);ReflectionTestUtils.setField(q,"geminiModel",MODEL);ReflectionTestUtils.setField(q,"geminiApiKey",SYNTHETIC);ReflectionTestUtils.setField(q,"geminiEnabled",true);ReflectionTestUtils.setField(q,"gptEnabled",false);ReflectionTestUtils.setField(q,"httpClient",new FakeClient(ledger));
    }
    static Map<String,Object> verifyContents(ConfigurableApplicationContext ctx,Ledger ledger,IsolatedRescheduleFixture.Seed seed){
        try{
            ledger.unchanged();var responses=ctx.getBean(AiResponseRepository.class).findAll();var questions=ctx.getBean(StudyQuestionRepository.class).findAll();int count=ledger.attempted()?1:0;
            if(responses.size()!=count||questions.size()!=count)throw new IOException();
            if(count==1){var a=responses.get(0);var q=questions.get(0);if(!ANSWER.equals(a.getResponseBody())||!ANSWER.equals(q.getAiAdvice())||!QUESTION.equals(q.getQuestion())||!Objects.equals(a.getGoalId(),seed.goalA())||!Objects.equals(a.getQuestionId(),q.getQuestionId())||!MODEL.equals(a.getModel())||!"gemini".equals(a.getActualProvider())||a.getResponseSource()!=AiResponse.Source.PROVIDER)throw new IOException();}
            return Map.of("executionMode",MODE,"testMode",MARKER,"mockResponse",true,"fakeWireCalls",count,"realAiCalls",0,"fakeContentsVerified",true,"question",QUESTION);
        }catch(Exception failure){throw new IllegalStateException("Fake content/ledger proof failed");}
    }
    public static GeminiShutdown.Result finish(ConfigurableApplicationContext ctx,String url,String id,long pid,Path dir,IsolatedRescheduleFixture.Baseline before,Ledger ledger,Gate gate,IsolatedRescheduleFixture.Seed seed,GeminiShutdown.Fault fault)throws IOException{
        return GeminiShutdown.finish(ctx,url,id,pid,dir,before,ledger::attempted,gate::quiesce,()->verifyContents(ctx,ledger,seed),fault);
    }
    public static void main(String[] args){
        if(args.length!=4||!args[0].equals("--approved-gemini-shutdown-fake")){System.out.println("PREPARED_ONLY; FAKE_WIRE_ONLY; NO_SERVER_STARTED");return;}
        String stage="FAKE_APPROVAL";byte[] password=null;boolean claimed=false;Path dir=null;String id=null;int exit=0;
        try{
            int port=Integer.parseInt(args[1]);id=args[2];dir=Path.of(args[3]).toAbsolutePath().normalize();var ledger=new Ledger(dir);if(!id.equals(ledger.id)||port!=ledger.port)throw new IOException();
            // A dedicated launcher binds this immutable artifact to the approval/start reservation.
            var a=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("approval.json")));if(!GeminiShutdown.sha(Files.readAllBytes(dir.resolve("gemini-fake-shutdown.jar"))).equalsIgnoreCase(a.path("jarSha256").asText()))throw new IOException();
            ledger.claim(ProcessHandle.current().pid());claimed=true;
            try{Class.forName("com.mysql.cj.jdbc.Driver");throw new IOException();}catch(ClassNotFoundException expected){}
            if(FakeGeminiShutdownServer.class.getClassLoader().getResources("application.properties").hasMoreElements())throw new IOException();
            try(var in=new DataInputStream(System.in)){password=PreparedGeminiServer.readSecret(in,12,72);if(in.read()!=-1)throw new IOException("Only login password is accepted; no API key input");}
            var props=properties(port,id,new BCryptPasswordEncoder(12).encode(new String(password,StandardCharsets.UTF_8)));Arrays.fill(password,(byte)0);
            System.setSecurityManager(new NetworkGuard(port));var env=new StandardEnvironment();env.getPropertySources().addFirst(new MapPropertySource("fakeOnly",props));
            var app=new SpringApplication(AiStudyPlannerApplication.class,RuntimeConfiguration.class);app.setEnvironment(env);stage="FAKE_BOOT";
            try(var ctx=app.run("--spring.config.location=optional:classpath:/mobile-isolation-only.properties")){
                stage="FAKE_FIXTURE";String url=(String)props.get("spring.datasource.url");var seed=IsolatedRescheduleFixture.seed(ctx,url);var before=IsolatedRescheduleFixture.baseline(ctx);install(ctx,ledger);var gate=ctx.getBean(Gate.class);gate.open(seed,ledger);long pid=ProcessHandle.current().pid();
                stage="FAKE_READY";GeminiShutdown.atomic(dir.resolve("ready.json"),Map.of("runId",id,"pid",pid,"port",port,"address","127.0.0.1","mode",MODE,"goalA",seed.goalA(),"goalB",seed.goalB(),"date",seed.today().toString(),"realAiCalls",0),GeminiShutdown.NONE);
                stage="STOP_REQUEST_READ";while(ctx.isActive()){if(GeminiShutdown.stopRequested(dir,id,pid))break;Thread.sleep(250);}
                stage="SHUTDOWN";if(!finish(ctx,url,id,pid,dir,before,ledger,gate,seed,GeminiShutdown.NONE).preserved())exit=2;
            }
        }catch(Throwable failure){exit=1;if(failure instanceof GeminiShutdown.Failure f)stage=f.stage;else if(claimed)GeminiShutdown.recordFailure(dir,id,ProcessHandle.current().pid(),stage,failure.getClass().getSimpleName(),false,false,false,false,List.of());System.err.println("FAKE_SHUTDOWN_BLOCKED stage="+stage+"; no retry, keys, settings or raw exception emitted");}
        finally{if(password!=null)Arrays.fill(password,(byte)0);}
        if(exit!=0)System.exit(exit);
    }
}
