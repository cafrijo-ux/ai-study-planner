package ai_study_planner.publiclocal;

import java.time.LocalDateTime;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** New portfolio DB only: create the fixed demo owner without importing personal data. */
@Configuration
@Profile("public-local")
public class PublicLocalBootstrap {
    @Bean ApplicationRunner initializePublicOwner(JdbcTemplate jdbc) {
        return args -> {
            try (var connection = jdbc.getDataSource().getConnection()) {
                if (!connection.getMetaData().getURL().startsWith("jdbc:h2:"))
                    throw new IllegalStateException("public-local requires a dedicated H2 database.");
            }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id=1", Long.class) == 0)
                jdbc.update("INSERT INTO users(user_id,name,email,password,created_at) VALUES(1,?,?,?,?)",
                    "포트폴리오 사용자", "portfolio@example.invalid", "NO_DATABASE_LOGIN", LocalDateTime.now());
        };
    }
}
