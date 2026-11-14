package review.mobile;

import ai_study_planner.*;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Shutdown evidence only. Never creates approvals, calls providers, or repairs past evidence. */
public final class GeminiShutdown {
    static final ObjectMapper JSON=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    @FunctionalInterface public interface Fault {void at(String stage)throws IOException;}
    public static final Fault NONE=stage->{};
    public record Artifact(String sha256,int bytes){}
    public static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException();}}
    public static JsonNode validJson(byte[] bytes)throws IOException{
        // Strict UTF-8, syntax, exact duplicates, case-insensitive property collisions, and trailing content.
        try{java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));}catch(java.nio.charset.CharacterCodingException e){throw new IOException("UTF8_INVALID");}
        try(var parser=JSON.getFactory().createParser(bytes)){
            JsonNode tree=JSON.readTree(parser);if(tree==null||parser.nextToken()!=null)throw new IOException("JSON_INCOMPLETE_OR_TRAILING");keys(tree);return tree;
        }
    }
    static void keys(JsonNode node)throws IOException{
        if(node.isObject()){
            var seen=new HashSet<String>();var fields=node.fields();while(fields.hasNext()){var entry=fields.next();if(!seen.add(entry.getKey().toLowerCase(Locale.ROOT)))throw new IOException("JSON_CASE_COLLISION");keys(entry.getValue());}
        }else if(node.isArray())for(var child:node)keys(child);
    }
    public static Artifact atomic(Path target,Object value,Fault fault)throws IOException{
        // Serialize completely BEFORE opening any output. LocalDateTime must be explicitly converted in DTOs.
        fault.at("SERIALIZE");byte[] bytes=JSON.writeValueAsBytes(value);validJson(bytes);
        Path parent=target.toAbsolutePath().normalize().getParent();if(!Files.isDirectory(parent))throw new IOException("OUTPUT_DIRECTORY_MISSING");
        if(Files.exists(target))throw new FileAlreadyExistsException("Evidence already published");
        Path temp=parent.resolve("."+target.getFileName()+"."+UUID.randomUUID()+".tmp");
        try{
            try(var c=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                var b=java.nio.ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);
            }
            fault.at("AFTER_FSYNC");byte[] disk=Files.readAllBytes(temp);validJson(disk);if(!MessageDigest.isEqual(bytes,disk))throw new IOException("SNAPSHOT_READBACK_MISMATCH");
            fault.at("BEFORE_PUBLISH");
            // Same-directory hard-link publication is atomic and never replaces an existing final path.
            // If unsupported, fail closed; there is no non-atomic/direct-write fallback.
            Files.createLink(target,temp);
            return new Artifact(sha(bytes),bytes.length);
        }finally{Files.deleteIfExists(temp);}
    }
    public static List<Map<String,Object>> snapshots(List<AiResponse> responses){
        var result=new ArrayList<Map<String,Object>>();
        for(var r:responses){
            var m=new LinkedHashMap<String,Object>();m.put("responseId",r.getResponseId());m.put("userId",r.getUserId());m.put("goalId",r.getGoalId());m.put("questionId",r.getQuestionId());
            m.put("feature",r.getFeature()==null?null:r.getFeature().name());m.put("requestedProvider",r.getRequestedProvider());m.put("actualProvider",r.getActualProvider());m.put("model",r.getModel());m.put("responseSource",r.getResponseSource()==null?null:r.getResponseSource().name());
            m.put("responseBody",r.getResponseBody());m.put("responseComplete",r.getResponseComplete());m.put("createdAt",r.getCreatedAt()==null?null:r.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));result.add(m);
        }
        return result;
    }
    public static boolean stopRequested(Path directory,String id,long pid)throws IOException{
        Path path=directory.resolve("stop-request.json");if(!Files.isRegularFile(path))return false;
        byte[] bytes=Files.readAllBytes(path);if(bytes.length>8192)throw new IOException("STOP_REQUEST_TOO_LARGE");
        var request=validJson(bytes);return request.isObject()&&id.equals(request.path("runId").asText())&&request.path("pid").asLong()==pid;
    }
    public record Result(boolean preserved,Artifact snapshot,Artifact verification){}
    @FunctionalInterface public interface Quiesce {void await(long timeoutMillis)throws Exception;}
    public static final class Failure extends IOException {
        public final String stage;
        Failure(String stage){super("Shutdown failed at "+stage);this.stage=stage;}
    }
    public static Result finish(ConfigurableApplicationContext ctx,String url,String id,long pid,Path directory,
        IsolatedRescheduleFixture.Baseline before,GeminiOncePolicy.Ledger ledger,PreparedGeminiServer.Gate gate,Fault fault)throws IOException{
        return finish(ctx,url,id,pid,directory,before,ledger::attempted,gate::quiesce,Map::of,fault);
    }
    /** Separate callers provide lifecycle callbacks; no fake approval is accepted by the paid ledger. */
    public static Result finish(ConfigurableApplicationContext ctx,String url,String id,long pid,Path directory,
        IsolatedRescheduleFixture.Baseline before,java.util.function.BooleanSupplier attempted,Quiesce gate,
        java.util.function.Supplier<Map<String,Object>> evidence,Fault fault)throws IOException{
        String stage="QUIESCE",failedStage=null,errorType=null;Connection shutdownConnection=null;
        boolean preserved=false,comparisonComplete=false,contextClosed=false,h2Shutdown=false;
        Artifact snapshot=null,verification=null;var cleanupErrors=new ArrayList<String>();
        try{
            fault.at(stage);gate.await(5000);
            stage="H2_IDENTITY";fault.at(stage);var jdbc=ctx.getBean(JdbcTemplate.class);IsolatedStudyLogFixture.assertH2(jdbc,url);
            // Keep a dedicated non-pooled H2 connection across context.close(), then SHUTDOWN last.
            // Otherwise Hibernate create-drop/Hikari can reconnect and recreate a DB after an earlier SHUTDOWN.
            shutdownConnection=DriverManager.getConnection(url,"sa","");
            stage="PRESERVATION_CHECK";fault.at(stage);preserved=before.equals(IsolatedRescheduleFixture.baseline(ctx));comparisonComplete=true;
            stage="SNAPSHOT_CAPTURE";fault.at(stage);
            var annotations=evidence.get();
            if(!Set.of("testMode","mockResponse","executionMode","fakeWireCalls","realAiCalls","fakeContentsVerified","question","accessSha256").containsAll(annotations.keySet()))throw new IOException("Evidence annotation key invalid");
            var rows=snapshots(ctx.getBean(AiResponseRepository.class).findAll());long questions=ctx.getBean(StudyQuestionRepository.class).count();
            for(var row:rows)row.putAll(annotations);
            stage="SNAPSHOT_WRITE";fault.at(stage);snapshot=atomic(directory.resolve("saved-ai-responses.json"),rows,fault);
            stage="VERIFICATION_WRITE";fault.at(stage);
            var proof=new LinkedHashMap<String,Object>();proof.put("schemaVersion",2);proof.put("runId",id);proof.put("pid",pid);proof.put("preserved",preserved);proof.put("status",preserved?"PRESERVED":"PRESERVATION_FAILED");proof.put("attemptReserved",attempted.getAsBoolean());proof.put("responses",rows.size());proof.put("questions",questions);proof.put("snapshotSha256",snapshot.sha256());proof.put("snapshotBytes",snapshot.bytes());proof.put("capturedAt",Instant.now().toString());proof.putAll(annotations);
            verification=atomic(directory.resolve("verification.json"),proof,fault);
        }catch(Exception failure){failedStage=stage;errorType=failure.getClass().getSimpleName();}
        finally{
            try{stage="CONTEXT_CLOSE";fault.at(stage);ctx.close();contextClosed=!ctx.isActive();if(!contextClosed)throw new IOException();}
            catch(Exception failure){cleanupErrors.add(stage);if(failedStage==null){failedStage=stage;errorType=failure.getClass().getSimpleName();}}
            try{stage="H2_SHUTDOWN";fault.at(stage);if(shutdownConnection==null)throw new IOException("No verified H2 connection");try(var statement=shutdownConnection.createStatement()){statement.execute("SHUTDOWN");}h2Shutdown=true;}
            catch(Exception failure){cleanupErrors.add(stage);if(failedStage==null){failedStage=stage;errorType=failure.getClass().getSimpleName();}}
            finally{if(shutdownConnection!=null)try{shutdownConnection.close();}catch(SQLException ignored){/* SHUTDOWN may already have closed this connection. */}}
        }
        if(failedStage==null){
            stage="STOPPED_WRITE";
            try{
                fault.at(stage);atomic(directory.resolve("stopped.json"),Map.of("schemaVersion",2,"runId",id,"pid",pid,"status",preserved?"STOPPED":"STOPPED_WITH_PRESERVATION_FAILURE","preserved",preserved,"contextClosed",contextClosed,"h2Shutdown",h2Shutdown,"verificationSha256",verification.sha256(),"snapshotSha256",snapshot.sha256()),fault);
                return new Result(preserved,snapshot,verification);
            }catch(Exception failure){failedStage=stage;errorType=failure.getClass().getSimpleName();}
        }
        recordFailure(directory,id,pid,failedStage,errorType,comparisonComplete,preserved,contextClosed,h2Shutdown,cleanupErrors);
        throw new Failure(failedStage);
    }
    public static void recordFailure(Path directory,String id,long pid,String stage,String errorType,boolean compared,boolean preserved,boolean closed,boolean h2Shutdown,List<String> cleanupErrors){
        try{
            var data=new LinkedHashMap<String,Object>();data.put("schemaVersion",2);data.put("runId",id);data.put("pid",pid);data.put("status","SHUTDOWN_FAILED");data.put("failedStage",stage);data.put("errorType",errorType);data.put("comparisonComplete",compared);data.put("preservationCheck",compared?preserved:null);data.put("verificationComplete",false);data.put("contextClosed",closed);data.put("h2Shutdown",h2Shutdown);data.put("cleanupErrors",cleanupErrors);
            atomic(directory.resolve("shutdown-error.json"),data,NONE);
        }catch(Exception cannotPublish){System.err.println("SHUTDOWN_FAILURE_RECORD_UNAVAILABLE stage="+stage+"; no raw exception or settings emitted");}
    }
}
