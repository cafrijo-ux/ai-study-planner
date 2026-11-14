package review.mysql;
import review.mobile.GeminiShutdown;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
/** Separate, approval-only provisioning. Never accepts an old integration scope. */
public final class DualLedgerAdmin {
 public static void claimProvision(DualLedgerServer.Scope s)throws Exception{for(String n:List.of("provisioned.json","phase-failure-provision.json","failure.json","stopped.json"))if(Files.exists(s.dir().resolve(n)))throw new IllegalStateException("Provision consumed; preserve evidence");GeminiShutdown.atomic(s.dir().resolve("admin-process-claim.json"),Map.of("runId",s.id(),"mode",DualLedgerProfile.MODE,"pid",ProcessHandle.current().pid(),"approvalSha256",s.hash()),GeminiShutdown.NONE);}
 static void provision(DualLedgerServer.Scope s,String store,String initial,String next,String app,MySqlInstanceAdmin.Connector connector,List<String> done)throws Exception{
  Path instance=Path.of(s.approval().path("instanceDirectory").asText());var p=new Properties();p.setProperty("user","root");p.setProperty("password",initial);p.setProperty("trustCertificateKeyStoreUrl",s.approval().path("trustStore").asText().isBlank()?"":Path.of(s.approval().path("trustStore").asText()).toUri().toString());p.setProperty("trustCertificateKeyStoreType","PKCS12");p.setProperty("trustCertificateKeyStorePassword",new String(MySqlIntegrationServer.pkcs12Password(store)));
  try{
   try(var c=MySqlInstanceAdmin.provisionStep("NEW_ROOT_TLS",done,()->connector.open(MySqlInstanceAdmin.rootBootstrapUrl(s.id()),p))){
    MySqlInstanceAdmin.provisionStep("NEW_ROOT_PASSWORD",done,()->{MySqlInstanceAdmin.changeInitialRootPassword(c,next);return null;});
    MySqlInstanceAdmin.provisionStep("INSTANCE_IDENTITY",done,()->{MySqlInstanceAdmin.verifyIdentity(c,instance);return null;});
    MySqlInstanceAdmin.provisionStep("DEDICATED_SCHEMA_DDL",done,()->{for(String sql:DualLedgerProfile.statements(DualLedgerProfile.ddl(s.id())))try(var q=c.createStatement()){q.execute(sql);}return null;});
    MySqlInstanceAdmin.provisionStep("NEW_MINIMUM_ACCOUNT",done,()->{try(var q=c.prepareStatement("CREATE USER '"+DualLedgerProfile.account(s.id())+"'@'localhost' IDENTIFIED BY ? REQUIRE SSL")){q.setString(1,app);q.execute();}return null;});
    MySqlInstanceAdmin.provisionStep("MINIMUM_GRANTS",done,()->{for(String sql:DualLedgerProfile.statements(DualLedgerProfile.grants(s.id())))try(var q=c.createStatement()){q.execute(sql);}return null;});
   }
   p.setProperty("user",DualLedgerProfile.account(s.id()));p.setProperty("password",app);
   MySqlInstanceAdmin.provisionStep("MINIMUM_ACCOUNT_PREFLIGHT",done,()->{try(var c=connector.open(DualLedgerProfile.url(s.id()),p)){DualLedgerProfile.verifyMySql(c,s.id(),true);MySqlInstanceAdmin.verifyIdentity(c,instance);}return null;});
   MySqlInstanceAdmin.provisionStep("PROVISION_EVIDENCE",done,()->{GeminiShutdown.atomic(s.dir().resolve("provisioned.json"),Map.of("runId",s.id(),"mode",DualLedgerProfile.MODE,"approvalSha256",s.hash(),"schema",DualLedgerProfile.name(s.id()),"dbPort",3307,"tableCount",12,"minimumAccountVerified",true),GeminiShutdown.NONE);return null;});
  }finally{p.clear();}
 }
 static void run(DualLedgerServer.Scope s)throws Exception{
  String stage="INSTANCE_SCOPE";var done=new ArrayList<String>();boolean claimed=false;
  try{
   DualLedgerServer.unused(s.dir());if(s.phase().equals("RECOVERY_READONLY"))throw new IllegalStateException();
   for(Path f:List.of(s.dir().resolve("provision-reserved.json"),Path.of(s.approval().path("instanceDirectory").asText()).resolve("provision-reserved.json"))){var n=GeminiShutdown.validJson(Files.readAllBytes(f));if(!s.id().equals(n.path("integrationRunId").asText())||!DualLedgerProfile.MODE.equals(n.path("mode").asText())||!s.hash().equalsIgnoreCase(n.path("approvalSha256").asText()))throw new IllegalStateException("Separate provision consent required");}
   claimProvision(s);claimed=true;stage="PROVISION_INPUT";String store,initial,next,app;try(var in=new java.io.DataInputStream(System.in)){store=MySqlIntegrationServer.secret(in,12);initial=MySqlIntegrationServer.secret(in,8);next=MySqlIntegrationServer.secret(in,16);app=MySqlIntegrationServer.secret(in,16);if(in.read()!=-1)throw new IllegalStateException();}done.add(stage);
   System.setSecurityManager(new MySqlIntegrationServer.Guard(8105,false));provision(s,store,initial,next,app,DriverManager::getConnection,done);System.out.println("DUAL_PROVISIONED; NEW_SCHEMA_ONLY; REAL_AI=0");
  }catch(Throwable e){if(e instanceof MySqlInstanceAdmin.ProvisionPhaseException f)stage=f.step;if(claimed)GeminiShutdown.atomic(s.dir().resolve("phase-failure-provision.json"),MySqlInstanceAdmin.provisionFailure(s.id(),stage,done,e),GeminiShutdown.NONE);throw new IllegalStateException("DUAL_PROVISION_BLOCKED");}
 }
}
