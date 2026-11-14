package review.mysql;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.*;
import java.sql.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import review.mobile.GeminiShutdown;

/** Profile-only, allowlisted diagnostics. Never persists messages, SQL, inputs or raw streams. */
public final class DualLedgerDiagnostics {
 public static final Set<String> STEPS=Set.of("LOGIN","FIRST_LOG","STUDY_REPLAY","STUDY_CONFLICT","CONCURRENT_STUDY","ROLLBACK","DAILY","DAILY_REPLAY","SUMMARY","RESPONSE_STORE_FAILURE","GET_RECOVERY","SUMMARY_LIMIT","SERVICE_RECREATION","UNCERTAIN","CALENDAR","RULE_PREVIEW","PRESERVATION","LOGOUT","RESULT_PUBLICATION");
 static final Set<String> API_CODES=Set.of("IDEMPOTENCY_STORAGE_UNAVAILABLE","IDEMPOTENCY_TRANSACTION_FAILED","IDEMPOTENCY_BUSY","IDEMPOTENCY_CONFLICT","IDEMPOTENCY_IN_PROGRESS","AI_LEDGER_UNAVAILABLE","AI_LOOKUP_UNAVAILABLE","AI_REQUEST_GOAL","AI_ALREADY_DISPATCHED","AI_POLICY_UNCONFIGURED","AI_DAILY_LIMIT","AI_IN_PROGRESS","AI_REQUEST_CONFLICT","AUTH_REQUIRED");
 public static final class Failure extends IllegalStateException {public final String step,code;public final int expected,actual;public Failure(String step,String code,int expected,int actual){super("DUAL_SCENARIO_CHECK_FAILED");this.step=safeStep(step);this.code=safeCode(code);this.expected=expected;this.actual=actual;}}
 static String safeCode(String value){if(value==null)return "UNCLASSIFIED_FAILURE";if(Set.of("ASSERTION_FAILED","DIAGNOSTIC_PUBLICATION_FAILED","HTTP_EXPECTATION_UNAVAILABLE").contains(value))return value;for(String code:API_CODES)if(("HTTP_EXPECTATION_"+code).equals(value))return value;return "UNCLASSIFIED_FAILURE";}
 private volatile String step="NOT_STARTED";private volatile Map<String,Object> lastSql=Map.of();private volatile Path directory;private final AtomicInteger sequence=new AtomicInteger();
 public static String safeStep(String v){return v!=null&&STEPS.contains(v)?v:"UNCLASSIFIED";}
 public void journal(Path ownedDirectory)throws Exception{directory=Files.createDirectory(ownedDirectory.resolve("scenario-checkpoints"));}
 public void enter(String value){step=safeStep(value);lastSql=Map.of();event("ENTER");}
 public String step(){return step;}
 public void complete(){event("COMPLETE");}
 void event(String kind){if(directory!=null)try{GeminiShutdown.atomic(directory.resolve(String.format(Locale.ROOT,"%06d.json",sequence.incrementAndGet())),Map.of("step",step,"event",kind),GeminiShutdown.NONE);}catch(Exception e){throw new Failure(step,"DIAGNOSTIC_PUBLICATION_FAILED",0,0);}}
 public void expect(org.springframework.test.web.servlet.MvcResult r,int expected,String check)throws Exception{int actual=r.getResponse().getStatus();if(actual!=expected){String api="UNAVAILABLE";try{String raw=IntegrationRuntime.JSON.readTree(r.getResponse().getContentAsByteArray()).path("code").asText();if(API_CODES.contains(raw))api=raw;}catch(Exception ignored){}throw new Failure(step,"HTTP_EXPECTATION_"+api,expected,actual);}}
 public void sql(Throwable e){var note=sqlNote(e);if(!note.isEmpty())lastSql=note;}
 public static Map<String,Object> sqlNote(Throwable e){Map<String,Object> fallback=Map.of();for(Throwable t:causes(e)){if(t instanceof SQLException q){String state=q.getSQLState();int n=q.getErrorCode();String category=switch(n){case 1142,1143,1227->"SQL_PRIVILEGE";case 1146,1054->"SQL_SCHEMA";case 1062,1451,1452,3819->"SQL_CONSTRAINT";case 1205,1213->"SQL_LOCK";case 1045->"SQL_AUTH";case 1064->"SQL_SYNTAX";default->"SQL_OTHER";};var note=Map.<String,Object>of("jdbcSqlState",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNAVAILABLE","mysqlErrorNumber",n>=0&&n<=65535?n:0,"sqlCategory",category);if(n>0&&n<=65535)return note;if(fallback.isEmpty())fallback=note;}}return fallback;}
 static List<Throwable> causes(Throwable e){var result=new ArrayList<Throwable>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());var queue=new ArrayDeque<Throwable>();if(e!=null)queue.add(e);while(!queue.isEmpty()&&result.size()<32){var t=queue.remove();if(!seen.add(t))continue;result.add(t);if(t.getCause()!=null)queue.add(t.getCause());if(t instanceof SQLException q&&q.getNextException()!=null)queue.add(q.getNextException());}return result;}
 public Map<String,Object> failure(Throwable e){var out=new LinkedHashMap<String,Object>();out.put("scenarioStep",step);out.put("code","UNCLASSIFIED_FAILURE");out.put("exceptionCategory","OTHER");for(Throwable t:causes(e)){if(t instanceof Failure f){if(!f.step.equals("UNCLASSIFIED"))out.put("scenarioStep",f.step);out.put("code",f.code);if(f.expected>0){out.put("expectedHttpStatus",f.expected);out.put("actualHttpStatus",f.actual);}out.put("exceptionCategory","SCENARIO_CHECK");break;}if(t instanceof javax.net.ssl.SSLException)out.put("exceptionCategory","TLS");else if(t instanceof java.util.concurrent.TimeoutException)out.put("exceptionCategory","TIMEOUT");else if(t instanceof java.io.IOException)out.put("exceptionCategory","IO");else if(t instanceof org.springframework.dao.DataAccessException)out.put("exceptionCategory","DATA_ACCESS");}var sql=sqlNote(e);out.putAll(sql.isEmpty()?lastSql:sql);out.put("noRetry",true);out.put("rawOutputPersisted",false);return out;}
 Object jdbc(Object target,Class<?> type){return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,m,args)->{if(m.getDeclaringClass()==Object.class)return switch(m.getName()){case "toString"->"DualLedgerObservedJdbc";case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->throw new UnsupportedOperationException();};try{Object value=m.invoke(target,args);if(value instanceof CallableStatement)return jdbc(value,CallableStatement.class);if(value instanceof PreparedStatement)return jdbc(value,PreparedStatement.class);if(value instanceof Statement)return jdbc(value,Statement.class);if(value instanceof ResultSet)return jdbc(value,ResultSet.class);return value;}catch(InvocationTargetException e){sql(e.getCause());throw e.getCause();}});}
 public DataSource wrap(DataSource source){return new ObservedDataSource(source);}
 final class ObservedDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource implements AutoCloseable {
  ObservedDataSource(DataSource source){super(source);}
  public Connection getConnection()throws SQLException{try{return (Connection)jdbc(super.getConnection(),Connection.class);}catch(SQLException e){sql(e);throw e;}}
  public Connection getConnection(String user,String pass)throws SQLException{try{return (Connection)jdbc(super.getConnection(user,pass),Connection.class);}catch(SQLException e){sql(e);throw e;}}
  public void close()throws Exception{if(getTargetDataSource() instanceof AutoCloseable c)c.close();}
 }
}
