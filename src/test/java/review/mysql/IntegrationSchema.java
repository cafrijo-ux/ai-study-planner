package review.mysql;
import review.mobile.GeminiShutdown;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** All identifiers are generated, not supplied JDBC catalog names. No connection in preparation methods. */
public final class IntegrationSchema {
 public static final String MODE="MYSQL_INTEGRATION_ONLY",PREFIX="ai_study_planner_integration_",OWNER="integration-owner";
 public static final List<String> BUSINESS=List.of("users","goals","subjects","study_logs","study_questions","ai_responses","study_schedule_settings","study_saved_plans");
 public static final List<String> TABLES=new ArrayList<>();static{TABLES.addAll(BUSINESS);TABLES.addAll(List.of("integration_run_state","integration_requests"));}
 public static String id(String value){if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("Canonical UUID required");return value;}
 public static String name(String id){String s=PREFIX+id(id).replace("-","");if(s.length()>64||!s.matches("[a-z0-9_]+"))throw new IllegalArgumentException();return s;}
 public static String account(String id){return "ai_it_"+id(id).replace("-","").substring(0,12);}
 public static void port(int p){if(p<1024||p>65535||Set.of(3306,3307,8080,8083,8084,8085,8086,8099,8101,8102,8103).contains(p))throw new IllegalArgumentException("Dedicated application port, never a DB port");}
 public static final int DB_PORT=3307;
 public static String url(String id){return "jdbc:mysql://127.0.0.1:"+DB_PORT+"/"+name(id)+"?sslMode=VERIFY_IDENTITY&allowMultiQueries=false&allowLoadLocalInfile=false&autoReconnect=false&connectionTimeZone=%2B09:00&forceConnectionTimeZoneToSession=true";}
 public static String tablesSql()throws Exception{try(var in=Objects.requireNonNull(IntegrationSchema.class.getResourceAsStream("/review/mysql/schema.mysql.sql"))){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
 public static String ddl(String id)throws Exception{return "-- APPROVAL REQUIRED. New isolated schema ONLY. Stop on first SQL error; never use --force.\nCREATE DATABASE `"+name(id)+"` CHARACTER SET utf8mb4;\nUSE `"+name(id)+"`;\n"+tablesSql()+"\nINSERT INTO integration_run_state(run_id,state) VALUES('"+id(id)+"','PREPARED');\n";}
 public static String grants(String id){var b=new StringBuilder("-- New dedicated account must already exist; no password or CREATE USER in this file.\n");for(String t:TABLES)b.append("GRANT SELECT, INSERT, UPDATE ON `").append(name(id)).append("`.`").append(t).append("` TO '").append(account(id)).append("'@'localhost';\n");return b.toString();}
 public static void offlineH2(String url,String id)throws Exception{
  if(!url.matches("jdbc:h2:mem:integration_[0-9a-f-]{36};MODE=MySQL;DB_CLOSE_DELAY=-1"))throw new IllegalArgumentException("Memory H2 only");
  try(var c=DriverManager.getConnection(url,"sa","")){if(!c.getMetaData().getDriverName().equals("H2 JDBC Driver"))throw new IllegalStateException();
   for(String sql:tablesSql().replaceAll("(?m)^--.*$","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").split(";"))if(!sql.isBlank())c.createStatement().execute(sql);
   c.createStatement().executeUpdate("INSERT INTO integration_run_state VALUES('"+id(id)+"','PREPARED')");
  }
 }
 public static void grantsSafe(List<String> lines,String id)throws Exception{
  Set<String> seen=new HashSet<>();for(String raw:lines){String s=raw.replace("`","");if(s.contains("WITH GRANT OPTION")||s.startsWith("GRANT PROXY"))throw new IllegalStateException("Delegation forbidden");
   if(s.matches("GRANT USAGE ON \\*\\.\\* TO .*"))continue;
   var m=java.util.regex.Pattern.compile("^GRANT ([A-Z, ]+) ON "+name(id)+"\\.([a-z_]+) TO .*$").matcher(s);
   if(!m.matches()||!TABLES.contains(m.group(2)))throw new IllegalStateException("Unexpected global/schema/role grant");
   var privileges=new HashSet<>(Arrays.asList(m.group(1).split(", ")));if(!privileges.equals(Set.of("SELECT","INSERT","UPDATE")))throw new IllegalStateException("Exact DML privileges required");seen.add(m.group(2));
  }if(!seen.equals(new HashSet<>(TABLES)))throw new IllegalStateException("Required table grants not proven");
 }
 /** Future explicit-approval connection only. No original catalog or business query outside the generated schema. */
 public static void verifyMySql(Connection c,String id)throws Exception{
  if(!c.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:"+DB_PORT+"/"+name(id)+"?")||!c.getMetaData().getDriverName().contains("MySQL"))throw new IllegalStateException("Wrong driver/URL");
  try(var s=c.createStatement();var r=s.executeQuery("SELECT DATABASE(),CURRENT_USER(),CURRENT_ROLE(),VERSION()")){if(!r.next()||!name(id).equals(r.getString(1))||!Set.of(account(id)+"@localhost",account(id)+"@127.0.0.1").contains(r.getString(2))||!"NONE".equals(r.getString(3))||!r.getString(4).startsWith("8."))throw new IllegalStateException("DB/account/roles/version mismatch");}
  try(var s=c.createStatement();var r=s.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")){if(!r.next()||r.getString(2).isBlank())throw new IllegalStateException("TLS required");}
  try(var s=c.createStatement();var r=s.executeQuery("SELECT @@mandatory_roles")){if(!r.next()||!r.getString(1).isBlank())throw new IllegalStateException("Mandatory roles need separate review");}
  var lines=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery("SHOW GRANTS")){while(r.next())lines.add(r.getString(1));}grantsSafe(lines,id);
  var names=new HashSet<String>();try(var p=c.prepareStatement("SELECT table_name,engine,table_type FROM information_schema.tables WHERE table_schema=?")){p.setString(1,name(id));try(var r=p.executeQuery()){while(r.next()){if(!"InnoDB".equals(r.getString(2))||!"BASE TABLE".equals(r.getString(3)))throw new IllegalStateException("Only InnoDB base tables");names.add(r.getString(1));}}}if(!names.equals(new HashSet<>(TABLES)))throw new IllegalStateException("Exact schema tables required");
  for(String[] q:List.of(new String[]{"triggers","trigger_schema"},new String[]{"routines","routine_schema"},new String[]{"events","event_schema"})){try(var p=c.prepareStatement("SELECT COUNT(*) FROM information_schema."+q[0]+" WHERE "+q[1]+"=?")){p.setString(1,name(id));try(var r=p.executeQuery()){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("No stored side effects allowed");}}}
  for(String table:BUSINESS)try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();if(r.getLong(1)!=0)throw new IllegalStateException("Business schema must be empty; no overwrite/reset");}
  requireUnique(c,id,"study_saved_plans",List.of("user_id","goal_id","plan_date"));requireUnique(c,id,"study_schedule_settings",List.of("user_id","schedule_date"));requireUnique(c,id,"integration_requests",List.of("run_id","request_key"));
  for(Class<?> type:List.of(ai_study_planner.User.class,ai_study_planner.Goal.class,ai_study_planner.Subject.class,ai_study_planner.StudyLog.class,ai_study_planner.StudyQuestion.class,ai_study_planner.AiResponse.class)){
   var expected=new HashSet<String>();for(var f:type.getDeclaredFields()){if(java.lang.reflect.Modifier.isStatic(f.getModifiers())||f.isAnnotationPresent(jakarta.persistence.Transient.class))continue;var a=f.getAnnotation(jakarta.persistence.Column.class);expected.add(a!=null&&!a.name().isBlank()?a.name():f.getName().replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT));}
   var actual=new HashSet<String>();try(var r=c.getMetaData().getColumns(name(id),null,type.getAnnotation(jakarta.persistence.Table.class).name(),null)){while(r.next())actual.add(r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));}if(!actual.equals(expected))throw new IllegalStateException("Entity columns differ from reviewed schema");
  }
  for(String table:TABLES)try(var r=c.getMetaData().getImportedKeys(name(id),null,table)){while(r.next())if(!name(id).equals(r.getString("PKTABLE_CAT")))throw new IllegalStateException("Foreign key escapes isolated schema");}
 }
 static void requireUnique(Connection c,String id,String table,List<String> expected)throws Exception{
  var indexes=new HashMap<String,SortedMap<Integer,String>>();try(var r=c.getMetaData().getIndexInfo(name(id),null,table,true,false)){while(r.next()){String n=r.getString("INDEX_NAME"),column=r.getString("COLUMN_NAME");if(n!=null&&column!=null)indexes.computeIfAbsent(n,k->new TreeMap<>()).put(r.getInt("ORDINAL_POSITION"),column.toLowerCase(Locale.ROOT));}}
  if(indexes.values().stream().noneMatch(m->new ArrayList<>(m.values()).equals(expected)))throw new IllegalStateException("Required unique index missing");
 }
 public static void claim(Connection c,String id)throws Exception{try(var p=c.prepareStatement("UPDATE integration_run_state SET state='RUNNING' WHERE run_id=? AND state='PREPARED'")){p.setString(1,id(id));if(p.executeUpdate()!=1)throw new IllegalStateException("Run already claimed; no reset");}}
}
