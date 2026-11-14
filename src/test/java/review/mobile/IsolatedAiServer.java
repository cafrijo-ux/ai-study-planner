package review.mobile;

import ai_study_planner.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.ContentCachingResponseWrapper;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-runtime-only harness. No real keys, provider transport, MySQL driver or default server startup. */
@SuppressWarnings("removal")
public final class IsolatedAiServer {
    public static final String MODE="SYNTHETIC_H2_AI_MOCK_ONLY";
    public static final String MARKER="AI_MOCK_H2_ONLY";
    public static final String OWNER="mobile-ai-h2-owner";
    public enum ReplyMode { SUCCESS, PARTIAL, FAILURE, NETWORK }
    public static Map<String,Object> properties(int port,String id,String hash){
        var p=IsolatedStudyLogServer.properties(port,id,hash);p.put("planner.auth.username",OWNER);return p;
    }
    public static final class NetworkGuard extends SecurityManager {
        final int port;
        public NetworkGuard(int port){IsolatedStudyLogServer.validatePort(port);this.port=port;}
        @Override public void checkPermission(java.security.Permission p){if(p.getName().equals("setSecurityManager"))throw new SecurityException("Guard cannot be removed");}
        @Override public void checkPermission(java.security.Permission p,Object context){checkPermission(p);}
        @Override public void checkConnect(String host,int port){throw new SecurityException("All outbound connections forbidden in AI isolation");}
        @Override public void checkConnect(String host,int port,Object context){checkConnect(host,port);}
        @Override public void checkListen(int value){if(value!=port)throw new SecurityException("Unexpected listener");}
        @Override public void checkAccept(String host,int value){if(!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(host))throw new SecurityException("Loopback only");}
    }
    public static final class MockClient extends HttpClient {
        public final String feature;
        public final Map<String,AtomicInteger> counts;
        public volatile ReplyMode replyMode=ReplyMode.SUCCESS;
        public final List<String> prompts=new CopyOnWriteArrayList<>();
        public MockClient(String feature,Map<String,AtomicInteger> counts){this.feature=feature;this.counts=counts;}
        @Override @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler)throws IOException{
            boolean gemini=request.uri().getHost().equals("generativelanguage.googleapis.com");
            if(!gemini&&!request.uri().getHost().equals("api.openai.com"))throw new IOException("Unexpected mocked provider");
            if(!request.method().equals("POST")||!request.uri().getScheme().equals("https"))throw new IOException("Unexpected mock contract");
            String key=feature.equals("QUESTION")?(gemini?"QUESTION_GEMINI":"QUESTION_GPT"):feature;
            if(counts.computeIfAbsent(key,k->new AtomicInteger()).incrementAndGet()!=1)throw new IOException("Mock function budget exhausted");
            if(!gemini&&!request.uri().getPath().equals("/v1/responses"))throw new IOException("Unexpected GPT endpoint");
            prompts.add(body(request));
            if(replyMode==ReplyMode.NETWORK)throw new HttpTimeoutException("Synthetic timeout; no socket created");
            String text="[격리 Mock · 실제 Gemini/OpenAI 호출 없음] 가상 기록 40분은 실제 학습 근거이며, 과거 FINAL은 날짜가 있는 계획입니다. 부족 60분과 보충 배정 9분은 다릅니다. FINAL은 변경하지 않습니다.";
            String encoded=IsolatedStudyLogServer.JSON.writeValueAsString(text);
            String result=gemini?"{\"modelVersion\":\"gemini-isolation-mock\",\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":"+encoded+"}]}}]}":
                "{\"status\":\""+(replyMode==ReplyMode.PARTIAL?"incomplete":"completed")+"\",\"model\":\"gpt-4o-mini-isolation-mock\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":"+encoded+"}]}]}";
            int status=replyMode==ReplyMode.FAILURE?503:200;
            return new HttpResponse<>(){
                public int statusCode(){return status;}public HttpRequest request(){return request;}
                public Optional<HttpResponse<T>> previousResponse(){return Optional.empty();}
                public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}
                public T body(){return (T)result;}public Optional<SSLSession> sslSession(){return Optional.empty();}
                public URI uri(){return request.uri();}public Version version(){return Version.HTTP_1_1;}
            };
        }
        static String body(HttpRequest request)throws IOException{
            var bytes=new ByteArrayOutputStream();var done=new CompletableFuture<String>();
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<java.nio.ByteBuffer>(){
                public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(java.nio.ByteBuffer b){byte[] a=new byte[b.remaining()];b.get(a);bytes.writeBytes(a);}
                public void onError(Throwable e){done.completeExceptionally(e);}public void onComplete(){done.complete(bytes.toString(StandardCharsets.UTF_8));}
            });
            try{return done.get(2,TimeUnit.SECONDS);}catch(Exception e){throw new IOException("Mock payload read failed");}
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest q,HttpResponse.BodyHandler<T> h){return CompletableFuture.failedFuture(new IOException("Async mock unsupported"));}
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest q,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){return sendAsync(q,h);}
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();}public Optional<Duration> connectTimeout(){return Optional.empty();}
        public Redirect followRedirects(){return Redirect.NEVER;}public Optional<ProxySelector> proxy(){return Optional.empty();}
        public SSLContext sslContext(){throw new UnsupportedOperationException();}public SSLParameters sslParameters(){return new SSLParameters();}
        public Optional<Authenticator> authenticator(){return Optional.empty();}public Version version(){return Version.HTTP_1_1;}
        public Optional<Executor> executor(){return Optional.empty();}
    }
    public record Installed(MockClient question,MockClient daily,MockClient weekly,Map<String,AtomicInteger> counts) {
        public Map<String,Integer> totals(){var m=new TreeMap<String,Integer>();counts.forEach((k,v)->m.put(k,v.get()));return m;}
    }
    public static Installed install(org.springframework.context.ConfigurableApplicationContext ctx){
        var counts=new ConcurrentHashMap<String,AtomicInteger>();
        var q=new MockClient("QUESTION",counts);var d=new MockClient("DAILY_COACHING",counts);var w=new MockClient("WEEKLY_COACHING",counts);
        var question=ctx.getBean(StudyQuestionController.class);
        set(question,"httpClient",q);set(question,"geminiEnabled",true);set(question,"geminiApiKey","synthetic-only-no-real-key");set(question,"geminiModel","gemini-isolation-mock");
        set(question,"gptEnabled",true);set(question,"gptApiKey","synthetic-only-no-real-key");set(question,"gptModel","gpt-4o-mini");
        var daily=ctx.getBean(LlmCoachingController.class);
        set(daily,"httpClient",d);set(daily,"provider","gemini");set(daily,"model","gemini-isolation-mock");set(daily,"geminiApiKey","synthetic-only-no-real-key");set(daily,"demoMode",false);
        // The config-check endpoint is a separate bean: it must describe the same synthetic setup.
        var config=ctx.getBean(LlmConfigController.class);
        set(config,"provider","gemini");set(config,"model","gemini-isolation-mock");set(config,"geminiApiKey","synthetic-only-no-real-key");set(config,"demoMode",false);
        var weekly=ctx.getBean(GptWeeklyCoachingController.class);
        set(weekly,"httpClient",w);set(weekly,"enabled",true);set(weekly,"apiKey","synthetic-only-no-real-key");set(weekly,"model","gpt-4o-mini");
        // No in-flight or cooldown field is touched. This is a fresh isolated process only.
        return new Installed(q,d,w,counts);
    }
    static void set(Object bean,String field,Object value){ReflectionTestUtils.setField(bean,field,value);}
    public static final class Gate implements Filter {
        volatile IsolatedRescheduleFixture.Seed seed;
        final Map<String,AtomicInteger> attempts=new ConcurrentHashMap<>();
        public void open(IsolatedRescheduleFixture.Seed seed){if(this.seed!=null)throw new IllegalStateException();this.seed=Objects.requireNonNull(seed);}
        public Map<String,Integer> totals(){var m=new TreeMap<String,Integer>();attempts.forEach((k,v)->m.put(k,v.get()));return m;}
        @Override public void doFilter(ServletRequest req,ServletResponse res,FilterChain chain)throws IOException,ServletException{
            var q=(HttpServletRequest)req;var r=(HttpServletResponse)res;
            if(seed==null){IsolatedStudyLogServer.Gate.error(r,503,"AI Mock fixture 준비 중");return;}
            String path=q.getRequestURI(),method=q.getMethod();
            boolean publicRead=method.equals("GET")&&Set.of("/auth/csrf","/auth/me","/login.html","/login.js","/auth-client.js").contains(path);
            if(publicRead){chain.doFilter(q,r);return;}
            if(q.getUserPrincipal()==null||!OWNER.equals(q.getUserPrincipal().getName())){IsolatedStudyLogServer.Gate.error(r,401,"전용 가상 계정으로 로그인하세요.");return;}
            boolean read=method.equals("GET")&&Set.of("/goals","/subjects","/analysis-summary","/study-logs","/study-reschedule-saved","/study-plan","/study-question/capabilities","/llm-config-check","/gpt-weekly-coaching/preview","/ai-responses","/study-questions").contains(path);
            boolean generate=(method.equals("POST")&&Set.of("/study-question","/gpt-weekly-coaching").contains(path))||(method.equals("GET")&&path.equals("/llm-coaching"));
            if(!read&&!generate){IsolatedStudyLogServer.Gate.error(r,403,"AI 전용 격리 환경에서 다른 생성·저장은 차단됩니다.");return;}
            if(!Set.of("/goals","/study-logs","/study-questions").contains(path)){
                String[] values=q.getParameterValues("goalId");
                if(values==null||values.length!=1||!(values[0].equals(""+seed.goalA())||values[0].equals(""+seed.goalB()))){IsolatedStudyLogServer.Gate.error(r,400,"가상 목표 A/B를 명시하세요.");return;}
                if(generate&&!values[0].equals(""+seed.goalA())){IsolatedStudyLogServer.Gate.error(r,403,"Mock 생성은 가상 목표 A에만 허용됩니다.");return;}
            }
            if(generate){
                String key=path.equals("/llm-coaching")?"DAILY_COACHING":"WEEKLY_COACHING";
                if(method.equals("POST")){
                    byte[] bytes=q.getInputStream().readNBytes(16385);
                    if(bytes.length>16384){IsolatedStudyLogServer.Gate.error(r,400,"입력 크기 초과");return;}
                    com.fasterxml.jackson.databind.JsonNode b;
                    try{b=IsolatedStudyLogServer.JSON.readTree(bytes);}catch(IOException invalid){IsolatedStudyLogServer.Gate.error(r,400,"잘못된 JSON 입력");return;}
                    if(b==null||!b.isObject()||!b.path("confirmPaidCall").asBoolean(false)){IsolatedStudyLogServer.Gate.error(r,400,"명시적 확인이 필요합니다.");return;}
                    if(path.equals("/study-question")){
                        String provider=b.path("provider").asText("gemini");
                        if(!Set.of("gemini","gpt").contains(provider)){IsolatedStudyLogServer.Gate.error(r,400,"잘못된 제공업체");return;}
                        key=provider.equals("gemini")?"QUESTION_GEMINI":"QUESTION_GPT";
                    }
                    q=replay(q,bytes);
                }
                if(attempts.computeIfAbsent(key,k->new AtomicInteger()).incrementAndGet()!=1){IsolatedStudyLogServer.Gate.error(r,409,"기능별 Mock 생성 시도는 최대 1회입니다. GET으로 확인하세요.");return;}
            }
            var cached=new ContentCachingResponseWrapper(r);chain.doFilter(q,cached);
            byte[] body=cached.getContentAsByteArray();
            if(body.length>0&&cached.getContentType()!=null&&cached.getContentType().contains("application/json")){
                var tree=IsolatedStudyLogServer.JSON.readTree(body);
                if(tree.isObject())mark((com.fasterxml.jackson.databind.node.ObjectNode)tree);
                else if(tree.isArray())tree.forEach(row->{if(row.isObject())mark((com.fasterxml.jackson.databind.node.ObjectNode)row);});
                body=IsolatedStudyLogServer.JSON.writeValueAsBytes(tree);cached.resetBuffer();cached.setContentLength(body.length);cached.getOutputStream().write(body);
            }
            cached.copyBodyToResponse();
        }
        static void mark(com.fasterxml.jackson.databind.node.ObjectNode node){node.put("testMode",MARKER);node.put("mockResponse",true);}
        static HttpServletRequest replay(HttpServletRequest q,byte[] bytes){return new HttpServletRequestWrapper(q){
            public ServletInputStream getInputStream(){var in=new ByteArrayInputStream(bytes);return new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener l){throw new UnsupportedOperationException();}};}
            public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        };}
    }
    @Configuration(proxyBeanMethods=false) public static class RuntimeConfiguration {
        @Bean Gate isolatedAiGate(){return new Gate();}
        @Bean FilterRegistrationBean<Gate> isolatedAiFilter(Gate gate){var f=new FilterRegistrationBean<>(gate);f.setOrder(-90);return f;}
    }
    public static void main(String[] args){
        if(args.length!=4||!args[0].equals("--approved-mobile-ai-h2")){System.out.println("PREPARED_ONLY; NO_SERVER_STARTED");return;}
        String stage="PREFLIGHT";byte[] secret=null;
        try{
            int port=Integer.parseInt(args[1]);String id=args[2];IsolatedStudyLogServer.validatePort(port);if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();
            Path dir=Path.of(args[3]).toAbsolutePath().normalize();if(!dir.getFileName().toString().equals(id)||!Files.isDirectory(dir))throw new IllegalArgumentException();
            try{Class.forName("com.mysql.cj.jdbc.Driver");throw new IllegalStateException("MySQL forbidden");}catch(ClassNotFoundException expected){}
            Class.forName("org.h2.Driver");if(IsolatedAiServer.class.getClassLoader().getResources("application.properties").hasMoreElements())throw new IllegalStateException("External config forbidden");
            try(var in=new DataInputStream(System.in)){int length=in.readInt();IsolatedStudyLogServer.validatePasswordByteLength(length);secret=in.readNBytes(length);if(secret.length!=length||in.read()!=-1)throw new IllegalArgumentException();}
            var props=properties(port,id,new BCryptPasswordEncoder(12).encode(new String(secret,StandardCharsets.UTF_8)));Arrays.fill(secret,(byte)0);
            System.setSecurityManager(new NetworkGuard(port));
            var environment=new StandardEnvironment();environment.getPropertySources().addFirst(new MapPropertySource("isolatedAiOnly",props));
            var app=new SpringApplication(AiStudyPlannerApplication.class,RuntimeConfiguration.class);app.setEnvironment(environment);stage="ISOLATED_SPRING_START";
            try(var ctx=app.run("--spring.config.location=optional:classpath:/mobile-isolation-only.properties")){
                stage="H2_FIXTURE_AND_MOCK";var seed=IsolatedRescheduleFixture.seed(ctx,(String)props.get("spring.datasource.url"));
                var baseline=IsolatedRescheduleFixture.baseline(ctx);var installed=install(ctx);ctx.getBean(Gate.class).open(seed);
                long pid=ProcessHandle.current().pid();
                var ready=new LinkedHashMap<String,Object>();ready.put("mode",MODE);ready.put("runId",id);ready.put("pid",pid);ready.put("port",port);ready.put("address","127.0.0.1");ready.put("date",seed.today().toString());ready.put("goalA",seed.goalA());ready.put("goalB",seed.goalB());ready.put("login",OWNER);ready.put("testMode",MARKER);
                IsolatedStudyLogServer.JSON.writeValue(dir.resolve("ready.json").toFile(),ready);
                while(ctx.isActive()){
                    Path stop=dir.resolve("stop-request.json");if(Files.isRegularFile(stop)){var r=IsolatedStudyLogServer.JSON.readTree(stop.toFile());if(id.equals(r.path("runId").asText())&&r.path("pid").asLong()==pid)break;}
                    Thread.sleep(250);
                }
                var proof=new LinkedHashMap<String,Object>();proof.put("runId",id);proof.put("preserved",baseline.equals(IsolatedRescheduleFixture.baseline(ctx)));proof.put("requests",ctx.getBean(Gate.class).totals());proof.put("mockProviderCalls",installed.totals());proof.put("realAiCalls",0);proof.put("responses",ctx.getBean(AiResponseRepository.class).count());proof.put("questions",ctx.getBean(StudyQuestionRepository.class).count());
                IsolatedStudyLogServer.JSON.writeValue(dir.resolve("verification.json").toFile(),proof);
                ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class).execute("SHUTDOWN");
            }
            IsolatedStudyLogServer.JSON.writeValue(dir.resolve("stopped.json").toFile(),Map.of("runId",id,"status","STOPPED"));
        }catch(Throwable e){System.err.println("ISOLATED_AI_STOPPED stage="+stage+"; no retry; no raw settings emitted");}
        finally{if(secret!=null)Arrays.fill(secret,(byte)0);}
    }
}
