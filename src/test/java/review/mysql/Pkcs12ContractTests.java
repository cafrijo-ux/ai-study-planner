package review.mysql;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import review.mobile.GeminiShutdown;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Public JDK CA fixture ONLY. No private key generation, DB, HTTP or failed-run access. */
class Pkcs12ContractTests {
    @TempDir Path temp;
    static X509Certificate root;
    @BeforeAll static void publicCaOnly()throws Exception {
        var roots=KeyStore.getInstance("JKS");
        try(var in=Files.newInputStream(Path.of(System.getProperty("java.home"),"lib/security/cacerts"))){roots.load(in,"changeit".toCharArray());}
        for(var aliases=roots.aliases();aliases.hasMoreElements();){
            String name=aliases.nextElement();var cert=(X509Certificate)roots.getCertificate(name);
            if(!roots.isKeyEntry(name)&&cert.getBasicConstraints()>=0&&cert.getKeyUsage()!=null&&cert.getKeyUsage()[5]&&cert.getPublicKey() instanceof RSAPublicKey rsa&&rsa.getModulus().bitLength()>=3072&&Set.of("SHA256withRSA","SHA384withRSA","SHA512withRSA").contains(cert.getSigAlgName())){
                try{cert.checkValidity();cert.verify(cert.getPublicKey());root=cert;break;}catch(Exception ignored){}
            }
        }
        assertNotNull(root,"Reviewed public CA fixture required; no generated replacement");
        Path fixture=Path.of("build/reports/mysql-pkcs12-review/public-ca.pem");Files.createDirectories(fixture.getParent());
        Files.writeString(fixture,"-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(root.getEncoded())+"\n-----END CERTIFICATE-----\n");
    }
    @ParameterizedTest @ValueSource(strings={"abcdefghijkl","한글 암호 시험!@#$","!@#$%^&*()_+"})
    void exactCaOnlyRoundTripWithUtf8Contract(String raw)throws Exception {
        Path file=temp.resolve("ca-only.p12");char[] password=MySqlIntegrationServer.pkcs12Password(raw);var complete=new ArrayList<String>();
        MySqlInstanceAdmin.createTrustStore(root,password,file,complete);
        var store=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(file)){store.load(in,password);}
        assertEquals(1,store.size());assertTrue(store.isCertificateEntry("isolated-mysql-ca"));assertFalse(store.isKeyEntry("isolated-mysql-ca"));
        assertArrayEquals(root.getEncoded(),store.getCertificate("isolated-mysql-ca").getEncoded());assertArrayEquals(root.getPublicKey().getEncoded(),store.getCertificate("isolated-mysql-ca").getPublicKey().getEncoded());
        assertEquals(List.of("TLS_PKCS12_KEYSTORE","TLS_PKCS12_WRITE","TLS_PKCS12_VALIDATION","TLS_PKCS12_PUBLICATION"),complete);
        Arrays.fill(password,'\0');
    }
    @Test void java17DirectNonAsciiStorePasswordIsUnsupportedButNotTheHistoricalCause()throws Exception {
        var store=KeyStore.getInstance("PKCS12");store.load(null,null);store.setCertificateEntry("ca",root);
        assertThrows(IOException.class,()->store.store(new ByteArrayOutputStream(),"한글합성암호테스트".toCharArray()));
    }
    @Test void wrongPasswordRejectedWithoutPublishingAnotherFile()throws Exception {
        Path p=temp.resolve("ca-only.p12");MySqlInstanceAdmin.createTrustStore(root,MySqlIntegrationServer.pkcs12Password("abcdefghijkl"),p,new ArrayList<>());
        String before=GeminiShutdown.sha(Files.readAllBytes(p));var s=KeyStore.getInstance("PKCS12");
        try(var in=Files.newInputStream(p)){assertThrows(IOException.class,()->s.load(in,MySqlIntegrationServer.pkcs12Password("different-12")));}
        assertEquals(before,GeminiShutdown.sha(Files.readAllBytes(p)));
    }
    static byte[] frame(byte[] bytes,int length)throws Exception{var out=new ByteArrayOutputStream();var data=new DataOutputStream(out);data.writeInt(length);data.write(bytes);return out.toByteArray();}
    @Test void correctUtf8FrameAndEof()throws Exception {
        String raw="한글! synthetic12";byte[] b=raw.getBytes(StandardCharsets.UTF_8);var in=new DataInputStream(new ByteArrayInputStream(frame(b,b.length)));
        assertEquals(raw,MySqlIntegrationServer.secret(in,12));assertEquals(-1,in.read());
    }
    @Test void historicalOneByteFramesFailBeforeCaParsing()throws Exception {
        var out=new ByteArrayOutputStream();var data=new DataOutputStream(out);for(byte b:"abcdefghijkl".getBytes(StandardCharsets.UTF_8)){data.writeInt(1);data.write(b);}
        assertThrows(IOException.class,()->MySqlIntegrationServer.secret(new DataInputStream(new ByteArrayInputStream(out.toByteArray())),12));
    }
    @Test void truncatedOrInvalidUtf8FramesRejected()throws Exception {
        for(byte[] wire:List.of(new byte[]{0,0},frame(new byte[2],12),frame(new byte[]{(byte)0xff},12),frame(new byte[513],513),frame(new byte[0],-1))){
            assertThrows(IOException.class,()->MySqlIntegrationServer.secret(new DataInputStream(new ByteArrayInputStream(wire)),12));
        }
        assertThrows(IOException.class,()->MySqlIntegrationServer.secret(new DataInputStream(new ByteArrayInputStream(frame(new byte[]{(byte)0xff},1))),1));
    }
    @Test void caParseReusesProductionPolicyWithoutAnyPrivateMaterial()throws Exception {
        Files.createDirectories(temp.resolve("tls"));Files.copy(Path.of("build/reports/mysql-pkcs12-review/public-ca.pem"),temp.resolve("tls/ca.pem"));
        assertArrayEquals(root.getEncoded(),MySqlInstanceAdmin.ca(temp).getEncoded());
    }
    @Test void partialWriteAndAccessDeniedAreWriteFailuresWithNoFinal()throws Exception {
        for(boolean denied:List.of(false,true)){
            var store=mock(KeyStore.class);doAnswer(c->{if(denied)throw new AccessDeniedException("SYNTHETIC_PRIVATE_PATH");((OutputStream)c.getArgument(0)).write(1);throw new IOException("SYNTHETIC_SECRET");}).when(store).store(any(OutputStream.class),any(char[].class));
            Path target=temp.resolve("ca-only.p12");var e=assertThrows(MySqlInstanceAdmin.TlsPhaseException.class,()->MySqlInstanceAdmin.publishTrustStore(store,"abcdefghijkl".toCharArray(),target));
            assertEquals("TLS_PKCS12_WRITE",e.step);assertNull(e.getCause());assertFalse(e.toString().contains("SYNTHETIC"));assertFalse(Files.exists(target));
            try(var files=Files.list(temp)){assertEquals(0,files.count());}
        }
    }
    @Test void successfulWriteButInvalidStoreFailsValidationAndNeverPublishes()throws Exception {
        var store=mock(KeyStore.class);doAnswer(c->{((OutputStream)c.getArgument(0)).write(new byte[]{1,2,3});return null;}).when(store).store(any(OutputStream.class),any(char[].class));
        var e=assertThrows(MySqlInstanceAdmin.TlsPhaseException.class,()->MySqlInstanceAdmin.publishTrustStore(store,"abcdefghijkl".toCharArray(),temp.resolve("ca-only.p12")));
        assertEquals("TLS_PKCS12_VALIDATION",e.step);try(var files=Files.list(temp)){assertEquals(0,files.count());}
    }
    @Test void existingTargetCannotBeReplacedAndPublicationFailureDistinct()throws Exception {
        Path target=temp.resolve("ca-only.p12");Files.writeString(target,"preserve-evidence");var done=new ArrayList<String>();
        var e=assertThrows(MySqlInstanceAdmin.TlsPhaseException.class,()->MySqlInstanceAdmin.createTrustStore(root,MySqlIntegrationServer.pkcs12Password("abcdefghijkl"),target,done));
        assertEquals("TLS_PKCS12_PUBLICATION",e.step);assertEquals("preserve-evidence",Files.readString(target));assertTrue(done.contains("TLS_PKCS12_VALIDATION"));assertFalse(done.contains("TLS_PKCS12_PUBLICATION"));
        try(var files=Files.list(temp)){assertEquals(1,files.count());}
    }
    @Test void missingDirectoryFailsWriteAndInvalidCaFailsKeystore()throws Exception {
        var e=assertThrows(MySqlInstanceAdmin.TlsPhaseException.class,()->MySqlInstanceAdmin.createTrustStore(root,"abcdefghijkl".toCharArray(),temp.resolve("absent/ca.p12"),new ArrayList<>()));assertEquals("TLS_PKCS12_WRITE",e.step);
        e=assertThrows(MySqlInstanceAdmin.TlsPhaseException.class,()->MySqlInstanceAdmin.createTrustStore(null,"abcdefghijkl".toCharArray(),temp.resolve("ca.p12"),new ArrayList<>()));assertEquals("TLS_PKCS12_KEYSTORE",e.step);
    }
    @Test void extraCertificateEntryIsRejected()throws Exception {
        var store=KeyStore.getInstance("PKCS12");store.load(null,null);store.setCertificateEntry("isolated-mysql-ca",root);store.setCertificateEntry("extra",root);
        assertThrows(IOException.class,()->MySqlInstanceAdmin.validateExactCa(store,root));
    }
}
