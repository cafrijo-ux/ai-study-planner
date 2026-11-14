package review.dual;
import review.mysql.*;
import review.mobile.GeminiShutdown;
import java.nio.file.*;
import java.util.*;
/** Dedicated test JVM: production Guard stays installed until exit; no HTTP or MySQL driver. */
@SuppressWarnings("removal") public final class DualLedgerGuardProbe {
 public static void main(String[] args)throws Exception{
  try{Class.forName("com.mysql.cj.jdbc.Driver");throw new IllegalStateException("MySQL driver forbidden");}catch(ClassNotFoundException expected){}
  String id=UUID.randomUUID().toString(),url="jdbc:h2:mem:dual_"+id+";MODE=MySQL;DB_CLOSE_DELAY=-1",password="synthetic-guard-password-only";var bootstrap=DualLedgerProfile.offlineH2(url,id);org.springframework.context.ConfigurableApplicationContext ctx=null;var d=new DualLedgerDiagnostics();Path dir=Files.createDirectories(Path.of("build/reports/dual-ledger-scenario-failure/guard-probe",id));
  try{System.setSecurityManager(new MySqlIntegrationServer.Guard(8105,false));var p=DualLedgerServer.offlineProperties(id,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode(password));p.put("spring.datasource.url",url);ctx=DualLedgerServer.context(p,false);d=ctx.getBean(DualLedgerDiagnostics.class);d.journal(dir);var f=DualLedgerRuntime.seed(ctx);var wire=DualLedgerRuntime.install(ctx);var gate=ctx.getBean(DualLedgerRuntime.Gate.class);gate.open(f);var proof=new DualLedgerScenario(ctx,id,f,wire,gate).run(password);GeminiShutdown.atomic(dir.resolve("result.json"),proof,GeminiShutdown.NONE);System.out.println("GUARDED_H2_SCENARIO_PASSED; HTTP=0; MYSQL=0; REAL_AI=0");}
  catch(Throwable e){GeminiShutdown.atomic(dir.resolve("failure.json"),d.failure(e),GeminiShutdown.NONE);System.err.println("GUARDED_H2_FAILED step="+d.step()+" code="+d.failure(e).get("code"));System.exit(1);}
  finally{if(ctx!=null)ctx.close();bootstrap.close();try(var c=java.sql.DriverManager.getConnection(url,"sa","");var st=c.createStatement()){st.execute("SHUTDOWN");}}
 }
}
