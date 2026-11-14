package review.mobile;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class GeminiShutdownTests {
    @TempDir Path temp;
    @Test void koreanQuotesNewlinesAndTimeStringsRoundTrip()throws Exception{
        String text="[가상] 한글 \"강조\" \\ 경로\n새 문단\t탭 😀";
        var file=temp.resolve("snapshot.json");var artifact=GeminiShutdown.atomic(file,List.of(Map.of("responseBody",text,"createdAt","2026-10-28T12:13:14.123")),GeminiShutdown.NONE);
        var tree=GeminiShutdown.validJson(Files.readAllBytes(file));assertEquals(text,tree.get(0).path("responseBody").asText());assertTrue(tree.get(0).path("createdAt").isTextual());assertEquals(artifact.sha256(),GeminiShutdown.sha(Files.readAllBytes(file)));
        try(var files=Files.list(temp)){assertEquals(1,files.count());}
    }
    @ParameterizedTest @ValueSource(strings={"AFTER_FSYNC","BEFORE_PUBLISH"})
    void writeFaultNeverPublishesPartialFinal(String stage)throws Exception{
        Path file=temp.resolve("snapshot.json");assertThrows(IOException.class,()->GeminiShutdown.atomic(file,Map.of("safe","가상"),at->{if(at.equals(stage))throw new IOException("Injected IO failure");}));
        assertFalse(Files.exists(file));try(var files=Files.list(temp)){assertEquals(0,files.count());}
    }
    @Test void unsupportedSerializationCannotExposePartialFile()throws Exception{
        Path file=temp.resolve("snapshot.json");assertThrows(IOException.class,()->GeminiShutdown.atomic(file,Map.of("createdAt",LocalDateTime.now()),GeminiShutdown.NONE));assertFalse(Files.exists(file));
    }
    @Test void existingEvidenceCannotBeReplaced()throws Exception{
        Path file=temp.resolve("snapshot.json");Files.writeString(file,"{\"original\":true}");String before=GeminiShutdown.sha(Files.readAllBytes(file));
        assertThrows(FileAlreadyExistsException.class,()->GeminiShutdown.atomic(file,Map.of("original",false),GeminiShutdown.NONE));assertEquals(before,GeminiShutdown.sha(Files.readAllBytes(file)));
    }
    @Test void racingPublishersHaveOneWinnerWithValidJson()throws Exception{
        var pool=Executors.newFixedThreadPool(4);Path file=temp.resolve("snapshot.json");
        try{var tasks=new ArrayList<Callable<Boolean>>();for(int i=0;i<8;i++){final int value=i;tasks.add(()->{try{GeminiShutdown.atomic(file,Map.of("value",value),GeminiShutdown.NONE);return true;}catch(FileAlreadyExistsException rejected){return false;}});}
            long winners=0;for(var result:pool.invokeAll(tasks))if(result.get())winners++;assertEquals(1,winners);assertTrue(GeminiShutdown.validJson(Files.readAllBytes(file)).has("value"));
        }finally{pool.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(strings={"{\"id\":1,\"id\":2}","{\"id\":1,\"ID\":2}","[{\"createdAt\"", "{} {}"})
    void ambiguousOrIncompleteJsonRejected(String raw){assertThrows(IOException.class,()->GeminiShutdown.validJson(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    @Test void stopSignalsRequireCompleteJsonAndExactIdentity()throws Exception{
        String id=UUID.randomUUID().toString();assertFalse(GeminiShutdown.stopRequested(temp,id,123));Path file=temp.resolve("stop-request.json");
        Files.writeString(file,"{\"runId\":");assertThrows(IOException.class,()->GeminiShutdown.stopRequested(temp,id,123));
        Files.writeString(file,"{\"runId\":\""+id+"\",\"pid\":124}");assertFalse(GeminiShutdown.stopRequested(temp,id,123));
        Files.writeString(file,"{\"runId\":\""+id+"\",\"pid\":123}");assertTrue(GeminiShutdown.stopRequested(temp,id,123));assertFalse(GeminiShutdown.stopRequested(temp,UUID.randomUUID().toString(),123));
    }
    @Test void quiescenceWaitsForActiveRequestAndRejectsNewOnes()throws Exception{
        var gate=new PreparedGeminiServer.Gate();gate.seed=new IsolatedRescheduleFixture.Seed(1,2,10,20,LocalDate.now());
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        var request=new MockHttpServletRequest("GET","/goals");request.setUserPrincipal(()->GeminiOncePolicy.OWNER);
        try{
            var active=pool.submit(()->{gate.doFilter(request,new MockHttpServletResponse(),(q,r)->{started.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}});return true;});assertTrue(started.await(2,TimeUnit.SECONDS));
            var closing=pool.submit(()->{gate.quiesce(2000);return true;});
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!gate.isClosing()&&System.nanoTime()<deadline)Thread.yield();assertTrue(gate.isClosing());
            // A timeout is deterministic evidence of waiting, not a fixed test sleep.
            assertThrows(TimeoutException.class,()->closing.get(50,TimeUnit.MILLISECONDS));
            var rejected=new MockHttpServletResponse();gate.doFilter(request,rejected,(q,r)->fail("New request entered shutdown scope"));assertEquals(503,rejected.getStatus());
            release.countDown();assertTrue(active.get(2,TimeUnit.SECONDS));assertTrue(closing.get(2,TimeUnit.SECONDS));
        }finally{release.countDown();pool.shutdownNow();}
    }
}
