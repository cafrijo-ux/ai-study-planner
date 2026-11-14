package review.mobile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
class ReadOnlyLanPolicyTests {
    @TempDir Path temp;
    @Test void defaultDoesNotStartServer(){ReadOnlyLanH2Server.main(new String[0]);}
    @ParameterizedTest @ValueSource(strings={"0.0.0.0","127.0.0.1","8.8.8.8","169.254.1.2","192.168.01.2","172.32.0.1","192.168.1.256","localhost","::1"})
    void invalidOrPublicIpRejected(String ip){assertThrows(IllegalArgumentException.class,()->ReadOnlyLanH2Server.ipv4(ip));}
    @Test void portsPrivatePairAndForcedProperties()throws Exception{
        for(int p:List.of(8080,8083,8084,8085,8086,8099,8101,8102,80))assertThrows(IllegalArgumentException.class,()->ReadOnlyLanH2Server.port(p));
        var p=ReadOnlyLanH2Server.properties(8103,UUID.randomUUID().toString(),"synthetic","192.168.50.10","192.168.50.11");assertEquals("192.168.50.10",p.get("server.address"));assertEquals("org.h2.Driver",p.get("spring.datasource.driver-class-name"));assertEquals(false,p.get("gpt.weekly.enabled"));assertEquals(false,p.get("study.question.gemini.enabled"));assertEquals("none",p.get("gemini.api.key"));
        assertFalse(ReadOnlyLanH2Server.class.getClassLoader().getResources("application.properties").hasMoreElements());
    }
    @Test void noOutboundAndOnlyApprovedPeerAccepted(){
        var g=new ReadOnlyLanH2Server.NetworkGuard(8103,"192.168.50.11");for(String h:List.of("localhost","127.0.0.1","generativelanguage.googleapis.com","api.openai.com","192.168.50.11"))for(int p:List.of(-1,443,3306,8103))assertThrows(SecurityException.class,()->g.checkConnect(h,p));
        assertDoesNotThrow(()->g.checkAccept("192.168.50.11",123));assertThrows(SecurityException.class,()->g.checkAccept("192.168.50.99",123));assertThrows(SecurityException.class,()->g.checkListen(8080));
    }
    @Test void runIdClaimAndApprovalHashCannotBeReusedOrChanged()throws Exception{
        String id=UUID.randomUUID().toString();Path dir=Files.createDirectories(temp.resolve(ReadOnlyLanH2Server.BASE).resolve(id));GeminiShutdown.atomic(dir.resolve("approval.json"),ReadOnlyLanH2Tests.approval(id),GeminiShutdown.NONE);GeminiShutdown.atomic(dir.resolve("approval-claim.json"),Map.of("runId",id,"mode",ReadOnlyLanH2Server.MODE,"approvalSha256",GeminiShutdown.sha(Files.readAllBytes(dir.resolve("approval.json")))),GeminiShutdown.NONE);var scope=new ReadOnlyLanH2Server.Scope(dir);GeminiShutdown.atomic(dir.resolve("start-reserved.json"),Map.of("runId",id,"approvalSha256",scope.hash),GeminiShutdown.NONE);scope.claim(12345);assertThrows(IOException.class,()->scope.claim(12345));Files.writeString(dir.resolve("approval.json")," ",StandardOpenOption.APPEND);assertThrows(IOException.class,scope::unchanged);assertThrows(IOException.class,()->new ReadOnlyLanH2Server.Scope(dir));
    }
    @Test void closingDrainsCurrentGetAndRejectsNewClientRequest()throws Exception{
        var b=new ReadOnlyLanH2Server.Boundary("192.168.50.11");b.open(new IsolatedRescheduleFixture.Seed(1,2,10,20,java.time.LocalDate.now()));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var request=new MockHttpServletRequest("GET","/auth/csrf");request.setRemoteAddr("192.168.50.11");var active=pool.submit(()->{b.doFilter(request,new MockHttpServletResponse(),(q,r)->{entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}});return true;});assertTrue(entered.await(2,TimeUnit.SECONDS));var closing=pool.submit(()->{b.quiesce(2000);return true;});long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(System.nanoTime()<deadline){synchronized(b){if(b.closing)break;}Thread.yield();}assertThrows(TimeoutException.class,()->closing.get(50,TimeUnit.MILLISECONDS));var rejected=new MockHttpServletResponse();b.doFilter(request,rejected,(q,r)->fail());assertEquals(503,rejected.getStatus());release.countDown();assertTrue(active.get(2,TimeUnit.SECONDS));assertTrue(closing.get(2,TimeUnit.SECONDS));}finally{release.countDown();pool.shutdownNow();}
    }
}
