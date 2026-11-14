package review.mobile;

import ai_study_planner.AiStudyPlannerApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.Permission;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-runtime launcher only. No production source changes; no provider mode or MySQL fallback. */
@SuppressWarnings("removal")
public final class IsolatedStudyLogServer {
    static final ObjectMapper JSON=new ObjectMapper();
    public static void validatePasswordByteLength(int length){if(length<12||length>72)throw new IllegalArgumentException("Password byte length invalid");}
    public static void validatePort(int port){if(port<1024||port>65535||Set.of(8080,8083,8084,8085,8086).contains(port))throw new IllegalArgumentException("Dedicated approved port required");}
    public static Map<String,Object> properties(int port,String runId,String hash){
        validatePort(port);if(!UUID.fromString(runId).toString().equals(runId))throw new IllegalArgumentException();
        Map<String,Object> p=new HashMap<>();
        p.put("spring.config.location","optional:classpath:/mobile-isolation-only.properties");
        p.put("spring.config.import","");p.put("spring.profiles.active","");
        p.put("server.address","127.0.0.1");p.put("server.port",port);p.put("server.shutdown","graceful");p.put("spring.lifecycle.timeout-per-shutdown-phase","5s");
        p.put("spring.datasource.url","jdbc:h2:mem:mobile_study_"+runId+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        p.put("spring.datasource.driver-class-name","org.h2.Driver");p.put("spring.datasource.username","sa");p.put("spring.datasource.password","");p.put("spring.datasource.hikari.read-only",false);
        p.put("spring.jpa.hibernate.ddl-auto","create-drop");p.put("spring.sql.init.mode","never");p.put("spring.jpa.database-platform","org.hibernate.dialect.H2Dialect");p.put("spring.jpa.properties.hibernate.dialect","org.hibernate.dialect.H2Dialect");
        p.put("spring.jpa.show-sql",false);p.put("spring.jmx.enabled",false);p.put("spring.h2.console.enabled",false);p.put("management.endpoints.enabled-by-default",false);
        p.put("planner.auth.username","mobile-h2-owner");p.put("planner.auth.password-hash",hash);
        p.put("planner.study-log.idempotency.enabled",false);
        p.put("planner.ai-cost.enabled",false);
        for(String key:List.of("gpt.weekly.enabled","study.question.gpt.enabled","study.question.gemini.enabled"))p.put(key,false);
        for(String key:List.of("gpt.weekly.api-key","study.question.gpt.api-key","llm.api.key","gemini.api.key"))p.put(key,"none");
        p.put("llm.demo-mode",true);p.put("llm.provider","gemini");p.put("logging.level.root","OFF");p.put("spring.main.banner-mode","off");p.put("spring.main.log-startup-info",false);
        return p;
    }
    public static final class NetworkGuard extends SecurityManager {
        final int port;
        public NetworkGuard(int port){validatePort(port);this.port=port;}
        public void checkPermission(Permission p){if(p.getName().equals("setSecurityManager"))throw new SecurityException("Guard cannot be removed");}
        public void checkPermission(Permission p,Object c){checkPermission(p);}
        public void checkConnect(String host,int target){
            boolean loop=Set.of("127.0.0.1","localhost","::1","0:0:0:0:0:0:0:1").contains(host);
            if(loop&&(target==-1||target==port))return;
            throw new SecurityException("External network forbidden");
        }
        public void checkConnect(String host,int port,Object c){checkConnect(host,port);}
        public void checkListen(int p){if(p!=port)throw new SecurityException("Unexpected listener");}
        public void checkAccept(String host,int p){if(!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(host))throw new SecurityException("Loopback only");}
    }
    public static final class Gate implements Filter {
        private final AtomicBoolean attempted=new AtomicBoolean();
        private volatile IsolatedStudyLogFixture.Seed seed;
        public void open(IsolatedStudyLogFixture.Seed s){if(seed!=null)throw new IllegalStateException("Fixture already opened");seed=Objects.requireNonNull(s);}
        public boolean attempted(){return attempted.get();}
        static void error(HttpServletResponse r,int code,String text)throws IOException{r.setStatus(code);r.setContentType("application/json;charset=UTF-8");JSON.writeValue(r.getOutputStream(),Map.of("message",text));}
        public void doFilter(ServletRequest raw,ServletResponse response,FilterChain chain)throws IOException,ServletException{
            var q=(HttpServletRequest)raw;var r=(HttpServletResponse)response;String path=q.getRequestURI(),method=q.getMethod();
            if(seed==null){error(r,503,"격리 H2 fixture 준비 중입니다.");return;}
            boolean get=method.equals("GET")&&Set.of("/auth/csrf","/auth/me","/goals","/subjects","/analysis-summary","/study-logs","/study-reschedule-saved","/ai-responses","/login.html","/login.js","/auth-client.js").contains(path);
            boolean auth=method.equals("POST")&&Set.of("/auth/login","/auth/logout").contains(path);
            boolean save=method.equals("POST")&&path.equals("/study-logs");
            if(!get&&!auth&&!save){error(r,403,"격리 저장 검사에서 허용되지 않는 경로입니다. AI/다른 저장은 차단됩니다.");return;}
            if(save){
                if(!attempted.compareAndSet(false,true)){error(r,409,"격리 테스트는 공부 기록 저장 시도 1회만 허용합니다. GET으로 확인하세요.");return;}
                byte[] body=q.getInputStream().readNBytes(8193);
                if(body.length>8192){error(r,400,"입력이 너무 큽니다. 재전송하지 마세요.");return;}
                try{
                    var b=JSON.readTree(body);
                    if(!b.path("userId").isIntegralNumber()||b.path("userId").asLong()!=1||!b.path("subjectId").isIntegralNumber()||b.path("subjectId").asLong()!=seed.subjectA()||
                       !seed.date().equals(b.path("studyDate").asText())||b.path("studyMinutes").asInt()!=40||b.path("focusScore").asInt()!=3||b.path("understandingScore").asInt()!=3||b.path("fatigueScore").asInt()!=2||b.has("logId")){
                        error(r,400,"목표 A의 당일 40분/집중도3/이해도3/피로도2 입력만 허용합니다. 재전송하지 마세요.");return;
                    }
                }catch(IOException ex){error(r,400,"입력 형식 오류입니다. 재전송하지 마세요.");return;}
                q=new HttpServletRequestWrapper(q){
                    public ServletInputStream getInputStream(){var in=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}};}
                    public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
                };
            }
            chain.doFilter(q,r);
        }
    }
    @Configuration(proxyBeanMethods=false)
    public static class RuntimeConfiguration {
        @Bean public Gate isolatedStudyGate(){return new Gate();}
        @Bean public FilterRegistrationBean<Gate> isolatedStudyFilter(Gate gate){var f=new FilterRegistrationBean<>(gate);f.setOrder(Ordered.HIGHEST_PRECEDENCE);return f;}
    }
    public static void main(String[] args){
        launch(args,false);
    }
    public static void launch(String[] args,boolean reschedule){
        // No default startup. Only an approved wrapper may provide the one-time runtime inputs.
        if(args.length!=4||!args[0].equals(reschedule?"--approved-mobile-reschedule-h2":"--approved-mobile-h2")){System.out.println("PREPARED_ONLY; NO_SERVER_STARTED");return;}
        String stage="PREFLIGHT";char[] secret=null;
        try{
            int port=Integer.parseInt(args[1]);String runId=args[2];validatePort(port);UUID.fromString(runId);
            Path dir=Path.of(args[3]).toAbsolutePath().normalize();if(!dir.getFileName().toString().equals(runId)||!Files.isDirectory(dir))throw new IllegalArgumentException();
            try{Class.forName("com.mysql.cj.jdbc.Driver");throw new IllegalStateException("MySQL driver forbidden");}catch(ClassNotFoundException expected){}
            Class.forName("org.h2.Driver");if(IsolatedStudyLogServer.class.getClassLoader().getResources("application.properties").hasMoreElements())throw new IllegalStateException("External settings resource forbidden");
            try(var input=new DataInputStream(System.in)){
                int length=input.readInt();validatePasswordByteLength(length);byte[] bytes=input.readNBytes(length);
                if(bytes.length!=length||input.read()!=-1)throw new IllegalArgumentException();secret=new String(bytes,StandardCharsets.UTF_8).toCharArray();Arrays.fill(bytes,(byte)0);
            }
            String hash=new BCryptPasswordEncoder(12).encode(new String(secret));Arrays.fill(secret,'\0');
            Map<String,Object> props=properties(port,runId,hash);
            System.setSecurityManager(new NetworkGuard(port));
            stage="SPRING_BOOT_ISOLATED_START";
            var environment=new StandardEnvironment();environment.getPropertySources().addFirst(new MapPropertySource("isolatedMobileOnly",props));
            var app=new SpringApplication(AiStudyPlannerApplication.class,reschedule?IsolatedRescheduleServer.RuntimeConfiguration.class:RuntimeConfiguration.class);app.setEnvironment(environment);
            try(var ctx=app.run("--spring.config.location=optional:classpath:/mobile-isolation-only.properties")){
                long pid=ProcessHandle.current().pid();
                stage="H2_FIXTURE";
                var ready=new LinkedHashMap<String,Object>();ready.put("runId",runId);ready.put("pid",pid);ready.put("port",port);ready.put("address","127.0.0.1");ready.put("mode",reschedule?IsolatedRescheduleServer.MODE:"SYNTHETIC_H2_ONLY");
                IsolatedRescheduleFixture.Seed rescheduleSeed=null;IsolatedRescheduleFixture.Baseline baseline=null;
                if(reschedule){
                    rescheduleSeed=IsolatedRescheduleFixture.seed(ctx,(String)props.get("spring.datasource.url"));
                    IsolatedRescheduleFixture.verifyPreview(ctx,rescheduleSeed);baseline=IsolatedRescheduleFixture.baseline(ctx);
                    ctx.getBean(IsolatedRescheduleServer.Gate.class).open(rescheduleSeed);
                    ready.put("goalA",rescheduleSeed.goalA());ready.put("goalB",rescheduleSeed.goalB());ready.put("subjectA",rescheduleSeed.subjectA());ready.put("date",rescheduleSeed.today().toString());ready.put("targetDate",rescheduleSeed.target().toString());ready.put("pendingMinutes",60);ready.put("carryoverMinutes",9);ready.put("newStudyMinutes",91);
                }else{
                    var seed=IsolatedStudyLogFixture.seed(ctx,(String)props.get("spring.datasource.url"));ctx.getBean(Gate.class).open(seed);
                    ready.put("goalA",seed.goalA());ready.put("goalB",seed.goalB());ready.put("subjectA",seed.subjectA());ready.put("date",seed.date());
                }
                JSON.writeValue(dir.resolve("ready.json").toFile(),ready);
                while(ctx.isActive()){
                    Path stop=dir.resolve("stop-request.json");
                    if(Files.isRegularFile(stop)){var request=JSON.readTree(stop.toFile());if(runId.equals(request.path("runId").asText())&&request.path("pid").asLong()==pid)break;}
                    Thread.sleep(250);
                }
                if(reschedule){
                    var verification=new LinkedHashMap<>(IsolatedRescheduleFixture.preservation(ctx,rescheduleSeed,baseline));
                    verification.put("saveAttempted",ctx.getBean(IsolatedRescheduleServer.Gate.class).attempted());verification.put("runId",runId);
                    JSON.writeValue(dir.resolve("verification.json").toFile(),verification);
                }
            }
            JSON.writeValue(dir.resolve("stopped.json").toFile(),Map.of("runId",runId,"status","STOPPED"));
        }catch(Throwable ex){System.err.println("ISOLATED_START_STOPPED stage="+stage+"; no retry; no raw settings emitted");}
        finally{if(secret!=null)Arrays.fill(secret,'\0');}
    }
}
