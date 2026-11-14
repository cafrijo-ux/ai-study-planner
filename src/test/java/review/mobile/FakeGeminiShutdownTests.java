package review.mobile;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class FakeGeminiShutdownTests {
    @TempDir Path temp;
    static Map<String,Object> approval(String id){return new LinkedHashMap<>(Map.of("approvalId",id,"port",8102,"mode",FakeGeminiShutdownServer.MODE,"model",FakeGeminiShutdownServer.MODEL,"question",FakeGeminiShutdownServer.QUESTION,"fakeOnly",true,"maxFakeCalls",1,"maxRealCalls",0));}
    Path directory()throws Exception{Path base=Files.createDirectories(temp.resolve(FakeGeminiShutdownServer.BASE));return Files.createDirectory(base.resolve(UUID.randomUUID().toString()));}
    FakeGeminiShutdownServer.Ledger ledger(Path dir)throws Exception{GeminiShutdown.atomic(dir.resolve("approval.json"),approval(dir.getFileName().toString()),GeminiShutdown.NONE);return new FakeGeminiShutdownServer.Ledger(dir);}
    void claim(FakeGeminiShutdownServer.Ledger l)throws Exception{GeminiShutdown.atomic(l.dir.resolve("start-reserved.json"),Map.of("runId",l.id,"mode",FakeGeminiShutdownServer.MODE,"approvalSha256",l.approvalHash),GeminiShutdown.NONE);l.claim(12345);}
    @Test void defaultEntryCannotStartServer(){assertDoesNotThrow(()->FakeGeminiShutdownServer.main(new String[0]));}
    @Test void allOutboundDnsGoogleOpenAiLoopbackAndOtherListenersDenied(){
        var g=new FakeGeminiShutdownServer.NetworkGuard(8102);
        for(String host:List.of("generativelanguage.googleapis.com","api.openai.com","127.0.0.1","localhost"))for(int p:List.of(-1,443,3306,8102))assertThrows(SecurityException.class,()->g.checkConnect(host,p));
        for(int p:List.of(8080,8083,8084,8085,8086))assertThrows(SecurityException.class,()->g.checkListen(p));
        assertDoesNotThrow(()->g.checkListen(8102));assertThrows(SecurityException.class,()->g.checkAccept("192.168.1.2",123));
    }
    @Test void fakeAndPaidLedgersRejectEachOthersApprovals()throws Exception{
        Path dir=directory();var l=ledger(dir);assertThrows(IOException.class,()->new GeminiOncePolicy.Ledger(dir));
        var bad=approval(UUID.randomUUID().toString());bad.put("mode",GeminiOncePolicy.MODE);Path other=directory();bad.put("approvalId",other.getFileName().toString());GeminiShutdown.atomic(other.resolve("approval.json"),bad,GeminiShutdown.NONE);assertThrows(IOException.class,()->new FakeGeminiShutdownServer.Ledger(other));
    }
    @Test void persistentClaimsAndFakeAttemptCannotBeResetOrReused()throws Exception{
        var l=ledger(directory());l.assertUnused();claim(l);assertThrows(IOException.class,l::assertUnused);assertThrows(IOException.class,()->l.claim(54321));
        l.reserve();String sha=GeminiShutdown.sha(Files.readAllBytes(l.dir.resolve("attempt.json")));assertThrows(IOException.class,l::reserve);assertThrows(IOException.class,()->new FakeGeminiShutdownServer.Ledger(l.dir).reserve());assertEquals(sha,GeminiShutdown.sha(Files.readAllBytes(l.dir.resolve("attempt.json"))));
    }
    @Test void mutatedApprovalOrWrongStartIdentityBlocksBeforeClaim()throws Exception{
        var l=ledger(directory());Files.writeString(l.dir.resolve("approval.json"),"{} ");assertThrows(IOException.class,l::unchanged);assertFalse(Files.exists(l.dir.resolve("process-claim.json")));
        var fresh=ledger(directory());GeminiShutdown.atomic(fresh.dir.resolve("start-reserved.json"),Map.of("runId",UUID.randomUUID().toString(),"mode",FakeGeminiShutdownServer.MODE,"approvalSha256",fresh.approvalHash),GeminiShutdown.NONE);assertThrows(IOException.class,()->fresh.claim(123));
    }
    @Test void wrongFolderAndForbiddenPortCannotBeAdopted()throws Exception{
        var dir=directory();var a=approval(dir.getFileName().toString());a.put("port",8080);GeminiShutdown.atomic(dir.resolve("approval.json"),a,GeminiShutdown.NONE);assertThrows(IllegalArgumentException.class,()->new FakeGeminiShutdownServer.Ledger(dir));
        var foreign=Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));GeminiShutdown.atomic(foreign.resolve("approval.json"),approval(foreign.getFileName().toString()),GeminiShutdown.NONE);assertThrows(IOException.class,()->new FakeGeminiShutdownServer.Ledger(foreign));
    }
    @Test void activeFakeRequestIsDrainedAndNewRequestsAreRejected()throws Exception{
        var l=ledger(directory());var gate=new FakeGeminiShutdownServer.Gate();gate.open(new IsolatedRescheduleFixture.Seed(1,2,10,20,java.time.LocalDate.now()),l);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var request=new MockHttpServletRequest("GET","/auth/me");var running=pool.submit(()->{gate.doFilter(request,new MockHttpServletResponse(),(q,r)->{entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}});return true;});assertTrue(entered.await(2,TimeUnit.SECONDS));
            var done=pool.submit(()->{gate.quiesce(2000);return true;});long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!gate.isClosing()&&System.nanoTime()<deadline)Thread.yield();assertTrue(gate.isClosing());assertThrows(TimeoutException.class,()->done.get(50,TimeUnit.MILLISECONDS));
            var rejected=new MockHttpServletResponse();gate.doFilter(new MockHttpServletRequest("GET","/auth/me"),rejected,(q,r)->fail("Shutdown let in a new request"));assertEquals(503,rejected.getStatus());release.countDown();assertTrue(running.get(2,TimeUnit.SECONDS));assertTrue(done.get(2,TimeUnit.SECONDS));
        }finally{release.countDown();pool.shutdownNow();}
    }
}
