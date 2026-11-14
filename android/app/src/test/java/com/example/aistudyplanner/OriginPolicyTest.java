package com.example.aistudyplanner;
import org.junit.Test;
import static org.junit.Assert.*;
public class OriginPolicyTest {
    @Test public void blankDoesNotConnect(){assertNull(OriginPolicy.approvedOrigin(""));assertNull(OriginPolicy.approvedOrigin(null));}
    @Test public void httpsOnly(){assertNull(OriginPolicy.approvedOrigin("http://example.com"));assertNull(OriginPolicy.approvedOrigin("javascript:alert(1)"));}
    @Test public void noLocalhostOrIp(){for(String value:new String[]{"https://localhost","https://127.0.0.1","https://[::1]","https://demo.local","https://demo.localhost","https://example.invalid"})assertNull(OriginPolicy.approvedOrigin(value));}
    @Test public void noCredentialsQueryFragmentOrPort(){for(String value:new String[]{"https://user:password@example.com","https://example.com?token=x","https://example.com#x","https://example.com:8081","https://example.com/path","https://example.com/%2F"})assertNull(OriginPolicy.approvedOrigin(value));}
    @Test public void publicHttpsOriginOnly(){assertEquals("https://example.com",OriginPolicy.approvedOrigin("https://example.com/"));assertEquals("https://example.com",OriginPolicy.approvedOrigin("https://example.com:443"));}
}
