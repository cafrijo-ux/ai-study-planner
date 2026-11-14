package review.mobile;

import ai_study_planner.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.util.ContentCachingResponseWrapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent entry; never arms or changes the AI Mock environment. */
@SuppressWarnings("removal")
public final class PreparedGeminiServer {
    static final com.fasterxml.jackson.databind.ObjectMapper JSON=IsolatedStudyLogServer.JSON;
    public static final class Gate implements Filter {
        IsolatedRescheduleFixture.Seed seed;boolean enabled;GeminiOncePolicy.Ledger ledger;final AtomicBoolean submitted=new AtomicBoolean();
        private boolean closing;private int active;
        synchronized boolean isClosing(){return closing;}
        public synchronized void quiesce(long timeoutMillis)throws InterruptedException,IOException{
            closing=true;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while(active>0){long left=deadline-System.nanoTime();if(left<=0)throw new IOException("Requests still active; no preservation claim");wait(Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(left)));}
        }
        public void open(IsolatedRescheduleFixture.Seed seed,boolean enabled,GeminiOncePolicy.Ledger ledger){if(this.seed!=null)throw new IllegalStateException();this.seed=seed;this.enabled=enabled;this.ledger=Objects.requireNonNull(ledger);}
        boolean used(){return submitted.get()||ledger.attempted()||Files.exists(ledger.directory.resolve("stopped.json"));}
        Map<String,Object> contract(String[] goals){
            var result=new LinkedHashMap<String,Object>();result.put("contractVersion",1);result.put("mode",GeminiOncePolicy.MODE);result.put("model",GeminiOncePolicy.MODEL);
            result.put("approvedGoalId",seed.goalA());result.put("selectedGoalId",goals!=null&&goals.length==1?Long.parseLong(goals[0]):0);
            result.put("provider","gemini");result.put("question",GeminiOncePolicy.QUESTION);result.put("subject","가상 운영체제");result.put("situation","복습");result.put("understanding","보통");
            result.put("generationEnabled",enabled);result.put("callUsed",used());result.put("attemptReserved",ledger.attempted());return result;
        }
        static void mismatch(List<Map<String,String>> errors,String field,String code,String message){errors.add(Map.of("field",field,"code",code,"message",message));}
        List<Map<String,String>> validate(com.fasterxml.jackson.databind.JsonNode b,String[] goals,boolean preflight){
            var errors=new ArrayList<Map<String,String>>();
            if(goals==null||goals.length!=1||!goals[0].equals(""+seed.goalA()))mismatch(errors,"goalId","APPROVED_GOAL_MISMATCH","승인된 목표 A와 선택한 목표가 다릅니다.");
            if(!enabled)mismatch(errors,"serverMode","GENERATION_DISABLED","서버가 생성 비활성 모드입니다. 사전 검증으로 활성화되지 않습니다.");
            if(used())mismatch(errors,"callLimit","APPROVAL_ALREADY_USED","이 실행의 생성 요청 또는 호출 한도가 이미 사용됐습니다. 재전송하지 마세요.");
            if(b==null||!b.isObject()){mismatch(errors,"request","INVALID_JSON","입력 JSON을 확인하세요.");return errors;}
            Set<String> fields=preflight?Set.of("provider","question","subject","situation","understanding","model","serverMode"):Set.of("provider","question","subject","situation","understanding","confirmPaidCall");
            if(b.size()!=fields.size()||!fields.stream().allMatch(b::has))mismatch(errors,"request","INPUT_FIELDS_MISMATCH","승인된 입력 필드가 누락됐거나 허용되지 않는 필드가 있습니다.");
            Map<String,String> expected=Map.of("provider","gemini","question",GeminiOncePolicy.QUESTION,"subject","가상 운영체제","situation","복습","understanding","보통");
            Map<String,String> messages=Map.of("provider","승인된 제공업체는 Gemini입니다. GPT를 선택할 수 없습니다.","question","승인 질문이 다릅니다. 띄어쓰기와 마지막 마침표까지 일치해야 합니다.","subject","과목이 승인값 ‘가상 운영체제’와 다릅니다.","situation","학습 상황이 승인값 ‘복습’과 다릅니다.","understanding","이해도가 승인값 ‘보통’과 다릅니다.");
            for(var entry:expected.entrySet())if(!b.path(entry.getKey()).isTextual()||!entry.getValue().equals(b.path(entry.getKey()).asText()))mismatch(errors,entry.getKey(),"APPROVED_"+entry.getKey().toUpperCase(java.util.Locale.ROOT)+"_MISMATCH",messages.get(entry.getKey()));
            if(preflight){
                if(!GeminiOncePolicy.MODEL.equals(b.path("model").asText()))mismatch(errors,"model","APPROVED_MODEL_MISMATCH","현재 선택 모델이 승인된 Gemini 모델과 다릅니다.");
                if(!GeminiOncePolicy.MODE.equals(b.path("serverMode").asText()))mismatch(errors,"serverMode","APPROVED_MODE_MISMATCH","승인된 전용 서버 실행 모드가 일치하지 않습니다.");
            }else if(!b.path("confirmPaidCall").isBoolean()||!b.path("confirmPaidCall").asBoolean())mismatch(errors,"confirmPaidCall","PAID_CONFIRMATION_REQUIRED","사전 검증과 별도로 실제 API 호출 및 비용 확인이 필요합니다.");
            return errors;
        }
        static void json(HttpServletResponse r,int status,Object value)throws IOException{r.setStatus(status);r.setHeader("Cache-Control","no-store");r.setContentType("application/json;charset=UTF-8");JSON.writeValue(r.getOutputStream(),value);}
        static com.fasterxml.jackson.databind.JsonNode input(HttpServletRequest q)throws IOException{
            byte[] bytes=q.getInputStream().readNBytes(4097);if(bytes.length>4096)return null;
            try{return JSON.readTree(bytes);}catch(Exception invalid){return null;}
        }
        @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain)throws IOException,ServletException{
            synchronized(this){if(closing){IsolatedStudyLogServer.Gate.error((HttpServletResponse)response,503,"종료 중입니다. 새 요청은 처리하지 않습니다.");return;}active++;}
            try{handle(request,response,chain);}finally{synchronized(this){active--;notifyAll();}}
        }
        private void handle(ServletRequest request,ServletResponse response,FilterChain chain)throws IOException,ServletException{
            var q=(HttpServletRequest)request;var r=(HttpServletResponse)response;String path=q.getRequestURI(),method=q.getMethod();
            if(seed==null){IsolatedStudyLogServer.Gate.error(r,503,"준비 중");return;}
            if(method.equals("GET")&&Set.of("/auth/csrf","/auth/me","/login.html","/login.js","/auth-client.js").contains(path)){chain.doFilter(q,r);return;}
            if(q.getUserPrincipal()==null||!GeminiOncePolicy.OWNER.equals(q.getUserPrincipal().getName())){IsolatedStudyLogServer.Gate.error(r,401,"별도 가상 계정으로 로그인하세요.");return;}
            boolean read=method.equals("GET")&&Set.of("/goals","/subjects","/analysis-summary","/study-logs","/study-plan","/study-reschedule-saved","/study-question/capabilities","/llm-config-check","/gpt-weekly-coaching/preview","/ai-responses","/study-questions").contains(path);
            boolean generate=method.equals("POST")&&path.equals("/study-question");
            boolean approval=method.equals("GET")&&path.equals("/prepared-gemini/approval");
            boolean preflight=method.equals("POST")&&path.equals("/prepared-gemini/preflight");
            if(!read&&!generate&&!approval&&!preflight){IsolatedStudyLogServer.Gate.error(r,403,"이 환경은 Gemini 질문 이외의 생성·저장을 차단합니다.");return;}
            if(preflight){
                var errors=validate(input(q),q.getParameterValues("goalId"),true);
                var result=new LinkedHashMap<String,Object>();result.put("contractVersion",1);result.put("mode",GeminiOncePolicy.MODE);result.put("approvedGoalId",seed.goalA());result.put("model",GeminiOncePolicy.MODEL);result.put("valid",errors.isEmpty());result.put("errors",errors);result.put("providerCalls",0);result.put("message",errors.isEmpty()?"입력 사전 검증 통과 · 아직 AI 호출이나 비용 동의를 수행하지 않았습니다.":"승인 조건과 다른 입력을 확인하세요.");json(r,200,result);return;
            }
            if(!Set.of("/goals","/study-logs","/study-questions").contains(path)){
                String[] goals=q.getParameterValues("goalId");
                if(goals==null||goals.length!=1||!(goals[0].equals(""+seed.goalA())||goals[0].equals(""+seed.goalB()))){json(r,400,Map.of("code","APPROVED_GOAL_MISMATCH","message","가상 목표 A/B만 지정하세요."));return;}
                if(generate&&!goals[0].equals(""+seed.goalA())){IsolatedStudyLogServer.Gate.error(r,403,"생성은 가상 목표 A만 가능합니다.");return;}
            }
            if(approval){json(r,200,contract(q.getParameterValues("goalId")));return;}
            if(generate){
                var b=input(q);var errors=validate(b,q.getParameterValues("goalId"),false);
                if(!errors.isEmpty()){int status=!enabled?403:used()?409:400;json(r,status,Map.of("code","APPROVED_REQUEST_MISMATCH","message",errors.get(0).get("message"),"errors",errors));return;}
                if(!submitted.compareAndSet(false,true)){IsolatedStudyLogServer.Gate.error(r,409,"추가 생성은 차단됐습니다. GET으로 저장 여부를 확인하세요.");return;}
                q=replay(q,JSON.writeValueAsBytes(b));
            }
            if(path.equals("/study-question/capabilities")){
                var cached=new ContentCachingResponseWrapper(r);chain.doFilter(q,cached);
                if(cached.getStatus()==200){var body=JSON.readTree(cached.getContentAsByteArray());if(body.isObject()){
                    ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("preparedGeminiContract",1).put("preparedGeminiMode",GeminiOncePolicy.MODE);
                    byte[] bytes=JSON.writeValueAsBytes(body);cached.resetBuffer();cached.setContentLength(bytes.length);cached.getOutputStream().write(bytes);
                }}cached.copyBodyToResponse();return;
            }
            chain.doFilter(q,r);
        }
        static HttpServletRequest replay(HttpServletRequest q,byte[] body){return new HttpServletRequestWrapper(q){
            public ServletInputStream getInputStream(){var in=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener l){throw new UnsupportedOperationException();}};}
            public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        };}
    }
    @Configuration(proxyBeanMethods=false) public static class RuntimeConfiguration {
        @Bean Gate preparedGeminiGate(){return new Gate();}
        @Bean FilterRegistrationBean<Gate> preparedGeminiFilter(Gate gate){var f=new FilterRegistrationBean<>(gate);f.setOrder(-90);return f;}
    }
    public static Map<String,Object> properties(int port,String id,String hash){var p=IsolatedStudyLogServer.properties(port,id,hash);p.put("planner.auth.username",GeminiOncePolicy.OWNER);return p;}
    public static void configure(org.springframework.context.ConfigurableApplicationContext ctx,boolean enabled,String key,GeminiOncePolicy.OneCallClient client){
        var question=ctx.getBean(StudyQuestionController.class);
        org.springframework.test.util.ReflectionTestUtils.setField(question,"geminiModel",GeminiOncePolicy.MODEL);
        org.springframework.test.util.ReflectionTestUtils.setField(question,"geminiEnabled",enabled);
        org.springframework.test.util.ReflectionTestUtils.setField(question,"geminiApiKey",enabled?key:"none");
        if(enabled){if(client==null)throw new IllegalStateException("Constrained transport required");org.springframework.test.util.ReflectionTestUtils.setField(question,"httpClient",client);}
    }
    static byte[] readSecret(DataInputStream in,int min,int max)throws IOException{int n=in.readInt();if(n<min||n>max)throw new IOException("Secret length invalid");byte[] b=in.readNBytes(n);if(b.length!=n)throw new IOException("Secret input incomplete");return b;}
    public static void main(String[] args){
        if(args.length!=5||!args[0].equals("--approved-prepared-gemini")){System.out.println("PREPARED_ONLY; NO_SERVER_STARTED");return;}
        String stage="APPROVAL";byte[] password=null,keyBytes=null;Path runDirectory=null;String runId=null;boolean claimed=false;int exitCode=0;
        try{
            int port=Integer.parseInt(args[1]);String id=args[2];boolean enable=switch(args[4]){case "READ_ONLY"->false;case "GEMINI_ONCE"->true;default->throw new IllegalArgumentException();};
            IsolatedStudyLogServer.validatePort(port);if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();
            Path dir=Path.of(args[3]).toAbsolutePath().normalize();var ledger=new GeminiOncePolicy.Ledger(dir);
            var approval=JSON.readTree(dir.resolve("approval.json").toFile());
            if(!id.equals(approval.path("approvalId").asText())||approval.path("port").asInt()!=port||approval.path("generationApproved").asBoolean()!=enable||!Files.isRegularFile(dir.resolve("start-reserved.json")))throw new IllegalStateException();
            GeminiOncePolicy.createExclusive(dir.resolve("process-claim.json"),Map.of("approvalId",id,"pid",ProcessHandle.current().pid()));
            claimed=true;runDirectory=dir;runId=id;
            try{Class.forName("com.mysql.cj.jdbc.Driver");throw new IllegalStateException();}catch(ClassNotFoundException expected){}
            if(PreparedGeminiServer.class.getClassLoader().getResources("application.properties").hasMoreElements())throw new IllegalStateException();
            try(var in=new DataInputStream(System.in)){password=readSecret(in,12,72);keyBytes=readSecret(in,enable?16:0,enable?512:0);if(in.read()!=-1)throw new IOException();}
            var props=properties(port,id,new BCryptPasswordEncoder(12).encode(new String(password,StandardCharsets.UTF_8)));Arrays.fill(password,(byte)0);
            String key=enable?new String(keyBytes,StandardCharsets.UTF_8):"";Arrays.fill(keyBytes,(byte)0);
            System.setProperty("sun.net.http.retryPost","false");System.setProperty("http.keepAlive","false");java.util.logging.LogManager.getLogManager().reset();
            var guard=new GeminiOncePolicy.NetworkGuard(port);System.setSecurityManager(guard);
            var environment=new StandardEnvironment();environment.getPropertySources().addFirst(new MapPropertySource("preparedGeminiOnly",props));
            var app=new SpringApplication(AiStudyPlannerApplication.class,RuntimeConfiguration.class);app.setEnvironment(environment);stage="ISOLATED_BOOT";
            try(var ctx=app.run("--spring.config.location=optional:classpath:/mobile-isolation-only.properties")){
                stage="H2_AND_POLICY";var seed=IsolatedRescheduleFixture.seed(ctx,(String)props.get("spring.datasource.url"));var baseline=IsolatedRescheduleFixture.baseline(ctx);
                configure(ctx,enable,key,enable?new GeminiOncePolicy.OneCallClient(ledger,key,GeminiOncePolicy.OneCallClient.realWire(guard)):null);ctx.getBean(Gate.class).open(seed,enable,ledger);
                long pid=ProcessHandle.current().pid();stage="READY_WRITE";GeminiShutdown.atomic(dir.resolve("ready.json"),Map.of("mode",GeminiOncePolicy.MODE,"runId",id,"pid",pid,"port",port,"address","127.0.0.1","goalA",seed.goalA(),"goalB",seed.goalB(),"date",seed.today().toString(),"generationEnabled",enable),GeminiShutdown.NONE);
                stage="STOP_REQUEST_READ";
                while(ctx.isActive()){if(GeminiShutdown.stopRequested(dir,id,pid))break;Thread.sleep(250);}
                stage="SHUTDOWN";
                var result=GeminiShutdown.finish(ctx,(String)props.get("spring.datasource.url"),id,pid,dir,baseline,ledger,ctx.getBean(Gate.class),GeminiShutdown.NONE);
                if(!result.preserved()){exitCode=2;System.err.println("PREPARED_GEMINI_PRESERVATION_FAILED; no success claim; approval remains spent");}
            }
        }catch(Throwable failure){
            exitCode=1;if(failure instanceof GeminiShutdown.Failure f)stage=f.stage;
            else if(claimed)GeminiShutdown.recordFailure(runDirectory,runId,ProcessHandle.current().pid(),stage,failure.getClass().getSimpleName(),false,false,false,false,List.of());
            System.err.println("PREPARED_GEMINI_STOPPED stage="+stage+"; no retry; inspect durable approval status; no raw settings emitted");
        }
        finally{if(password!=null)Arrays.fill(password,(byte)0);if(keyBytes!=null)Arrays.fill(keyBytes,(byte)0);}
        if(exitCode!=0)System.exit(exitCode);
    }
}
