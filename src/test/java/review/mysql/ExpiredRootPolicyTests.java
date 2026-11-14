package review.mysql;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExpiredRootPolicyTests {
    @TempDir Path temp;
    @Test void sandboxSettingsAreRootOnlyAndCannotWeakenTlsOrReconnect(){
        String id=UUID.randomUUID().toString();String root=MySqlInstanceAdmin.rootBootstrapUrl(id),app=IntegrationSchema.url(id);
        for(String flag:List.of("disconnectOnExpiredPasswords=false","useLocalSessionState=true","useServerPrepStmts=false","useCursorFetch=false","forceConnectionTimeZoneToSession=false","sslMode=VERIFY_IDENTITY","autoReconnect=false","allowMultiQueries=false"))assertTrue(root.contains(flag));
        assertTrue(root.startsWith("jdbc:mysql://127.0.0.1:3307/?"));assertFalse(root.contains("readOnlyPropagatesToServer=false"));assertFalse(app.contains("useLocalSessionState"));assertTrue(app.contains("forceConnectionTimeZoneToSession=true"));assertFalse(root.contains(":3306/"));
    }
    @Test void rootChangeIsFirstExplicitSqlAndRemainsParameterized()throws Exception{
        var c=mock(Connection.class);var p=mock(PreparedStatement.class);when(c.prepareStatement(anyString())).thenReturn(p);
        MySqlInstanceAdmin.changeInitialRootPassword(c,"SYNTHETIC_ONLY");var order=inOrder(c,p);order.verify(c).prepareStatement("ALTER USER 'root'@'localhost' IDENTIFIED BY ?");order.verify(p).setString(1,"SYNTHETIC_ONLY");order.verify(p).execute();order.verify(p).close();
        verify(c,never()).getMetaData();verify(c,never()).createStatement();verify(c,never()).isReadOnly();verifyNoMoreInteractions(c,p);
    }
    @ParameterizedTest @ValueSource(ints={1820,1862,1819,1064,1295,1396,1227,2013})
    void vendorCodeAndSqlstateAreSafeAndNoReplay(int number)throws Exception{
        var c=mock(Connection.class);var p=mock(PreparedStatement.class);when(c.prepareStatement(anyString())).thenReturn(p);when(p.execute()).thenThrow(new SQLException("SYNTHETIC_SECRET_DO_NOT_EMIT","HY000",number));
        var e=assertThrows(MySqlInstanceAdmin.RootChangeException.class,()->MySqlInstanceAdmin.changeInitialRootPassword(c,"synthetic"));assertEquals("HY000",e.diagnostic.jdbcSqlState());assertEquals(number,e.diagnostic.mysqlErrorNumber());assertEquals("EXECUTE",e.diagnostic.sqlOperation());assertFalse(e.diagnostic.rootChangeAcknowledged());assertNull(e.getCause());assertFalse(e.toString().contains("SYNTHETIC"));verify(p,times(1)).execute();
        assertEquals(number==1820||number==1862?"PASSWORD_EXPIRED":number==1819?"PASSWORD_POLICY":number==1064?"SQL_SYNTAX":number==1295?"UNSUPPORTED_PREPARE":number==1396?"ACCOUNT_OPERATION":number==1227?"PRIVILEGE":"TRANSPORT_UNCERTAIN",e.diagnostic.sqlErrorCategory());
    }
    @Test void prepareBindAndCloseFailuresAreDistinctAndAckIsNeverInvented()throws Exception{
        for(String operation:List.of("PREPARE","BIND","CLOSE")){
            var c=mock(Connection.class);var p=mock(PreparedStatement.class);when(c.prepareStatement(anyString())).thenReturn(p);var sql=new SQLException("PRIVATE_SECRET","HY000",1820);
            if(operation.equals("PREPARE"))when(c.prepareStatement(anyString())).thenThrow(sql);
            if(operation.equals("BIND"))doThrow(sql).when(p).setString(anyInt(),anyString());
            if(operation.equals("CLOSE"))doThrow(sql).when(p).close();
            var e=assertThrows(MySqlInstanceAdmin.RootChangeException.class,()->MySqlInstanceAdmin.changeInitialRootPassword(c,"synthetic"));assertEquals(operation,e.diagnostic.sqlOperation());assertEquals(operation.equals("CLOSE"),e.diagnostic.rootChangeAcknowledged());verify(p,operation.equals("CLOSE")?times(1):never()).execute();
        }
    }
    @Test void malformedMetadataAndExceptionMessagesCannotEscapeDiagnostics()throws Exception{
        var sql=new SQLException("PRIVATE_PASSWORD_PATH","SECRET_STATE",-1);var d=MySqlInstanceAdmin.sqlDiagnostic(sql,"EXECUTE",false);assertEquals("UNAVAILABLE",d.jdbcSqlState());assertEquals(0,d.mysqlErrorNumber());
        var root=new MySqlInstanceAdmin.RootChangeException(sql,"EXECUTE",false);var phase=new MySqlInstanceAdmin.ProvisionPhaseException("NEW_ROOT_PASSWORD",root);var record=MySqlInstanceAdmin.provisionFailure(UUID.randomUUID().toString(),phase.step,List.of("NEW_ROOT_TLS"),phase);String json=IntegrationRuntime.JSON.writeValueAsString(record);assertFalse(json.contains("PRIVATE"));assertFalse(json.contains("SECRET"));assertEquals(true,record.get("sqlMayHaveChanged"));assertEquals("POSSIBLE_PARTIAL_SQL",phase.sqlMayHaveChanged?"POSSIBLE_PARTIAL_SQL":"WRONG");
    }
    @Test void lateIdentityErrorRetainsAcknowledgedRootChangeAndDoesNotRetry()throws Exception{
        var c=mock(Connection.class);var p=mock(PreparedStatement.class);var s=mock(Statement.class);when(c.prepareStatement(anyString())).thenReturn(p);when(c.createStatement()).thenReturn(s);when(s.executeQuery(anyString())).thenThrow(new SQLException("PRIVATE_PATH","HY000",1820));String id=UUID.randomUUID().toString();var scope=new MySqlIntegrationServer.Scope(temp,id,"SCENARIO",8104,"synthetic",null);var done=new ArrayList<String>();
        var e=assertThrows(MySqlInstanceAdmin.ProvisionPhaseException.class,()->MySqlInstanceAdmin.provisionDatabase(temp,scope,UUID.randomUUID().toString(),"synthetic-store","initial","next","app",(u,props)->c,done));assertEquals("INSTANCE_IDENTITY",e.step);assertTrue(done.contains("NEW_ROOT_PASSWORD"));var r=MySqlInstanceAdmin.provisionFailure(UUID.randomUUID().toString(),e.step,done,e);assertEquals(true,r.get("rootChangeAcknowledged"));assertEquals(1820,r.get("mysqlErrorNumber"));verify(p,times(1)).execute();var order=inOrder(p,s);order.verify(p).execute();order.verify(s).executeQuery("SELECT @@port,@@datadir");
    }
    @Test void nestedAndNextSqlExceptionsKeepOnlySafeVendorMetadata(){
        var inner=new SQLException("SYNTHETIC_PRIVATE_DETAIL","HY000",1820);var outer=new SQLException("SYNTHETIC_PRIVATE_WRAPPER","08001",0,inner);assertEquals(1820,MySqlInstanceAdmin.sqlDiagnostic(outer,"EXECUTE",false).mysqlErrorNumber());
        var chain=new SQLException("SYNTHETIC_PRIVATE_WRAPPER","08001",0);chain.setNextException(new SQLException("PRIVATE_POLICY","HY000",1819));assertEquals("PASSWORD_POLICY",MySqlInstanceAdmin.sqlDiagnostic(chain,"EXECUTE",false).sqlErrorCategory());
    }
}
