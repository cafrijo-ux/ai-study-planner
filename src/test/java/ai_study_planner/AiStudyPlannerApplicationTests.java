package ai_study_planner;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.UUID;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:planner_context_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.sql.init.mode=never",
    "gpt.weekly.enabled=false",
    "gpt.weekly.api-key=none",
    "gemini.api.key=none",
    "llm.api.key=none",
    "llm.demo-mode=true"
})
class AiStudyPlannerApplicationTests {
    @org.springframework.beans.factory.annotation.Autowired
    javax.sql.DataSource dataSource;

    @Test
    void contextUsesOnlyInMemoryH2() throws Exception {
        try (var connection = dataSource.getConnection()) {
            org.junit.jupiter.api.Assertions.assertTrue(connection.getMetaData().getURL()
                    .startsWith("jdbc:h2:mem:planner_context_test"));
        }
    }

    // 테스트 실행 중에만 사용하는 임시 BCrypt 해시
    private static final String TEST_PASSWORD_HASH =
            new BCryptPasswordEncoder(12)
                    .encode(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void configureTestCredentials(DynamicPropertyRegistry registry) {
        registry.add("planner.auth.username",
                () -> "context-test-owner");

        registry.add("planner.auth.password-hash",
                () -> TEST_PASSWORD_HASH);
    }

	@Test
	void contextLoads() {
	}

}
