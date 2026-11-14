package ai_study_planner.publiclocal;

import ai_study_planner.AiStudyPlannerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("public-local")
@SpringBootTest(classes=AiStudyPlannerApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:public_local_check;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:schema-public.sql"})
class PublicLocalTests {
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void auth(DynamicPropertyRegistry r) {
        r.add("planner.auth.username",()->"synthetic-owner");
        r.add("planner.auth.password-hash",()->new BCryptPasswordEncoder(12).encode(java.util.UUID.randomUUID().toString()));
    }
    @Test void emptyH2CreatesOnlyOwnerAndReinitializationPreservesData() throws Exception {
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM study_logs",Integer.class));
        new PublicLocalBootstrap().initializePublicOwner(jdbc).run(null);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
    }
    @Test void disabledGateRejectsAllProviderRoutesBeforeControllers() throws Exception {
        for(String path:java.util.List.of("/llm-coaching","/study-log-summary","/gpt-weekly-coaching","/study-question")) {
            var request=new MockHttpServletRequest("POST",path);request.setServletPath(path);
            var response=new MockHttpServletResponse();
            new PublicAiGate(false).doFilter(request,response,(q,s)->fail("Provider controller must not execute"));
            assertEquals(503,response.getStatus());assertTrue(response.getContentAsString().contains("not_called"));
        }
    }
    @Test void disabledGateStillAllowsStudyHistoryAndBasicManagement() throws Exception {
        for(String path:java.util.List.of("/goals","/subjects","/study-logs","/study-question")) {
            var request=new MockHttpServletRequest("GET",path);request.setServletPath(path);
            var response=new MockHttpServletResponse();var called=new java.util.concurrent.atomic.AtomicBoolean();
            new PublicAiGate(false).doFilter(request,response,(q,s)->called.set(true));assertTrue(called.get());
        }
    }
}
