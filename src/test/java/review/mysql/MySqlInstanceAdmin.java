package review.mysql;

import review.mobile.GeminiShutdown;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.interfaces.RSAPublicKey;
import java.sql.*;
import java.util.*;

/** Approved NEW instance only. No default connection, user secrets on stdin; no raw exceptions. */
public final class MySqlInstanceAdmin {
    public static final String MODE="ISOLATED_MYSQL3307";
    static final Map<String,String> PROVISION_CODES=Map.ofEntries(
        Map.entry("INSTANCE_SCOPE","PROVISION_PRECHECK_FAILED"),Map.entry("PROVISION_INPUT","PROVISION_INPUT_FAILED"),
        Map.entry("NEW_ROOT_TLS","PROVISION_CONNECTION_FAILED"),Map.entry("NEW_ROOT_PASSWORD","PROVISION_ROOT_PASSWORD_FAILED"),
        Map.entry("INSTANCE_IDENTITY","PROVISION_IDENTITY_FAILED"),Map.entry("DEDICATED_SCHEMA_DDL","PROVISION_SCHEMA_DDL_FAILED"),
        Map.entry("NEW_MINIMUM_ACCOUNT","PROVISION_ACCOUNT_DDL_FAILED"),Map.entry("MINIMUM_GRANTS","PROVISION_GRANTS_FAILED"),
        Map.entry("MINIMUM_ACCOUNT_PREFLIGHT","PROVISION_ACCOUNT_PREFLIGHT_FAILED"),Map.entry("PROVISION_EVIDENCE","PROVISION_EVIDENCE_FAILED"),Map.entry("PROVISION_CLEANUP","PROVISION_CLEANUP_FAILED"));
    @FunctionalInterface interface Connector {Connection open(String url,Properties properties)throws SQLException;}
    static final class ProvisionPhaseException extends IOException {
        final String step,code;final boolean sqlMayHaveChanged;
        final SqlDiagnostic diagnostic;
        ProvisionPhaseException(String step,Exception failure){super(PROVISION_CODES.getOrDefault(step,"PROVISION_PRECHECK_FAILED"));this.step=step;this.code=provisionCode(step,failure);this.sqlMayHaveChanged=!Set.of("INSTANCE_SCOPE","PROVISION_INPUT","NEW_ROOT_TLS").contains(step);this.diagnostic=failure instanceof RootChangeException root?root.diagnostic:sqlDiagnostic(failure,"UNSPECIFIED",false);}
    }
    record SqlDiagnostic(String jdbcSqlState,int mysqlErrorNumber,String sqlErrorCategory,String sqlOperation,boolean rootChangeAcknowledged){}
    static SqlDiagnostic sqlDiagnostic(Throwable failure,String operation,boolean acknowledged){
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());var pending=new ArrayDeque<Throwable>();if(failure!=null)pending.add(failure);SqlDiagnostic fallback=null;
        while(!pending.isEmpty()&&seen.size()<24){var e=pending.remove();if(!seen.add(e))continue;
            if(e instanceof SQLException sql){String raw=sql.getSQLState();String state=raw!=null&&raw.matches("[0-9A-Z]{5}")?raw:"UNAVAILABLE";int n=sql.getErrorCode();if(n<0||n>65535)n=0;
                String category=switch(n){case 1820,1862->"PASSWORD_EXPIRED";case 1819->"PASSWORD_POLICY";case 1064->"SQL_SYNTAX";case 1295->"UNSUPPORTED_PREPARE";case 1045->"AUTHENTICATION";case 1396->"ACCOUNT_OPERATION";case 1142,1227->"PRIVILEGE";case 2006,2013->"TRANSPORT_UNCERTAIN";case 0->"DRIVER_OR_NON_SQL";default->"MYSQL_OTHER";};
                var diagnostic=new SqlDiagnostic(state,n,category,operation,acknowledged);if(n!=0)return diagnostic;if(fallback==null)fallback=diagnostic;
                if(sql.getNextException()!=null)pending.add(sql.getNextException());
            }
            if(e.getCause()!=null)pending.add(e.getCause());
        }
        return fallback!=null?fallback:new SqlDiagnostic("UNAVAILABLE",0,"DRIVER_OR_NON_SQL",operation,acknowledged);
    }
    static final class RootChangeException extends IOException{
        final SqlDiagnostic diagnostic;RootChangeException(Throwable failure,String operation,boolean acknowledged){super("ROOT_PASSWORD_CHANGE_BLOCKED");diagnostic=sqlDiagnostic(failure,operation,acknowledged);}
    }
    static String rootBootstrapUrl(String id){
        // Only this one fresh/expired-root connection uses local session status.
        // A server read-only SELECT or timezone SET is forbidden in sandbox mode.
        return IntegrationSchema.url(id).replace("/"+IntegrationSchema.name(id)+"?","/?").replace("forceConnectionTimeZoneToSession=true","forceConnectionTimeZoneToSession=false")+
            "&disconnectOnExpiredPasswords=false&useLocalSessionState=true&useServerPrepStmts=false&useCursorFetch=false";
    }
    static void changeInitialRootPassword(Connection c,String next)throws RootChangeException{
        PreparedStatement statement=null;String operation="PREPARE";boolean acknowledged=false;
        try{statement=c.prepareStatement("ALTER USER 'root'@'localhost' IDENTIFIED BY ?");operation="BIND";statement.setString(1,next);operation="EXECUTE";statement.execute();acknowledged=true;operation="CLOSE";var toClose=statement;statement=null;toClose.close();}
        catch(Exception failure){throw new RootChangeException(failure,operation,acknowledged);}
        finally{if(statement!=null)try{statement.close();}catch(Exception ignored){/* Original safe diagnostic wins. Never retry execute. */}}
    }
    static String provisionCode(String step,Throwable failure){
        if(step.equals("NEW_ROOT_TLS"))for(int i=0;i<12&&failure!=null;i++,failure=failure.getCause()){
            if(failure instanceof javax.net.ssl.SSLException||failure instanceof CertificateException)return "PROVISION_TLS_FAILED";
            if(failure instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().startsWith("28"))return "PROVISION_AUTH_FAILED";
        }
        return PROVISION_CODES.getOrDefault(step,"PROVISION_PRECHECK_FAILED");
    }
    static <T>T provisionStep(String step,List<String> done,TlsAction<T> action)throws ProvisionPhaseException{
        try{T value=action.run();done.add(step);return value;}catch(Exception failure){throw new ProvisionPhaseException(step,failure);}
    }
    static Map<String,Object> provisionFailure(String id,String step,List<String> completed,Throwable failure){
        IntegrationSchema.id(id);String safe=PROVISION_CODES.containsKey(step)?step:"INSTANCE_SCOPE";
        String code=failure instanceof ProvisionPhaseException p?p.code:provisionCode(safe,failure);
        boolean maybe=failure instanceof ProvisionPhaseException p&&p.sqlMayHaveChanged;
        var result=new LinkedHashMap<String,Object>();result.put("runId",id);result.put("stage","PROVISION");result.put("status","FAILED");result.put("failedStep",safe);result.put("code",code);result.put("completedSteps",completed.stream().filter(PROVISION_CODES::containsKey).toList());result.put("sqlMayHaveChanged",maybe);result.put("evidencePreserved",true);result.put("noRetry",true);
        var d=failure instanceof ProvisionPhaseException phase?phase.diagnostic:sqlDiagnostic(failure,"UNSPECIFIED",false);
        result.put("jdbcSqlState",d.jdbcSqlState());result.put("mysqlErrorNumber",d.mysqlErrorNumber());result.put("sqlErrorCategory",d.sqlErrorCategory());result.put("sqlOperation",d.sqlOperation());result.put("rootChangeAcknowledged",d.rootChangeAcknowledged()||completed.contains("NEW_ROOT_PASSWORD"));return result;
    }
    static void provisionDatabase(Path dir,MySqlIntegrationServer.Scope target,String id,String store,String initial,String next,String app,Connector connector,List<String> done)throws Exception {
        var p=new Properties();p.setProperty("user","root");p.setProperty("password",initial);p.setProperty("trustCertificateKeyStoreUrl",dir.resolve("tls/ca-only.p12").toUri().toString());p.setProperty("trustCertificateKeyStoreType","PKCS12");p.setProperty("trustCertificateKeyStorePassword",new String(MySqlIntegrationServer.pkcs12Password(store)));
        String url=rootBootstrapUrl(target.id());
        try {
            try(var c=provisionStep("NEW_ROOT_TLS",done,()->connector.open(url,p))){
                provisionStep("NEW_ROOT_PASSWORD",done,()->{changeInitialRootPassword(c,next);return null;});
                // Initialized root is expired: identity SELECT follows the approved
                // password change. Before connection, launcher pins process/CA/port.
                provisionStep("INSTANCE_IDENTITY",done,()->{verifyIdentity(c,dir);return null;});
                provisionStep("DEDICATED_SCHEMA_DDL",done,()->{for(String sql:IntegrationSchema.ddl(target.id()).replaceAll("(?m)^--.*$","").split(";"))if(!sql.isBlank())try(var s=c.createStatement()){s.execute(sql);}return null;});
                provisionStep("NEW_MINIMUM_ACCOUNT",done,()->{try(var s=c.prepareStatement("CREATE USER '"+IntegrationSchema.account(target.id())+"'@'localhost' IDENTIFIED BY ? REQUIRE SSL")){s.setString(1,app);s.execute();}return null;});
                provisionStep("MINIMUM_GRANTS",done,()->{for(String sql:IntegrationSchema.grants(target.id()).replaceAll("(?m)^--.*$","").split(";"))if(!sql.isBlank())try(var s=c.createStatement()){s.execute(sql);}return null;});
            }
            p.setProperty("user",IntegrationSchema.account(target.id()));p.setProperty("password",app);
            provisionStep("MINIMUM_ACCOUNT_PREFLIGHT",done,()->{try(var c=connector.open(IntegrationSchema.url(target.id()),p)){IntegrationSchema.verifyMySql(c,target.id());verifyIdentity(c,dir);}return null;});
            provisionStep("PROVISION_EVIDENCE",done,()->{GeminiShutdown.atomic(dir.resolve("provisioned.json"),Map.of("runId",id,"integrationRunId",target.id(),"schema",IntegrationSchema.name(target.id()),"dbPort",3307,"businessRows",0,"minimumAccountVerified",true),GeminiShutdown.NONE);return null;});
        }catch(ProvisionPhaseException failure){throw failure;}catch(Exception failure){throw new ProvisionPhaseException("PROVISION_CLEANUP",failure);}finally{p.clear();}
    }
    static com.fasterxml.jackson.databind.JsonNode scope(Path dir)throws Exception {
        dir=dir.toAbsolutePath().normalize();try(var files=Files.list(dir)){if(files.anyMatch(p->p.getFileName().toString().startsWith("phase-failure-")||p.getFileName().toString().equals("admin-failure.json")))throw new IOException("Failed run cannot be reused");}var a=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("approval.json")));
        String id=IntegrationSchema.id(a.path("runId").asText());
        if(!dir.getFileName().toString().equals(id)||!dir.getParent().getFileName().toString().equals("mysql-instance-approvals")||!MODE.equals(a.path("mode").asText())||a.path("port").asInt()!=3307||!"127.0.0.1".equals(a.path("address").asText()))throw new IOException("Isolated instance scope");
        var claim=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("approval-claim.json")));
        if(!id.equals(claim.path("runId").asText())||!GeminiShutdown.sha(Files.readAllBytes(dir.resolve("approval.json"))).equalsIgnoreCase(claim.path("sha256").asText()))throw new IOException("Approval changed");
        if(!GeminiShutdown.sha(Files.readAllBytes(dir.resolve("mysql-integration.jar"))).equalsIgnoreCase(a.path("jarSha256").asText()))throw new IOException("Instance JAR changed");
        return a;
    }
    static X509Certificate ca(Path dir)throws Exception {
        X509Certificate ca;try(var in=Files.newInputStream(dir.resolve("tls/ca.pem"))){ca=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);}
        ca.checkValidity();ca.verify(ca.getPublicKey());
        if(ca.getBasicConstraints()<0||ca.getKeyUsage()==null||!ca.getKeyUsage()[5]||!(ca.getPublicKey() instanceof RSAPublicKey rsa)||rsa.getModulus().bitLength()<3072||!Set.of("SHA256withRSA","SHA384withRSA","SHA512withRSA").contains(ca.getSigAlgName()))throw new IOException("CA policy");
        return ca;
    }
    public static void run(String[] args) {
        if(args.length<2||!Set.of("TRUSTSTORE","PROVISION").contains(args[0])){System.out.println("INSTANCE_ADMIN_PREPARED_ONLY; NO_DB");return;}
        String stage="INSTANCE_SCOPE";Path dir=Path.of(args[1]).toAbsolutePath().normalize();boolean scopeVerified=false;var completed=new ArrayList<String>();
        try {
            var a=scope(dir);String id=a.path("runId").asText();scopeVerified=true;
            if(args[0].equals("TRUSTSTORE")) {
                System.setSecurityManager(new SecurityManager(){
                    public void checkPermission(Permission p){if(p.getName().equals("setSecurityManager"))throw new SecurityException("Guard removal forbidden");}
                    public void checkConnect(String h,int p){throw new SecurityException("TRUSTSTORE is offline only");}
                    public void checkConnect(String h,int p,Object c){checkConnect(h,p);}
                    public void checkListen(int p){throw new SecurityException("No listener");}
                });
                if(!Files.exists(dir.resolve("tls-reserved.json")))throw new IOException("TLS approval missing");
                stage="TLS_PKCS12_INPUT_VALIDATION";
                char[] password;try(var in=new DataInputStream(System.in)){password=MySqlIntegrationServer.pkcs12Password(MySqlIntegrationServer.secret(in,12));if(in.read()!=-1){Arrays.fill(password,'\0');throw new IOException();}}
                try {
                    completed.add(stage);stage="TLS_CA_PUBLIC_VALIDATION";var root=ca(dir);completed.add(stage);stage="TLS_PKCS12_CREATION";
                    createTrustStore(root,password,dir.resolve("tls/ca-only.p12"),completed);
                    stage="TLS_EVIDENCE_PUBLICATION";
                    GeminiShutdown.atomic(dir.resolve("truststore-proof.json"),Map.of("runId",id,"caEntries",1,"privateKeyEntries",0,"passwordEncoding","UTF8_BASE64_V1","sha256",GeminiShutdown.sha(Files.readAllBytes(dir.resolve("tls/ca-only.p12")))),GeminiShutdown.NONE);
                } finally {Arrays.fill(password,'\0');}
                System.out.println("CA_ONLY_TRUSTSTORE_CREATED; NO_DB");return;
            }
            if(args.length!=3)throw new IOException("Integration scope required");
            var target=MySqlIntegrationServer.scope(Path.of(args[2]));
            if(!id.equals(target.approval().path("instanceRunId").asText())||!Path.of(target.approval().path("trustStore").asText()).equals(dir.resolve("tls/ca-only.p12")))throw new IOException("Different instance/CA");
            var consent=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("provision-reserved.json")));
            if(!id.equals(consent.path("runId").asText())||!target.id().equals(consent.path("integrationRunId").asText()))throw new IOException("Provision consent missing");
            stage="PROVISION_INPUT";String store,initial,next,app;try(var in=new DataInputStream(System.in)){store=MySqlIntegrationServer.secret(in,12);initial=MySqlIntegrationServer.secret(in,8);next=MySqlIntegrationServer.secret(in,16);app=MySqlIntegrationServer.secret(in,16);if(in.read()!=-1)throw new IOException();}
            completed.add(stage);System.setSecurityManager(new MySqlIntegrationServer.Guard(8104,false));
            provisionDatabase(dir,target,id,store,initial,next,app,DriverManager::getConnection,completed);
            System.out.println("NEW_SCHEMA_PROVISIONED; NO_ORIGINAL_DB_ACCESS");
        }catch(Throwable failure){if(failure instanceof TlsPhaseException phase)stage=phase.step;if(failure instanceof ProvisionPhaseException phase)stage=phase.step;if(scopeVerified)try{Object record=args[0].equals("TRUSTSTORE")?tlsFailure(dir.getFileName().toString(),stage,completed):provisionFailure(dir.getFileName().toString(),stage,completed,failure);GeminiShutdown.atomic(dir.resolve("admin-failure.json"),record,GeminiShutdown.NONE);}catch(Exception ignored){}throw new IllegalStateException("INSTANCE_ADMIN_BLOCKED stage="+stage);}
    }
    static Map<String,Object> tlsFailure(String id,String step,List<String> completed) {
        IntegrationSchema.id(id);var codes=Map.of("INSTANCE_SCOPE","TLS_APPROVAL_FAILED","TLS_PKCS12_INPUT_VALIDATION","TLS_PKCS12_INPUT_FAILED","TLS_CA_PUBLIC_VALIDATION","TLS_CA_VALIDATION_FAILED","TLS_PKCS12_CREATION","TLS_PKCS12_CREATION_FAILED","TLS_PKCS12_KEYSTORE","TLS_PKCS12_KEYSTORE_FAILED","TLS_PKCS12_WRITE","TLS_PKCS12_WRITE_FAILED","TLS_PKCS12_VALIDATION","TLS_PKCS12_VALIDATION_FAILED","TLS_PKCS12_PUBLICATION","TLS_PKCS12_PUBLICATION_FAILED","TLS_EVIDENCE_PUBLICATION","TLS_EVIDENCE_PUBLICATION_FAILED");
        String safe=codes.containsKey(step)?step:"INSTANCE_SCOPE";
        return Map.of("runId",id,"stage","TLS","status","FAILED","failedStep",safe,"code",codes.get(safe),"mysqlStarted",false,"completedSteps",completed.stream().filter(codes::containsKey).toList(),"evidencePreserved",true);
    }
    static void publishTrustStore(KeyStore store,char[] password,Path target)throws Exception {
        publishTrustStore(store,password,target,null,new ArrayList<>());
    }
    static final class TlsPhaseException extends IOException {
        final String step;TlsPhaseException(String step){super(step);this.step=step;}
    }
    @FunctionalInterface interface TlsAction<T>{T run()throws Exception;}
    static <T>T tlsStep(String step,List<String> completed,TlsAction<T> action)throws TlsPhaseException{
        try{T result=action.run();completed.add(step);return result;}catch(Exception ignored){throw new TlsPhaseException(step);}
    }
    static void createTrustStore(X509Certificate root,char[] password,Path target,List<String> completed)throws Exception{
        var trust=tlsStep("TLS_PKCS12_KEYSTORE",completed,()->{var k=KeyStore.getInstance("PKCS12");k.load(null,null);k.setCertificateEntry("isolated-mysql-ca",root);validateExactCa(k,root);return k;});
        publishTrustStore(trust,password,target,root,completed);
    }
    static void validateExactCa(KeyStore store,X509Certificate expected)throws Exception{ // gitleaks:allow -- only a public CA alias is validated here
        MySqlIntegrationServer.validateTrustStore(store);
        if(store.size()!=1||!store.isCertificateEntry("isolated-mysql-ca")||store.isKeyEntry("isolated-mysql-ca")||
            (expected!=null&&!Arrays.equals(expected.getEncoded(),store.getCertificate("isolated-mysql-ca").getEncoded())))throw new IOException("Exact CA-only store required");
    }
    static void publishTrustStore(KeyStore store,char[] password,Path target,X509Certificate expected,List<String> completed)throws Exception {
        Path temp=tlsStep("TLS_PKCS12_WRITE",new ArrayList<>(),()->Files.createTempFile(target.getParent(),".ca-store-",".tmp"));
        try {
            tlsStep("TLS_PKCS12_WRITE",completed,()->{try(var f=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)){store.store(java.nio.channels.Channels.newOutputStream(f),password);f.force(true);}return null;});
            tlsStep("TLS_PKCS12_VALIDATION",completed,()->{var read=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(temp)){read.load(in,password);}validateExactCa(read,expected);return null;});
            // No overwrite: same fsync + atomic hard-link publication policy as JSON evidence.
            tlsStep("TLS_PKCS12_PUBLICATION",completed,()->{Files.createLink(target,temp);return null;});
        }finally{try{Files.deleteIfExists(temp);}catch(IOException ignored){throw new TlsPhaseException("TLS_PKCS12_PUBLICATION");}}
    }
    static void verifyIdentity(Connection c,Path dir)throws Exception {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT @@port,@@datadir")){
            if(!r.next()||r.getInt(1)!=3307||!Path.of(r.getString(2)).toAbsolutePath().normalize().equals(dir.resolve("data")))throw new IOException("Wrong MySQL instance datadir/port");
        }
    }
}
