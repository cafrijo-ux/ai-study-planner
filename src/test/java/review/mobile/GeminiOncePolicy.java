package review.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.Permission;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.HttpsURLConnection;

/** Separate real-provider policy. Never used by the fully blocked Mock server. */
@SuppressWarnings("removal")
public final class GeminiOncePolicy {
    public static final String MODEL="gemini-3.5-flash-lite",HOST="generativelanguage.googleapis.com";
    public static final String QUESTION="프로세스와 스레드의 차이를 두 문장으로 설명해 주세요.";
    public static final String OWNER="gemini-once-h2-owner",MODE="GEMINI_ONCE_H2_PREPARED";
    public static final int MAX_OUTPUT=1000;
    public static final String PATH="/v1beta/models/"+MODEL+":generateContent";
    public static void createExclusive(Path path,Map<String,?> data)throws IOException{
        byte[] bytes=IsolatedStudyLogServer.JSON.writeValueAsBytes(data);
        try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var b=java.nio.ByteBuffer.wrap(bytes);while(b.hasRemaining())channel.write(b);channel.force(true);
        }
    }
    public static final class Ledger {
        final Path directory;final String approvalId;
        public Ledger(Path directory)throws IOException{
            this.directory=directory.toAbsolutePath().normalize();
            JsonNode a=IsolatedStudyLogServer.JSON.readTree(this.directory.resolve("approval.json").toFile());approvalId=a.path("approvalId").asText();
            if(!UUID.fromString(approvalId).toString().equals(approvalId)||!this.directory.getFileName().toString().equals(approvalId)||
                !a.path("mode").asText().equals(MODE)||!a.path("model").asText().equals(MODEL)||a.path("maxCalls").asInt()!=1||a.path("maxOutputTokens").asInt()!=MAX_OUTPUT||!a.path("question").asText().equals(QUESTION))throw new IOException("Approval contract invalid");
            if(a.path("requestContractVersion").asInt()!=1||!a.path("provider").asText().equals("gemini")||!a.path("subject").asText().equals("가상 운영체제")||!a.path("situation").asText().equals("복습")||!a.path("understanding").asText().equals("보통"))throw new IOException("Explicit approved fields missing or mismatched");
        }
        public void reserve()throws IOException{
            if(!IsolatedStudyLogServer.JSON.readTree(directory.resolve("approval.json").toFile()).path("generationApproved").asBoolean(false))throw new IOException("Generation not approved");
            if(!Files.isRegularFile(directory.resolve("process-claim.json")))throw new IOException("No approved process claim");
            createExclusive(directory.resolve("attempt.json"),Map.of("approvalId",approvalId,"status","ATTEMPT_RESERVED_UNCERTAIN","model",MODEL,"at",Instant.now().toString()));
        }
        public boolean attempted(){return Files.exists(directory.resolve("attempt.json"));}
        void outcome(String status){
            try{createExclusive(directory.resolve("outcome.json"),Map.of("approvalId",approvalId,"status",status,"billingVerified",false,"at",Instant.now().toString()));}
            catch(IOException ignored){/* Never lose a received answer or permit a retry due to auxiliary proof failure. */}
        }
    }
    public static final class NetworkGuard extends SecurityManager {
        final int port;final ThreadLocal<Set<String>> permitted= new ThreadLocal<>();
        public NetworkGuard(int port){IsolatedStudyLogServer.validatePort(port);this.port=port;}
        @Override public void checkPermission(Permission p){if(p.getName().equals("setSecurityManager"))throw new SecurityException("Cannot remove guard");}
        @Override public void checkPermission(Permission p,Object c){checkPermission(p);}
        @Override public void checkConnect(String host,int target){
            var ips=permitted.get();
            if(ips!=null&&((target==-1&&host.equals(HOST))||(target==443&&(host.equals(HOST)||ips.contains(host)))))return;
            throw new SecurityException("Outbound connection not approved");
        }
        @Override public void checkConnect(String host,int target,Object c){checkConnect(host,target);}
        @Override public void checkListen(int p){if(p!=port)throw new SecurityException("Protected listener");}
        @Override public void checkAccept(String host,int p){if(!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(host))throw new SecurityException("Loopback only");}
        <T> T withinApprovedAttempt(java.util.concurrent.Callable<T> action)throws Exception{
            if(permitted.get()!=null)throw new SecurityException("Nested permit");
            permitted.set(new HashSet<>());
            try{for(var address:InetAddress.getAllByName(HOST))permitted.get().add(address.getHostAddress());return action.call();}
            finally{permitted.remove();}
        }
    }
    @FunctionalInterface public interface Wire {String exchange(HttpRequest request,String payload,String key)throws Exception;}
    public static final class OneCallClient extends HttpClient {
        final Ledger ledger;final String key;final Wire wire;
        public OneCallClient(Ledger ledger,String key,Wire wire){if(key==null||key.isBlank()||key.equalsIgnoreCase("none"))throw new IllegalArgumentException("Key missing");this.ledger=ledger;this.key=key;this.wire=wire;}
        @Override @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest q,HttpResponse.BodyHandler<T> handler)throws IOException{
            String body=readBody(q);validate(q,body);
            ledger.reserve(); // durable CREATE_NEW and fsync BEFORE DNS, connect or send; never removed on failure.
            boolean received=false;
            try{
                String reply=wire.exchange(q,body,key);
                received=true;
                JsonNode root=IsolatedStudyLogServer.JSON.readTree(reply);String actual=root.path("modelVersion").asText();
                if(!(actual.equals(MODEL)||actual.startsWith(MODEL+"-")))throw new IOException("Response model mismatch");
                if(reply.contains(key))throw new IOException("Unexpected credential in response");
                ledger.outcome("RESPONSE_RECEIVED_MODEL_VALIDATED");
                return new HttpResponse<>(){
                    public int statusCode(){return 200;}public HttpRequest request(){return q;}public T body(){return (T)reply;}
                    public Optional<HttpResponse<T>> previousResponse(){return Optional.empty();}public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}
                    public Optional<javax.net.ssl.SSLSession> sslSession(){return Optional.empty();}public URI uri(){return q.uri();}public Version version(){return Version.HTTP_1_1;}
                };
            }catch(Exception failure){
                boolean timeout=failure instanceof HttpTimeoutException||failure instanceof java.net.SocketTimeoutException;
                ledger.outcome(timeout?"TIMEOUT_CALL_UNCERTAIN":received?"RESPONSE_REJECTED":"FAILED_CALL_UNCERTAIN");
                if(timeout)throw new HttpTimeoutException("Approved attempt timed out; billing/call uncertain; no retry");
                throw new IOException("Approved request failed or uncertain; no retry");
            }
        }
        static void validate(HttpRequest q,String body)throws IOException{
            URI u=q.uri();
            if(!q.method().equals("POST")||!u.getScheme().equals("https")||!u.getHost().equals(HOST)||u.getPort()!=-1||!u.getPath().equals(PATH)||u.getRawQuery()!=null||u.getUserInfo()!=null||u.getFragment()!=null)throw new IOException("Endpoint not approved");
            JsonNode b=IsolatedStudyLogServer.JSON.readTree(body);
            if(!b.isObject()||b.size()!=2||!b.has("contents")||b.path("generationConfig").size()!=1||b.path("generationConfig").path("maxOutputTokens").asInt()!=MAX_OUTPUT||!body.contains(QUESTION)||body.getBytes(StandardCharsets.UTF_8).length>4096)throw new IOException("Request outside approval");
        }
        static String readBody(HttpRequest q)throws IOException{
            var data=new ByteArrayOutputStream();var done=new CompletableFuture<String>();
            q.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>(){
                public void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(java.nio.ByteBuffer b){byte[] a=new byte[b.remaining()];b.get(a);data.writeBytes(a);}
                public void onError(Throwable e){done.completeExceptionally(e);}public void onComplete(){done.complete(data.toString(StandardCharsets.UTF_8));}
            });
            try{return done.get(2,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){throw new IOException("Payload unavailable");}
        }
        public static Wire realWire(NetworkGuard guard){return (request,payload,key)->guard.withinApprovedAttempt(()->{
            // Fixed-length streaming + retryPost=false: no buffered POST replay, redirect, proxy, auth retry or fallback.
            var c=(HttpsURLConnection)request.uri().toURL().openConnection(Proxy.NO_PROXY);
            c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setConnectTimeout(10000);c.setReadTimeout(60000);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json; charset=UTF-8");c.setRequestProperty("x-goog-api-key",key);
            byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
            try{try(var out=c.getOutputStream()){out.write(bytes);}if(c.getResponseCode()<200||c.getResponseCode()>=300)throw new IOException("Provider non-success");
                try(var in=c.getInputStream()){byte[] response=in.readNBytes(131073);if(response.length>131072)throw new IOException("Response too large");return new String(response,StandardCharsets.UTF_8);}
            }finally{c.disconnect();}
        });}
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest q,HttpResponse.BodyHandler<T> h){return CompletableFuture.failedFuture(new IOException("Async forbidden"));}
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest q,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){return sendAsync(q,h);}
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();}public Optional<java.time.Duration> connectTimeout(){return Optional.empty();}public Redirect followRedirects(){return Redirect.NEVER;}
        public Optional<ProxySelector> proxy(){return Optional.empty();}public javax.net.ssl.SSLContext sslContext(){throw new UnsupportedOperationException();}public javax.net.ssl.SSLParameters sslParameters(){return new javax.net.ssl.SSLParameters();}
        public Optional<Authenticator> authenticator(){return Optional.empty();}public Version version(){return Version.HTTP_1_1;}public Optional<java.util.concurrent.Executor> executor(){return Optional.empty();}
    }
}
