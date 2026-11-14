package review.mysql;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import review.mobile.GeminiShutdown;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstancePolicyTests {
    @TempDir Path temp;
    @Test void db3307NeverFallsBackTo3306OrOtherHost()throws Exception {
        String id=UUID.randomUUID().toString();String url=IntegrationSchema.url(id);
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:3307/"));assertTrue(url.contains("sslMode=VERIFY_IDENTITY"));assertFalse(url.contains("autoReconnect=true"));
        var c=mock(Connection.class);var m=mock(DatabaseMetaData.class);when(c.getMetaData()).thenReturn(m);when(m.getURL()).thenReturn(url.replace(":3307/",":3306/"));when(m.getDriverName()).thenReturn("MySQL Connector/J");
        assertThrows(Exception.class,()->IntegrationSchema.verifyMySql(c,id));verify(c,never()).createStatement();
    }
    @Test void appPortCannotBeEitherMysqlPort(){for(int p:List.of(3306,3307))assertThrows(IllegalArgumentException.class,()->IntegrationSchema.port(p));IntegrationSchema.port(8104);}
    @Test void guardPermitsOnlyLiteralLoopback3307(){var g=new MySqlIntegrationServer.Guard(8104,false);g.checkConnect("127.0.0.1",3307);for(String h:List.of("localhost","::1","192.168.200.134","api.openai.com","generativelanguage.googleapis.com"))assertThrows(SecurityException.class,()->g.checkConnect(h,3307));assertThrows(SecurityException.class,()->g.checkConnect("127.0.0.1",3306));}
    @Test void rootAdminHasNoDefaultConnection(){MySqlInstanceAdmin.run(new String[0]);}
    @Test void serverDatadirAndPortAreIndependentlyVerified()throws Exception {
        var c=mock(Connection.class);var s=mock(Statement.class);var r=mock(ResultSet.class);when(c.createStatement()).thenReturn(s);when(s.executeQuery("SELECT @@port,@@datadir")).thenReturn(r);when(r.next()).thenReturn(true);when(r.getInt(1)).thenReturn(3307);when(r.getString(2)).thenReturn(temp.resolve("data").toString());MySqlInstanceAdmin.verifyIdentity(c,temp);
        when(r.getInt(1)).thenReturn(3306);assertThrows(Exception.class,()->MySqlInstanceAdmin.verifyIdentity(c,temp));when(r.getInt(1)).thenReturn(3307);when(r.getString(2)).thenReturn(temp.resolve("other").toString());assertThrows(Exception.class,()->MySqlInstanceAdmin.verifyIdentity(c,temp));verify(s,never()).executeUpdate(anyString());
    }
    @Test void instanceApprovalJarAndIdentityCannotBeSubstituted()throws Exception {
        String id=UUID.randomUUID().toString();Path dir=temp.resolve("mysql-instance-approvals").resolve(id);Files.createDirectories(dir);byte[] fake="synthetic-not-a-runtime".getBytes();Files.write(dir.resolve("mysql-integration.jar"),fake);
        var a=new LinkedHashMap<String,Object>();a.put("runId",id);a.put("mode",MySqlInstanceAdmin.MODE);a.put("port",3307);a.put("address","127.0.0.1");a.put("jarSha256",GeminiShutdown.sha(fake));GeminiShutdown.atomic(dir.resolve("approval.json"),a,GeminiShutdown.NONE);GeminiShutdown.atomic(dir.resolve("approval-claim.json"),Map.of("runId",id,"sha256",GeminiShutdown.sha(Files.readAllBytes(dir.resolve("approval.json")))),GeminiShutdown.NONE);
        assertEquals(id,MySqlInstanceAdmin.scope(dir).path("runId").asText());Files.writeString(dir.resolve("mysql-integration.jar"),"substituted");assertThrows(Exception.class,()->MySqlInstanceAdmin.scope(dir));assertThrows(Exception.class,()->MySqlInstanceAdmin.scope(temp));
    }
    @Test void malformedOldIntegrationApprovalIsBlockedBeforeSecretsOrJdbc()throws Exception {
        String id=UUID.randomUUID().toString();Path dir=temp.resolve("mysql-integration-approvals").resolve(id);Files.createDirectories(dir);GeminiShutdown.atomic(dir.resolve("approval.json"),Map.of("runId",id,"mode",IntegrationSchema.MODE,"port",8104,"phase","SCENARIO","schema",IntegrationSchema.name(id),"dbHost","127.0.0.1","dbPort",3306),GeminiShutdown.NONE);assertThrows(Exception.class,()->MySqlIntegrationServer.scope(dir));
    }
}
