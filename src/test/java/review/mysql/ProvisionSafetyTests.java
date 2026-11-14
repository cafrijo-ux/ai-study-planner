package review.mysql;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.net.ssl.SSLHandshakeException;
import review.mobile.GeminiShutdown;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProvisionSafetyTests {
    @TempDir Path temp;
    MySqlIntegrationServer.Scope target(){String id=UUID.randomUUID().toString();return new MySqlIntegrationServer.Scope(temp,id,"SCENARIO",8104,"synthetic",null);}
    @Test void tlsAuthAndConnectionFailuresAreDistinctAndNeverRetried()throws Exception {
        var t=target();for(SQLException cause:List.of(new SQLException("SYNTHETIC_SECRET","28000"),new SQLException("SYNTHETIC_SECRET","08001",new SSLHandshakeException("PRIVATE_PATH")),new SQLException("SYNTHETIC_SECRET","08001"))){
            int[] calls={0};var completed=new ArrayList<String>();var e=assertThrows(MySqlInstanceAdmin.ProvisionPhaseException.class,()->MySqlInstanceAdmin.provisionDatabase(temp,t,UUID.randomUUID().toString(),"synthetic-store","initial8","next-root","new-account",(url,p)->{calls[0]++;assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:3307/?"));assertTrue(url.contains("sslMode=VERIFY_IDENTITY"));assertFalse(url.contains("autoReconnect=true"));throw cause;},completed));
            assertEquals(1,calls[0]);assertEquals("NEW_ROOT_TLS",e.step);assertFalse(e.sqlMayHaveChanged);assertTrue(completed.isEmpty());assertNull(e.getCause());assertFalse(e.toString().contains("SYNTHETIC"));
            assertEquals(cause.getSQLState().equals("28000")?"PROVISION_AUTH_FAILED":cause.getCause()!=null?"PROVISION_TLS_FAILED":"PROVISION_CONNECTION_FAILED",e.code);
        }
    }
    @Test void rootChangeFailureStopsBeforeSchemaAndIsNotReportedAsRolledBack()throws Exception {
        var c=mock(Connection.class);var ps=mock(PreparedStatement.class);when(c.prepareStatement(startsWith("ALTER USER"))).thenReturn(ps);when(ps.execute()).thenThrow(new SQLException("PRIVATE_SECRET","HY000"));
        var e=assertThrows(MySqlInstanceAdmin.ProvisionPhaseException.class,()->MySqlInstanceAdmin.provisionDatabase(temp,target(),UUID.randomUUID().toString(),"synthetic-store","initial8","next-root","new-account",(u,p)->c,new ArrayList<>()));
        assertEquals("NEW_ROOT_PASSWORD",e.step);assertTrue(e.sqlMayHaveChanged);verify(c,never()).createStatement();verify(ps,times(1)).execute();verify(c).close();
    }
    Connection rootAfterIdentity()throws Exception {
        var c=mock(Connection.class);var ps=mock(PreparedStatement.class);when(c.prepareStatement(startsWith("ALTER USER"))).thenReturn(ps);
        var s=mock(Statement.class);var r=mock(ResultSet.class);when(c.createStatement()).thenReturn(s);when(s.executeQuery("SELECT @@port,@@datadir")).thenReturn(r);when(r.next()).thenReturn(true);when(r.getInt(1)).thenReturn(3307);when(r.getString(2)).thenReturn(temp.resolve("data").toString());return c;
    }
    @Test void schemaFailurePreservesRootCompletionAndStopsBeforeAccount()throws Exception {
        var c=rootAfterIdentity();when(c.createStatement().execute(argThat(sql->sql!=null&&sql.stripLeading().startsWith("CREATE DATABASE")))).thenThrow(new SQLException("PRIVATE_DDL","42000"));var done=new ArrayList<String>();
        var e=assertThrows(MySqlInstanceAdmin.ProvisionPhaseException.class,()->MySqlInstanceAdmin.provisionDatabase(temp,target(),UUID.randomUUID().toString(),"synthetic-store","initial8","next-root","new-account",(u,p)->c,done));
        assertEquals("DEDICATED_SCHEMA_DDL",e.step);assertTrue(e.sqlMayHaveChanged);assertEquals(List.of("NEW_ROOT_TLS","NEW_ROOT_PASSWORD","INSTANCE_IDENTITY"),done);verify(c,never()).prepareStatement(startsWith("CREATE USER"));
    }
    @Test void accountDdlFailureDoesNotGrantOrClaimSuccess()throws Exception {
        var c=rootAfterIdentity();when(c.prepareStatement(startsWith("CREATE USER"))).thenThrow(new SQLException("PRIVATE_ACCOUNT","42000"));var done=new ArrayList<String>();
        var e=assertThrows(MySqlInstanceAdmin.ProvisionPhaseException.class,()->MySqlInstanceAdmin.provisionDatabase(temp,target(),UUID.randomUUID().toString(),"synthetic-store","initial8","next-root","new-account",(u,p)->c,done));
        assertEquals("NEW_MINIMUM_ACCOUNT",e.step);assertTrue(done.contains("DEDICATED_SCHEMA_DDL"));verify(c.createStatement(),never()).execute(argThat(sql->sql!=null&&sql.stripLeading().startsWith("GRANT")));assertFalse(Files.exists(temp.resolve("provisioned.json")));
    }
    @Test void grantFailureCannotProceedToAccountConnection()throws Exception {
        var c=rootAfterIdentity();when(c.prepareStatement(startsWith("CREATE USER"))).thenReturn(mock(PreparedStatement.class));when(c.createStatement().execute(argThat(sql->sql!=null&&sql.stripLeading().startsWith("GRANT")))).thenThrow(new SQLException("PRIVATE_GRANT","42000"));int[] calls={0};var done=new ArrayList<String>();
        var e=assertThrows(MySqlInstanceAdmin.ProvisionPhaseException.class,()->MySqlInstanceAdmin.provisionDatabase(temp,target(),UUID.randomUUID().toString(),"synthetic-store","initial8","next-root","new-account",(u,p)->{calls[0]++;return c;},done));
        assertEquals("MINIMUM_GRANTS",e.step);assertTrue(e.sqlMayHaveChanged);assertEquals(1,calls[0]);assertFalse(done.contains("MINIMUM_GRANTS"));
    }
    @Test void safeFailureRecordFiltersInputAndReportsPartialState()throws Exception {
        var e=new MySqlInstanceAdmin.ProvisionPhaseException("DEDICATED_SCHEMA_DDL",new SQLException("PRIVATE_PASSWORD","42000"));var r=MySqlInstanceAdmin.provisionFailure(UUID.randomUUID().toString(),e.step,List.of("NEW_ROOT_PASSWORD","SECRET_UNKNOWN"),e);String json=IntegrationRuntime.JSON.writeValueAsString(r);
        assertEquals("PROVISION_SCHEMA_DDL_FAILED",r.get("code"));assertEquals(true,r.get("sqlMayHaveChanged"));assertFalse(json.contains("SECRET"));assertFalse(r.containsKey("errorType"));
    }
    @Test void legacyProvisionFailureBlocksInstanceBeforeReadingCredentials()throws Exception {
        Path dir=temp.resolve("mysql-instance-approvals").resolve(UUID.randomUUID().toString());Files.createDirectories(dir);Path f=dir.resolve("phase-failure-provision.json");Files.writeString(f,"{\"stage\":\"PROVISION\"}");String hash=GeminiShutdown.sha(Files.readAllBytes(f));assertThrows(Exception.class,()->MySqlInstanceAdmin.scope(dir));assertEquals(hash,GeminiShutdown.sha(Files.readAllBytes(f)));assertEquals(1,Files.list(dir).count());
    }
    @Test void failedInstanceAlsoBlocksLinkedIntegrationBeforeClaimOrCredentials()throws Exception {
        String instanceId=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();Path instance=temp.resolve("mysql-instance-approvals").resolve(instanceId),dir=temp.resolve("mysql-integration-approvals").resolve(id);Files.createDirectories(instance);Files.createDirectories(dir);
        Files.writeString(instance.resolve("ready.json"),"{}");Files.writeString(instance.resolve("phase-failure-provision.json"),"{\"stage\":\"PROVISION\"}");
        var a=new LinkedHashMap<String,Object>();a.put("runId",id);a.put("mode",IntegrationSchema.MODE);a.put("phase","SCENARIO");a.put("schema",IntegrationSchema.name(id));a.put("port",8104);a.put("dbHost","127.0.0.1");a.put("dbPort",3307);a.put("instanceRunId",instanceId);a.put("instanceDirectory",instance.toString());a.put("instanceReadySha256",GeminiShutdown.sha(Files.readAllBytes(instance.resolve("ready.json"))));GeminiShutdown.atomic(dir.resolve("approval.json"),a,GeminiShutdown.NONE);
        var e=assertThrows(java.io.IOException.class,()->MySqlIntegrationServer.scope(dir));assertEquals("Failed or linked run cannot be reused",e.getMessage());assertFalse(Files.exists(dir.resolve("process-claim.json")));
    }
}
