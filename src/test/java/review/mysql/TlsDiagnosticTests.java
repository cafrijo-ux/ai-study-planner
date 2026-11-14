package review.mysql;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TlsDiagnosticTests {
    @TempDir Path temp;
    @Test void failureRecordUsesOnlyKnownStepsAndNeverExceptionInput()throws Exception {
        String id=UUID.randomUUID().toString();var r=MySqlInstanceAdmin.tlsFailure(id,"TLS_PKCS12_CREATION",List.of("TLS_PKCS12_INPUT_VALIDATION","arbitrary-sensitive-value"));String json=IntegrationRuntime.JSON.writeValueAsString(r);
        assertEquals("TLS_PKCS12_CREATION_FAILED",r.get("code"));assertEquals(false,r.get("mysqlStarted"));assertFalse(json.contains("arbitrary-sensitive-value"));assertFalse(json.contains("StackTrace"));assertFalse(r.containsKey("errorType"));assertEquals("INSTANCE_SCOPE",MySqlInstanceAdmin.tlsFailure(id,"untrusted",List.of()).get("failedStep"));
    }
    @Test void partialKeystoreWriteNeverPublishesFinalFile()throws Exception {
        var store=mock(KeyStore.class);doAnswer(call->{((OutputStream)call.getArgument(0)).write(1);throw new IOException("SYNTHETIC_SECRET_DO_NOT_REPORT");}).when(store).store(any(OutputStream.class),any(char[].class));Path target=temp.resolve("ca-only.p12");assertThrows(IOException.class,()->MySqlInstanceAdmin.publishTrustStore(store,"synthetic-only".toCharArray(),target));assertFalse(Files.exists(target));try(var files=Files.list(temp)){assertEquals(0,files.count());}
    }
    @Test void failedRunIsRejectedBeforeReadingApprovalOrTouchingAnything()throws Exception {
        Path dir=temp.resolve("mysql-instance-approvals").resolve(UUID.randomUUID().toString());Files.createDirectories(dir);Path f=dir.resolve("phase-failure-tls.json");Files.writeString(f,"{\"stage\":\"TLS\",\"legacy\":true}");byte[] before=Files.readAllBytes(f);assertThrows(IOException.class,()->MySqlInstanceAdmin.scope(dir));assertArrayEquals(before,Files.readAllBytes(f));try(var files=Files.list(dir)){assertEquals(1,files.count());}
    }
}
