package review.connector;
import com.mysql.cj.NativeSession;
import com.mysql.cj.PreparedQuery;
import com.mysql.cj.conf.RuntimeProperty;
import com.mysql.cj.exceptions.PasswordExpiredException;
import com.mysql.cj.jdbc.ClientPreparedStatement;
import com.mysql.cj.jdbc.ConnectionImpl;
import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import java.security.Permission;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Connector/J read-only decision code, mocked NativeSession, no sockets. */
@SuppressWarnings({"removal","unchecked"})
class ExpiredRootDriverTests {
    static boolean finished;
    @BeforeAll static void noNetwork(){System.setSecurityManager(new SecurityManager(){
        public void checkPermission(Permission p){if(p.getName().equals("setSecurityManager")&&!finished)throw new SecurityException("Guard retained");}
        public void checkConnect(String h,int p){throw new SecurityException("OFFLINE_DRIVER_PROBE");}
        public void checkConnect(String h,int p,Object c){checkConnect(h,p);}
        public void checkListen(int p){throw new SecurityException("NO_SERVER");}
    });}
    @AfterAll static void releaseTestWorker(){finished=true;System.setSecurityManager(null);}
    static void field(Object target,String name,Object value)throws Exception{
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{var f=type.getDeclaredField(name);f.setAccessible(true);f.set(target,value);return;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    record Probe(ConnectionImpl connection,NativeSession session,ClientPreparedStatement statement){}
    Probe probe(boolean localState)throws Exception{
        var c=mock(ConnectionImpl.class,CALLS_REAL_METHODS);var session=mock(NativeSession.class,RETURNS_DEEP_STUBS);
        field(c,"session",session);var local=mock(RuntimeProperty.class);when(local.getValue()).thenReturn(localState);field(c,"useLocalSessionState",local);
        var propagates=mock(RuntimeProperty.class);when(propagates.getValue()).thenReturn(true);field(c,"readOnlyPropagatesToServer",propagates);
        doReturn(true).when(c).versionMeetsMinimum(anyInt(),anyInt(),anyInt());doReturn(new ReentrantLock()).when(c).getConnectionLock();
        when(session.queryServerVariable("@@session.transaction_read_only")).thenThrow(new PasswordExpiredException("SYNTHETIC_EXPIRED"));
        var ps=mock(ClientPreparedStatement.class,CALLS_REAL_METHODS);var q=mock(PreparedQuery.class);when(q.getOriginalSql()).thenReturn("ALTER USER 'root'@'localhost' IDENTIFIED BY ?");
        field(ps,"connection",c);field(ps,"session",session);field(ps,"query",q);return new Probe(c,session,ps);
    }
    boolean check(Probe probe)throws Exception{var m=ClientPreparedStatement.class.getDeclaredMethod("checkReadOnlySafeStatement");m.setAccessible(true);return (boolean)m.invoke(probe.statement());}
    @Test void defaultsTriggerForbiddenSelectBeforeAlterUser()throws Exception{
        var p=probe(false);var e=assertThrows(InvocationTargetException.class,()->check(p));assertInstanceOf(java.sql.SQLException.class,e.getCause());verify(p.session(),times(1)).queryServerVariable("@@session.transaction_read_only");
    }
    @Test void localBootstrapFlagAllowsAlterDecisionWithoutServerSelect()throws Exception{
        var p=probe(true);assertTrue(check(p));verify(p.session(),never()).queryServerVariable(anyString());
    }
    @Test void genuineReadOnlyClientStateStillRejectsWriteDecision()throws Exception{
        var p=probe(true);field(p.connection(),"readOnly",true);assertFalse(check(p));verify(p.session(),never()).queryServerVariable(anyString());
    }
}
