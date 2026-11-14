package review.dual;
import review.mysql.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class DualLedgerProfileTests {
 final String id=UUID.randomUUID().toString();
 @Test void legacyExactTenAndNewExactTwelve(){assertEquals(10,IntegrationSchema.TABLES.size());assertEquals(12,DualLedgerProfile.TABLES.size());assertFalse(IntegrationSchema.TABLES.contains("ai_generation_requests"));assertTrue(DualLedgerProfile.TABLES.containsAll(IntegrationSchema.TABLES));}
 @Test void schemaAndAccountSeparate(){assertTrue(DualLedgerProfile.name(id).length()<=64);assertNotEquals(IntegrationSchema.name(id),DualLedgerProfile.name(id));assertNotEquals(IntegrationSchema.account(id),DualLedgerProfile.account(id));assertThrows(Exception.class,()->DualLedgerProfile.name("old-invalid"));}
 @Test void onlyDedicatedPortsAndIdentityTls(){String u=DualLedgerProfile.url(id);assertTrue(u.startsWith("jdbc:mysql://127.0.0.1:3307/"));assertTrue(u.contains("sslMode=VERIFY_IDENTITY"));assertFalse(u.contains(":3306/"));DualLedgerProfile.port(8105);for(int p:List.of(8080,8083,8084,8085,8086,8099,8101,8102,8103,8104,3307))assertThrows(Exception.class,()->DualLedgerProfile.port(p));}
 @Test void networkGuardRejectsOtherDbAiDnsAndListeners(){var g=new MySqlIntegrationServer.Guard(8105,false);g.checkConnect("127.0.0.1",3307);for(String host:List.of("localhost","192.168.1.1","generativelanguage.googleapis.com","api.openai.com"))assertThrows(SecurityException.class,()->g.checkConnect(host,3307));assertThrows(SecurityException.class,()->g.checkConnect("127.0.0.1",3306));assertThrows(SecurityException.class,()->g.checkListen(8105));}
 @Test void tableGrantsAreExactNoDeletionDdlGlobalRoles(){String grants=DualLedgerProfile.grants(id);DualLedgerProfile.verifyGrants(DualLedgerProfile.statements(grants),id);for(String evil:List.of("GRANT SELECT ON *.* TO 'x'@'localhost'","GRANT DELETE ON `"+DualLedgerProfile.name(id)+"`.`study_logs` TO 'x'@'localhost'","GRANT `role` TO 'x'@'localhost'","GRANT SELECT ON `"+DualLedgerProfile.name(id)+"`.`other` TO 'x'@'localhost'")){var g=new ArrayList<>(DualLedgerProfile.statements(grants));g.add(evil);assertThrows(Exception.class,()->DualLedgerProfile.verifyGrants(g,id));}assertFalse(grants.contains("GRANT OPTION"));}
 @Test void usersGrantSupportsBothUserRowLocksWithoutDeleteOrLockTables(){
  assertEquals(Set.of("SELECT","INSERT","UPDATE"),DualLedgerProfile.RIGHTS.get("users"));
  assertTrue(DualLedgerProfile.statements(DualLedgerProfile.grants(id)).contains(userGrant(Set.of("SELECT","INSERT","UPDATE"))));
  assertFalse(DualLedgerProfile.RIGHTS.get("users").contains("DELETE"));
  assertFalse(DualLedgerProfile.RIGHTS.get("users").contains("LOCK TABLES"));
 }
 @Test void otherElevenTableGrantsKeepTheirReviewedContract(){
  var actual=new TreeMap<>(DualLedgerProfile.RIGHTS);actual.remove("users");
  var expected=new TreeMap<String,Set<String>>();
  for(String table:List.of("goals","subjects","study_logs","ai_responses","integration_requests"))expected.put(table,Set.of("SELECT","INSERT"));
  expected.put("study_questions",Set.of("SELECT"));expected.put("integration_run_state",Set.of("SELECT","UPDATE"));
  for(String table:List.of("study_saved_plans","study_schedule_settings","study_log_requests","ai_generation_requests"))expected.put(table,Set.of("SELECT","INSERT","UPDATE"));
  assertEquals(expected,actual);
 }
 @Test void generatedGrantsRemainTableScopedAndHaveNoDeletionDdlOrDelegation(){
  var statements=DualLedgerProfile.statements(DualLedgerProfile.grants(id));assertEquals(12,statements.size());
  for(String sql:statements){
   assertTrue(sql.contains(" ON `"+DualLedgerProfile.name(id)+"`.`"));
   for(String forbidden:List.of("DELETE","LOCK TABLES","CREATE","ALTER","DROP","GRANT OPTION","*"))assertFalse(sql.contains(forbidden),forbidden);
  }
  DualLedgerProfile.verifyGrants(statements,id);
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"SELECT","INSERT","UPDATE"})
 void missingUserPrivilegeIsRejected(String missing){
  var rights=new TreeSet<>(Set.of("SELECT","INSERT","UPDATE"));rights.remove(missing);
  assertThrows(IllegalStateException.class,()->DualLedgerProfile.verifyGrants(withUserGrant(userGrant(rights)),id));
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"DELETE","LOCK TABLES","CREATE","ALL PRIVILEGES"})
 void excessiveUserPrivilegeIsRejected(String extra){
  var rights=new TreeSet<>(Set.of("SELECT","INSERT","UPDATE"));rights.add(extra);
  assertThrows(IllegalStateException.class,()->DualLedgerProfile.verifyGrants(withUserGrant(userGrant(rights)),id));
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={" WITH GRANT OPTION"," with grant option"})
 void userGrantOptionRemainsRejected(String suffix){
  String sql=userGrant(Set.of("SELECT","INSERT","UPDATE"));
  assertThrows(IllegalStateException.class,()->DualLedgerProfile.verifyGrants(withUserGrant(sql+suffix),id));
 }
 @Test void schemaWildcardRemainsRejected(){
  String sql=userGrant(Set.of("SELECT","INSERT","UPDATE"));
  assertThrows(IllegalStateException.class,()->DualLedgerProfile.verifyGrants(withUserGrant(sql.replace("`.`users`","`.*")),id));
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({"StudyLogRequestRepository,lockOwner","AiCostRequestRepository,lock"})
 void bothLedgersKeepExclusiveUserRowLockSql(String className,String method)throws Exception{
  var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
  org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(Long.class),org.mockito.ArgumentMatchers.eq(1L))).thenReturn(List.of(1L));
  var constructor=Class.forName("ai_study_planner."+className).getDeclaredConstructor(org.springframework.jdbc.core.JdbcTemplate.class);constructor.setAccessible(true);
  Object repository=constructor.newInstance(jdbc);org.springframework.test.util.ReflectionTestUtils.invokeMethod(repository,method,1L);
  org.mockito.Mockito.verify(jdbc).queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE",Long.class,1L);
  org.mockito.Mockito.verifyNoMoreInteractions(jdbc);
 }
 private String userGrant(Set<String> rights){return "GRANT "+String.join(", ",new TreeSet<>(rights))+" ON `"+DualLedgerProfile.name(id)+"`.`users` TO '"+DualLedgerProfile.account(id)+"'@'localhost'";}
 private List<String> withUserGrant(String replacement){
  var grants=new ArrayList<>(DualLedgerProfile.statements(DualLedgerProfile.grants(id)));
  int index=-1;for(int i=0;i<grants.size();i++)if(grants.get(i).contains("`.`users`"))index=i;
  assertTrue(index>=0);grants.set(index,replacement);return grants;
 }
 @Test void originalDraftsHaveFksChecksBinaryKeysAndInnoDb()throws Exception{for(String f:List.of("study-log-idempotency.mysql.review.sql","ai-cost-protection.mysql.review.sql")){String ddl=DualLedgerProfile.draft(f);assertTrue(ddl.contains("ENGINE=InnoDB"));assertTrue(ddl.contains("ascii_bin"));assertTrue(ddl.contains("PRIMARY KEY"));assertTrue(ddl.contains("FOREIGN KEY"));assertTrue(ddl.contains("CHECK"));}assertFalse(DualLedgerProfile.ddl(id).contains("__APPROVED_SCHEMA__"));}
 @Test void defaultsStayOffOnlyNewExplicitProfileEnables(){var old=review.mobile.IsolatedStudyLogServer.properties(8104,id,"synthetic-hash");assertEquals(false,old.get("planner.study-log.idempotency.enabled"));assertEquals(false,old.get("planner.ai-cost.enabled"));var p=DualLedgerServer.offlineProperties(id,"synthetic-hash");assertEquals(true,p.get("planner.ai-cost.enabled"));assertEquals("none",p.get("spring.jpa.hibernate.ddl-auto"));assertEquals("never",p.get("spring.sql.init.mode"));assertEquals(false,p.get("gpt.weekly.enabled"));}
 @Test void consumedFailedStoppedApprovalsCannotBeReused()throws Exception{Path p=Files.createTempDirectory("dual-unused-");try{for(String f:List.of("start-reserved.json","process-claim.json","launcher-state.json","stopped.json","failure.json","phase-failure-provision.json")){Files.writeString(p.resolve(f),"{}");assertThrows(Exception.class,()->DualLedgerServer.unused(p));Files.delete(p.resolve(f));}DualLedgerServer.unused(p);}finally{Files.delete(p);}}
 @Test void mainDefaultDoesNotConnectOrCreateApproval(){assertDoesNotThrow(()->DualLedgerServer.main(new String[0]));}
 @Test void memoryOnlyHelperRejectsFileOrMySqlBeforeConnection(){assertThrows(Exception.class,()->DualLedgerProfile.offlineH2("jdbc:mysql://127.0.0.1:3307/x",id));assertThrows(Exception.class,()->DualLedgerProfile.offlineH2("jdbc:h2:file:./not-created",id));}
}
