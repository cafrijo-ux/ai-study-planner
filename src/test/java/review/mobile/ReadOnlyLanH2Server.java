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
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.Permission;
import java.util.*;

/** Test-only LAN read harness. No MySQL/key configuration, AI transport or business writes. */
@SuppressWarnings("removal")
public final class ReadOnlyLanH2Server {
    public static final String MODE="LAN_READONLY_H2",BASE="lan-h2-approvals",OWNER="lan-h2-viewer";
    public static final int REQUEST_LIMIT=300;
    public static byte[] ipv4(String value){
        String[] p=value.split("\\.",-1);if(p.length!=4)throw new IllegalArgumentException("Canonical private IPv4 required");byte[] bytes=new byte[4];
        for(int i=0;i<4;i++){if(!p[i].matches("0|[1-9][0-9]{0,2}"))throw new IllegalArgumentException();int n=Integer.parseInt(p[i]);if(n>255)throw new IllegalArgumentException();bytes[i]=(byte)n;}
        int a=bytes[0]&255,b=bytes[1]&255;if(!(a==10||(a==172&&b>=16&&b<=31)||(a==192&&b==168)))throw new IllegalArgumentException("Private address required");return bytes;
    }
    public static void port(int port){IsolatedStudyLogServer.validatePort(port);if(Set.of(8099,8101,8102).contains(port))throw new IllegalArgumentException("Protected port");}
    public static Map<String,Object> properties(int port,String id,String hash,String address,String peer){
        port(port);ipv4(address);ipv4(peer);if(address.equals(peer))throw new IllegalArgumentException("Different PC/phone IP required");
        var p=IsolatedStudyLogServer.properties(port,id,hash);p.put("server.address",address);p.put("planner.auth.username",OWNER);p.put("server.forward-headers-strategy","NONE");return p;
    }
    public static final class NetworkGuard extends SecurityManager {
        final int port;final String peer;
        public NetworkGuard(int port,String peer){port(port);ipv4(peer);this.port=port;this.peer=peer;}
        public void checkPermission(Permission p){if(p.getName().equals("setSecurityManager"))throw new SecurityException("Cannot remove guard");}
        public void checkPermission(Permission p,Object c){checkPermission(p);}
        public void checkConnect(String h,int p){throw new SecurityException("LAN H2 forbids all outbound/DNS");}
        public void checkConnect(String h,int p,Object c){checkConnect(h,p);}
        public void checkListen(int p){if(p!=port)throw new SecurityException("Protected listener");}
        public void checkAccept(String h,int p){if(!peer.equals(h))throw new SecurityException("Only the approved peer IP");}
    }
    public static final class Scope {
        final Path dir;final String id,address,peer,hash;final int port;
        Scope(Path directory)throws IOException{
            dir=directory.toAbsolutePath().normalize();var a=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("approval.json")));
            id=a.path("runId").asText();address=a.path("address").asText();peer=a.path("peer").asText();port=a.path("port").asInt();port(port);ipv4(address);ipv4(peer);
            if(!UUID.fromString(id).toString().equals(id)||!dir.getFileName().toString().equals(id)||!dir.getParent().getFileName().toString().equals(BASE)||!MODE.equals(a.path("mode").asText())||address.equals(peer))throw new IOException("LAN scope mismatch");
            hash=GeminiShutdown.sha(Files.readAllBytes(dir.resolve("approval.json")));
            var binding=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("approval-claim.json")));
            if(!id.equals(binding.path("runId").asText())||!MODE.equals(binding.path("mode").asText())||!hash.equalsIgnoreCase(binding.path("approvalSha256").asText()))throw new IOException("Approved configuration changed");
        }
        void unchanged()throws IOException{if(!hash.equals(GeminiShutdown.sha(Files.readAllBytes(dir.resolve("approval.json")))))throw new IOException("Approval changed");}
        void claim(long pid)throws IOException{
            unchanged();var start=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("start-reserved.json")));
            if(!id.equals(start.path("runId").asText())||!hash.equalsIgnoreCase(start.path("approvalSha256").asText())||Files.exists(dir.resolve("stopped.json"))||Files.exists(dir.resolve("shutdown-error.json")))throw new IOException("Spent or wrong run");
            GeminiShutdown.atomic(dir.resolve("process-claim.json"),Map.of("runId",id,"pid",pid,"mode",MODE,"approvalSha256",hash),GeminiShutdown.NONE);
        }
    }
    public static final class Boundary implements Filter {
        final String peer;IsolatedRescheduleFixture.Seed seed;boolean closing;int active,total;final Map<String,Integer> reads=new TreeMap<>();int logins,denied;
        public Boundary(String peer){ipv4(peer);this.peer=peer;}
        public synchronized void open(IsolatedRescheduleFixture.Seed seed){if(this.seed!=null)throw new IllegalStateException();this.seed=seed;}
        public synchronized void quiesce(long millis)throws Exception{closing=true;long end=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);while(active>0){long left=end-System.nanoTime();if(left<=0)throw new IOException("Requests active");wait(Math.max(1,left/1_000_000));}}
        public void doFilter(ServletRequest raw,ServletResponse out,FilterChain chain)throws IOException,ServletException{
            var q=(HttpServletRequest)raw;var r=(HttpServletResponse)out;
            if(!peer.equals(q.getRemoteAddr())){IsolatedStudyLogServer.Gate.error(r,403,"승인된 갤럭시 주소만 허용합니다.");return;}
            synchronized(this){if(closing||seed==null){IsolatedStudyLogServer.Gate.error(r,503,"격리 LAN 준비/종료 중");return;}if(++total>REQUEST_LIMIT){IsolatedStudyLogServer.Gate.error(r,429,"격리 조회 요청 한도 초과 · 자동 재전송 금지");return;}active++;}
            try{
                String path=q.getRequestURI(),method=q.getMethod();
                boolean get=method.equals("GET")&&Set.of("/auth/csrf","/auth/me","/login.html","/login.js","/auth-client.js","/goals","/subjects","/analysis-summary","/study-logs","/study-plan","/study-reschedule-saved","/study-question/capabilities","/llm-config-check","/ai-responses","/study-questions").contains(path);
                boolean auth=method.equals("POST")&&Set.of("/auth/login","/auth/logout").contains(path);
                if(!get&&!auth){synchronized(this){denied++;}IsolatedStudyLogServer.Gate.error(r,403,"LAN 가상 환경은 로그인·조회 전용입니다. 저장·AI 생성은 차단됩니다.");return;}
                boolean scoped=get&&Set.of("/subjects","/analysis-summary","/study-plan","/study-reschedule-saved","/ai-responses","/study-question/capabilities","/llm-config-check").contains(path);
                String[] goals=q.getParameterValues("goalId");if(scoped&&(goals==null||goals.length!=1||!(goals[0].equals(""+seed.goalA())||goals[0].equals(""+seed.goalB())))){IsolatedStudyLogServer.Gate.error(r,400,"가상 목표 A/B를 명시하세요.");return;}
                chain.doFilter(q,r);
                if(r.getStatus()>=200&&r.getStatus()<300){synchronized(this){if(auth&&path.equals("/auth/login"))logins++;if(get&&!path.startsWith("/auth/")&&!path.endsWith(".html")&&!path.endsWith(".js"))reads.merge(path+(scoped?" goal="+goals[0]:""),1,Integer::sum);}}
            }finally{synchronized(this){active--;notifyAll();}}
        }
        synchronized Map<String,Object> access(String id,long pid){return Map.of("runId",id,"pid",pid,"mode",MODE,"approvedPeer",peer,"successfulLogins",logins,"successfulReads",new TreeMap<>(reads),"deniedWrites",denied,"totalRequests",total,"requestLimit",REQUEST_LIMIT);}
    }
    @Configuration(proxyBeanMethods=false) public static class RuntimeConfiguration {
        @Bean Boundary lanBoundary(@org.springframework.beans.factory.annotation.Value("${lan.approved-peer}") String peer){return new Boundary(peer);}
        @Bean FilterRegistrationBean<Boundary> lanFilter(Boundary boundary){var f=new FilterRegistrationBean<>(boundary);f.setOrder(-110);return f;}
    }
    static GeminiShutdown.Result finish(ConfigurableApplicationContext ctx,String url,Scope scope,long pid,IsolatedRescheduleFixture.Baseline before,Boundary boundary)throws IOException{
        return GeminiShutdown.finish(ctx,url,scope.id,pid,scope.dir,before,()->false,boundary::quiesce,()->{
            try{scope.unchanged();if(ctx.getBean(AiResponseRepository.class).count()!=0||ctx.getBean(StudyQuestionRepository.class).count()!=0)throw new IOException("Unexpected generated records");
                var access=GeminiShutdown.atomic(scope.dir.resolve("lan-access.json"),boundary.access(scope.id,pid),GeminiShutdown.NONE);
                return Map.of("executionMode",MODE,"realAiCalls",0,"fakeWireCalls",0,"accessSha256",access.sha256());
            }catch(IOException e){throw new IllegalStateException("LAN evidence capture failed");}
        },GeminiShutdown.NONE);
    }
    public static void main(String[] args){
        if(args.length!=2||!args[0].equals("--approved-lan-readonly-h2")){System.out.println("PREPARED_ONLY; NO_LAN_SERVER_STARTED");return;}
        String stage="LAN_APPROVAL";Scope scope=null;boolean claimed=false;byte[] password=null;int exit=0;
        try{
            scope=new Scope(Path.of(args[1]));var approval=GeminiShutdown.validJson(Files.readAllBytes(scope.dir.resolve("approval.json")));
            if(!GeminiShutdown.sha(Files.readAllBytes(scope.dir.resolve("lan-readonly-h2.jar"))).equalsIgnoreCase(approval.path("jarSha256").asText()))throw new IOException("JAR changed");
            var network=NetworkInterface.getByInetAddress(InetAddress.getByAddress(ipv4(scope.address)));
            if(network==null||!network.isUp()||network.isLoopback()||network.isVirtual()||network.getIndex()!=approval.path("interfaceIndex").asInt()||network.getDisplayName().matches("(?i).*(vpn|tap|tun|virtual|hyper-v|wireguard|tailscale).*"))throw new IOException("Physical LAN changed");
            scope.claim(ProcessHandle.current().pid());claimed=true;
            try{Class.forName("com.mysql.cj.jdbc.Driver");throw new IOException("MySQL forbidden");}catch(ClassNotFoundException expected){}
            if(ReadOnlyLanH2Server.class.getClassLoader().getResources("application.properties").hasMoreElements())throw new IOException("Config forbidden");
            try(var in=new DataInputStream(System.in)){password=PreparedGeminiServer.readSecret(in,12,72);if(in.read()!=-1)throw new IOException("Only virtual password allowed");}
            var p=properties(scope.port,scope.id,new BCryptPasswordEncoder(12).encode(new String(password,StandardCharsets.UTF_8)),scope.address,scope.peer);p.put("lan.approved-peer",scope.peer);Arrays.fill(password,(byte)0);
            System.setSecurityManager(new NetworkGuard(scope.port,scope.peer));var env=new StandardEnvironment();env.getPropertySources().addFirst(new MapPropertySource("lanH2Only",p));var app=new SpringApplication(AiStudyPlannerApplication.class,RuntimeConfiguration.class);app.setEnvironment(env);stage="LAN_H2_BOOT";
            try(var ctx=app.run("--spring.config.location=optional:classpath:/mobile-isolation-only.properties")){
                String url=(String)p.get("spring.datasource.url");stage="LAN_FIXTURE";var seed=IsolatedRescheduleFixture.seed(ctx,url);var before=IsolatedRescheduleFixture.baseline(ctx);var boundary=ctx.getBean(Boundary.class);boundary.open(seed);long pid=ProcessHandle.current().pid();
                stage="LAN_READY";GeminiShutdown.atomic(scope.dir.resolve("ready.json"),Map.of("runId",scope.id,"pid",pid,"mode",MODE,"address",scope.address,"peer",scope.peer,"port",scope.port,"goalA",seed.goalA(),"goalB",seed.goalB(),"date",seed.today().toString()),GeminiShutdown.NONE);
                stage="STOP_REQUEST_READ";while(ctx.isActive()){if(GeminiShutdown.stopRequested(scope.dir,scope.id,pid))break;Thread.sleep(250);}
                stage="LAN_SHUTDOWN";if(!finish(ctx,url,scope,pid,before,boundary).preserved())exit=2;
            }
        }catch(Throwable failure){exit=1;if(failure instanceof GeminiShutdown.Failure f)stage=f.stage;else if(claimed)GeminiShutdown.recordFailure(scope.dir,scope.id,ProcessHandle.current().pid(),stage,failure.getClass().getSimpleName(),false,false,false,false,List.of());System.err.println("LAN_H2_BLOCKED stage="+stage+"; no raw settings/retry/kill");}
        finally{if(password!=null)Arrays.fill(password,(byte)0);}if(exit!=0)System.exit(exit);
    }
}
