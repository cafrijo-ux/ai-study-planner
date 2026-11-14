package ai_study_planner.security;

import jakarta.servlet.SessionTrackingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import java.util.Set;

/** One authenticated demo owner maps to existing user_id=1; no users-table migration. */
@Configuration
@EnableWebSecurity
public class DemoSecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService demoUsers(@Value("${planner.auth.username:}") String username,
            @Value("${planner.auth.password-hash:}") String hash) {
        if (username.isBlank() || !hash.matches("\\$2[aby]\\$(1[2-6])\\$[./A-Za-z0-9]{53}"))
            throw new IllegalStateException("Configure a demo username and BCrypt cost 12-16 password hash outside source control.");
        return new InMemoryUserDetailsManager(User.withUsername(username).password(hash).roles("DEMO_OWNER").build());
    }
    @Bean ServletContextInitializer secureSessionCookie(@Value("${planner.auth.cookie-secure:true}") boolean secure) {
        return context -> {
            var cookie=context.getSessionCookieConfig();
            cookie.setHttpOnly(true); cookie.setSecure(secure); cookie.setAttribute("SameSite", "Lax");
            context.setSessionTrackingModes(Set.of(SessionTrackingMode.COOKIE));
            context.setSessionTimeout(30);
        };
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
            .requestMatchers("/login.html", "/auth-client.js", "/login.js", "/auth/csrf", "/.well-known/assetlinks.json").permitAll()
            .requestMatchers(q -> {
                String path = q.getRequestURI().toLowerCase(java.util.Locale.ROOT);
                return path.contains(".backup-") || path.contains(".before-") || path.endsWith(".bak");
            }).denyAll()
            .anyRequest().hasRole("DEMO_OWNER"));
        // Default session-backed CSRF + XOR/BREACH tokens; never disable CSRF.
        // These legacy GET routes may spend provider credits: require a token without changing URLs.
        http.csrf(c -> c.requireCsrfProtectionMatcher(q ->
            org.springframework.security.web.csrf.CsrfFilter.DEFAULT_CSRF_MATCHER.matches(q)
            || Set.of("/llm-coaching", "/study-log-summary").contains(q.getServletPath())));
        http.formLogin(f -> f.loginPage("/login.html").loginProcessingUrl("/auth/login")
            .successHandler((q,s,a)->reply(s,200,"로그인되었습니다."))
            .failureHandler((q,s,e)->reply(s,401,"아이디 또는 비밀번호를 확인해 주세요.")));
        http.logout(l -> l.logoutUrl("/auth/logout").invalidateHttpSession(true).clearAuthentication(true)
            .deleteCookies("JSESSIONID").logoutSuccessHandler((q,s,a)->reply(s,200,"로그아웃되었습니다.")));
        http.exceptionHandling(e -> e.authenticationEntryPoint((q,s,x)->{
            String p=q.getRequestURI();
            if(q.getMethod().equals("GET") && (p.equals("/") || p.endsWith(".html"))) s.sendRedirect("/login.html");
            else reply(s,401,"로그인이 필요합니다.");
        }).accessDeniedHandler((q,s,x)->reply(s,403,"접근 권한 또는 보안 토큰을 확인해 주세요. 입력 내용은 유지됩니다.")));
        http.requestCache(c -> c.disable());
        http.sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()));
        http.headers(h -> h.frameOptions(f -> f.deny())
            .contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"))
            .addHeaderWriter((q,s)->s.setHeader("Referrer-Policy","no-referrer")));
        http.addFilterBefore(new LoginAttemptFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    static void reply(jakarta.servlet.http.HttpServletResponse response,int status,String message) throws java.io.IOException {
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\""+message+"\"}");
    }
}
