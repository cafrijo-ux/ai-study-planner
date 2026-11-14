package review.mysql;
import ai_study_planner.*;
import org.springframework.boot.*;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.support.GenericWebApplicationContext;
import review.mobile.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

@SuppressWarnings("removal")
public final class MySqlIntegrationServer {
 public static final String BASE="mysql-integration-approvals";
 public static final class Guard extends SecurityManager {
  final int port;final boolean http;public Guard(int port,boolean http){IntegrationSchema.port(port);this.port=port;this.http=http;}
  public void checkPermission(Permission p){if(p.getName().equals("setSecurityManager"))throw new SecurityException("Guard removal forbidden");}
  public void checkPermission(Permission p,Object c){checkPermission(p);}
  public void checkConnect(String host,int p){if(host.equals("127.0.0.1")&&p==IntegrationSchema.DB_PORT)return;throw new SecurityException("Only approved isolated MySQL3307; never3306/DNS/AI/email");}
  public void checkConnect(String h,int p,Object c){checkConnect(h,p);}
  public void checkListen(int p){if(!http||p!=port)throw new SecurityException("No unapproved listener");}
  public void checkAccept(String h,int p){if(!h.equals("127.0.0.1")&&!h.equals("::1"))throw new SecurityException("Loopback only");}
 }
 @Configuration(proxyBeanMethods=false) public static class Config {
  @Bean IntegrationRuntime.Gate integrationGate(ConfigurableApplicationContext ctx,@org.springframework.beans.factory.annotation.Value("${integration.run-id}")String id){return new IntegrationRuntime.Gate(ctx,id);}
  @Bean FilterRegistrationBean<IntegrationRuntime.Gate> integrationFilter(IntegrationRuntime.Gate gate){var r=new FilterRegistrationBean<>(gate);r.setOrder(-90);return r;}
  @Bean IntegrationRuntime.FixtureApi fixtureApi(ConfigurableApplicationContext ctx){return new IntegrationRuntime.FixtureApi(ctx);}
 }
 public record Scope(Path dir,String id,String phase,int port,String hash,com.fasterxml.jackson.databind.JsonNode approval){}
 public static Scope scope(Path raw)throws Exception{
  Path dir=raw.toAbsolutePath().normalize();var a=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("approval.json")));String id=IntegrationSchema.id(a.path("runId").asText());int port=a.path("port").asInt();IntegrationSchema.port(port);String phase=a.path("phase").asText();
  if(!dir.getFileName().toString().equals(id)||!dir.getParent().getFileName().toString().equals(BASE)||!IntegrationSchema.MODE.equals(a.path("mode").asText())||!Set.of("SCENARIO","HTTP").contains(phase)||!IntegrationSchema.name(id).equals(a.path("schema").asText())||!"127.0.0.1".equals(a.path("dbHost").asText())||a.path("dbPort").asInt()!=IntegrationSchema.DB_PORT)throw new IOException("Wrong integration scope/isolated DB port");
  String instanceId=IntegrationSchema.id(a.path("instanceRunId").asText());Path instance=Path.of(a.path("instanceDirectory").asText()).toAbsolutePath().normalize();if(!instance.getFileName().toString().equals(instanceId)||!instance.getParent().getFileName().toString().equals("mysql-instance-approvals")||!GeminiShutdown.sha(Files.readAllBytes(instance.resolve("ready.json"))).equalsIgnoreCase(a.path("instanceReadySha256").asText()))throw new IOException("Isolated instance evidence changed");
  for(Path check:List.of(dir,instance))try(var files=Files.list(check)){if(files.anyMatch(p->p.getFileName().toString().startsWith("phase-failure-")||Set.of("failure.json","admin-failure.json").contains(p.getFileName().toString())))throw new IOException("Failed or linked run cannot be reused");}
  CalendarReadOnlySession.scenario(phase,a.path("httpScenario").asText("ANDROID_WRITE"));
  String hash=GeminiShutdown.sha(Files.readAllBytes(dir.resolve("approval.json")));var claim=GeminiShutdown.validJson(Files.readAllBytes(dir.resolve("approval-claim.json")));if(!id.equals(claim.path("runId").asText())||!hash.equalsIgnoreCase(claim.path("approvalSha256").asText()))throw new IOException("Approval changed");return new Scope(dir,id,phase,port,hash,a);
 }
 static Map<String,Object> properties(Scope scope,String dbPassword,String password,String storePassword){
  var p=IsolatedStudyLogServer.properties(scope.port,scope.id,new BCryptPasswordEncoder(12).encode(password));p.put("planner.auth.username",IntegrationSchema.OWNER);p.put("integration.run-id",scope.id);p.put("integration.phase",scope.phase);
  p.put("spring.datasource.url",IntegrationSchema.url(scope.id));p.put("spring.datasource.username",IntegrationSchema.account(scope.id));p.put("spring.datasource.password",dbPassword);p.put("spring.datasource.driver-class-name","com.mysql.cj.jdbc.Driver");p.put("spring.jpa.hibernate.ddl-auto","none");p.put("spring.jpa.database-platform","org.hibernate.dialect.MySQLDialect");p.put("spring.jpa.properties.hibernate.dialect","org.hibernate.dialect.MySQLDialect");p.put("spring.flyway.enabled",false);p.put("spring.liquibase.enabled",false);
  p.put("spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreUrl",Path.of(scope.approval.path("trustStore").asText()).toUri().toString());p.put("spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreType","PKCS12");p.put("spring.datasource.hikari.data-source-properties.trustCertificateKeyStorePassword",new String(pkcs12Password(storePassword)));p.put("spring.datasource.hikari.maximum-pool-size",4);p.put("spring.datasource.hikari.minimum-idle",0);return p;
 }
 static String secret(DataInputStream in,int min)throws Exception{int n=in.readInt();if(n<min||n>512)throw new IOException("Secret length");byte[] bytes=in.readNBytes(n);try{if(bytes.length!=n)throw new IOException();return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}finally{Arrays.fill(bytes,(byte)0);}}
 // SunPKCS12's PBE rejects non-ASCII passwords on Java17. Losslessly encode the
 // original UTF-8 secret for EVERY PKCS12 writer/reader and Connector/J consumer.
 // This is encoding, not password hashing or a relaxation of encryption settings.
 static char[] pkcs12Password(String secret){byte[] bytes=secret.getBytes(StandardCharsets.UTF_8);try{return ("u8v1:"+Base64.getEncoder().encodeToString(bytes)).toCharArray();}finally{Arrays.fill(bytes,(byte)0);}}
 static void validateTrustStore(KeyStore store)throws Exception{if(store.size()==0)throw new IOException("Empty CA store");for(var names=store.aliases();names.hasMoreElements();){String alias=names.nextElement();if(store.isKeyEntry(alias)||!(store.getCertificate(alias) instanceof java.security.cert.X509Certificate ca)||ca.getBasicConstraints()<0)throw new IOException("CA certificates only; no private/leaf entries");}}
 public static void main(String[] args){
  if(args.length>=1&&args[0].equals("--instance-admin")){MySqlInstanceAdmin.run(Arrays.copyOfRange(args,1,args.length));return;}
  if(args.length!=2||!Set.of("--prepare-mysql-files","--approved-mysql-integration").contains(args[0])){System.out.println("PREPARED_ONLY; NO_DB_OR_SERVER_STARTED");return;}
  String stage="APPROVAL";Scope scope=null;boolean claimed=false;int exit=0;
  try{
   scope=scope(Path.of(args[1]));
   if(args[0].equals("--prepare-mysql-files")){for(var e:Map.of("schema.review.sql",IntegrationSchema.ddl(scope.id),"grants.review.sql",IntegrationSchema.grants(scope.id)).entrySet())try(var out=java.nio.channels.FileChannel.open(scope.dir.resolve(e.getKey()),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var b=java.nio.ByteBuffer.wrap(e.getValue().getBytes(StandardCharsets.UTF_8));while(b.hasRemaining())out.write(b);out.force(true);}System.out.println("SQL_PREPARED_ONLY; NO_DB_CONNECTION");return;}
   if(!GeminiShutdown.sha(Files.readAllBytes(scope.dir.resolve("mysql-integration.jar"))).equalsIgnoreCase(scope.approval.path("jarSha256").asText()))throw new IOException("JAR mismatch");
   var dbConsent=GeminiShutdown.validJson(Files.readAllBytes(scope.dir.resolve("db-approved.json")));var start=GeminiShutdown.validJson(Files.readAllBytes(scope.dir.resolve("start-reserved.json")));
   if(!scope.hash.equalsIgnoreCase(dbConsent.path("approvalSha256").asText())||!scope.id.equals(dbConsent.path("runId").asText())||!dbConsent.path("reviewedSqlOnly").asBoolean()||!dbConsent.path("noStoredSideEffectsAdminReviewed").asBoolean()||!scope.hash.equalsIgnoreCase(start.path("approvalSha256").asText())||!scope.id.equals(start.path("runId").asText()))throw new IOException("DB/start consent missing");
   GeminiShutdown.atomic(scope.dir.resolve("process-claim.json"),Map.of("runId",scope.id,"pid",ProcessHandle.current().pid(),"mode",IntegrationSchema.MODE),GeminiShutdown.NONE);claimed=true;
   String dbPassword,password,storePassword;try(var in=new DataInputStream(System.in)){dbPassword=secret(in,8);password=secret(in,12);storePassword=secret(in,1);if(in.read()!=-1)throw new IOException();}
   stage="TRUSTSTORE";Path trust=Path.of(scope.approval.path("trustStore").asText());if(!GeminiShutdown.sha(Files.readAllBytes(trust)).equalsIgnoreCase(scope.approval.path("trustStoreSha256").asText()))throw new IOException("Trust store changed");var ks=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(trust)){ks.load(input,pkcs12Password(storePassword));}validateTrustStore(ks);
   System.setSecurityManager(new Guard(scope.port,scope.phase.equals("HTTP")));stage="MYSQL_READONLY_PREFLIGHT";
   var props=new Properties();props.setProperty("user",IntegrationSchema.account(scope.id));props.setProperty("password",dbPassword);props.setProperty("trustCertificateKeyStoreUrl",trust.toUri().toString());props.setProperty("trustCertificateKeyStoreType","PKCS12");props.setProperty("trustCertificateKeyStorePassword",new String(pkcs12Password(storePassword)));
   try(var c=DriverManager.getConnection(IntegrationSchema.url(scope.id),props)){MySqlInstanceAdmin.verifyIdentity(c,Path.of(scope.approval.path("instanceDirectory").asText()));IntegrationSchema.verifyMySql(c,scope.id);GeminiShutdown.atomic(scope.dir.resolve("db-preflight.json"),Map.of("runId",scope.id,"database",IntegrationSchema.name(scope.id),"dbPort",3307,"driver","MySQL","tlsVerified",true,"accountMatched",true,"emptyBusinessTables",true),GeminiShutdown.NONE);IntegrationSchema.claim(c,scope.id);}finally{props.clear();}
   var env=new StandardEnvironment();env.getPropertySources().addFirst(new MapPropertySource("integrationOnly",properties(scope,dbPassword,password,storePassword)));var app=new SpringApplication(AiStudyPlannerApplication.class,Config.class);app.setEnvironment(env);
   if(scope.phase.equals("SCENARIO")){app.setWebApplicationType(WebApplicationType.SERVLET);app.setApplicationContextFactory(type->{var c=new GenericWebApplicationContext();c.setServletContext(new org.springframework.mock.web.MockServletContext());return c;});}
   stage="SPRING_BOOT";try(var ctx=app.run("--spring.config.location=optional:classpath:/integration-only.properties")){
    IntegrationRuntime.seed(ctx);long pid=ProcessHandle.current().pid();var ready=new LinkedHashMap<String,Object>();ready.put("runId",scope.id);ready.put("pid",pid);ready.put("mode",IntegrationSchema.MODE);ready.put("phase",scope.phase);ready.put("schema",IntegrationSchema.name(scope.id));ready.put("port",scope.port);ready.put("address","127.0.0.1");ready.put("approvalSha256",scope.hash);ready.put("jarSha256",scope.approval.path("jarSha256").asText());
    if(scope.phase.equals("HTTP")){
     stage="HTTP_VIRTUAL_FIXTURE";var gate=ctx.getBean(IntegrationRuntime.Gate.class);final var running=ctx;final var runScope=scope;
     if(CalendarReadOnlySession.MODE.equals(scope.approval.path("httpScenario").asText("ANDROID_WRITE"))){
      var session=gate.bootstrapAndroid(()->CalendarReadOnlySession.initialize(running,runScope.id,password));gate.enableCalendar(session);
      ready.putAll(session.ready());ready.put("goalA",session.fixture.goalA());ready.put("goalB",session.fixture.goalB());ready.put("subjectA",session.fixture.subjectA());ready.put("subjectB",session.fixture.subjectB());
      var fixtureProof=GeminiShutdown.atomic(scope.dir.resolve("calendar-fixture.json"),Map.of("runId",scope.id,"mode",CalendarReadOnlySession.MODE,"baseline",session.baseline,"plan",session.contract),GeminiShutdown.NONE);ready.put("calendarFixtureSha256",fixtureProof.sha256());
     }else{
      var fixtureDate=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));var fixture=gate.bootstrapAndroid(()->new IntegrationScenario(running,runScope.id).initialize(password));
      if(!fixtureDate.equals(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))))throw new IOException("HTTP_FIXTURE_DATE_CHANGED");gate.enableAndroid(fixture);if(!fixtureDate.equals(gate.android.today))throw new IOException("HTTP_FIXTURE_DATE_CHANGED");
      ready.put("goalA",fixture.goalA());ready.put("goalB",fixture.goalB());ready.put("subjectA",fixture.subjectA());ready.put("subjectB",fixture.subjectB());ready.put("initialStudyLogs",0);ready.putAll(gate.android.dateEvidence());ready.put("inputPastStudyMinutes",fixture.past().path("studyMinutes").asInt());ready.put("maxStudyWrites",1);ready.put("maxPlanWrites",1);ready.put("androidMode","ANDROID_HTTP_MYSQL");
     }
    }
    GeminiShutdown.atomic(scope.dir.resolve("ready.json"),ready,GeminiShutdown.NONE);
    if(scope.phase.equals("SCENARIO")){stage="SCENARIO";var scenario=new IntegrationScenario(ctx,scope.id);var result=scenario.run(password);scenario.verifyLogout();GeminiShutdown.atomic(scope.dir.resolve("scenario-results.json"),result.proof(),GeminiShutdown.NONE);}
    else{stage="STOP_REQUEST";while(ctx.isActive()){if(GeminiShutdown.stopRequested(scope.dir,scope.id,pid))break;Thread.sleep(250);}}
    stage="SHUTDOWN";var gate=ctx.getBean(IntegrationRuntime.Gate.class);gate.quiesce(5000);var verification=new LinkedHashMap<String,Object>();verification.put("runId",scope.id);verification.put("pid",pid);verification.put("mode",IntegrationSchema.MODE);verification.put("schema",IntegrationSchema.name(scope.id));verification.put("databaseRetained",true);verification.put("realAiCalls",0);verification.put("tables",IntegrationRuntime.snapshot(ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class)));if(gate.android!=null)verification.put("android",gate.android.verify());if(gate.calendar!=null){verification.put("calendar",gate.calendar.verify());verification.put("calendarFixtureSha256",ready.get("calendarFixtureSha256"));}var proof=GeminiShutdown.atomic(scope.dir.resolve("verification.json"),verification,GeminiShutdown.NONE);
    ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update("UPDATE integration_run_state SET state='STOPPED' WHERE run_id=?",scope.id);ctx.close();GeminiShutdown.atomic(scope.dir.resolve("stopped.json"),Map.of("runId",scope.id,"pid",pid,"contextClosed",!ctx.isActive(),"databaseRetained",true,"verificationSha256",proof.sha256()),GeminiShutdown.NONE);
   }
  }catch(Throwable failure){exit=1;if(claimed&&scope!=null)try{GeminiShutdown.atomic(scope.dir.resolve("failure.json"),Map.of("runId",scope.id,"stage",stage,"errorType",failure.getClass().getSimpleName(),"databaseNotDeleted",true),GeminiShutdown.NONE);}catch(Exception ignored){}System.err.println("MYSQL_INTEGRATION_BLOCKED stage="+stage+"; no raw settings; no reset/drop/retry");}
  if(exit!=0)System.exit(exit);
 }
}
