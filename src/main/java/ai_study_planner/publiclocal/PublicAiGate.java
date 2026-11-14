package ai_study_planner.publiclocal;

import java.io.IOException;
import java.util.Set;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs before controllers, so automatic dashboard refreshes cannot spend credits. */
@Component
@Profile("public-local")
@Order(-200)
public class PublicAiGate extends OncePerRequestFilter {
    private final boolean enabled;
    private static final Set<String> GENERATION = Set.of(
        "/llm-coaching", "/study-log-summary", "/gpt-weekly-coaching", "/study-question");
    public PublicAiGate(@Value("${planner.public.ai-enabled:false}") boolean enabled) { this.enabled=enabled; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        boolean generation = GENERATION.contains(request.getServletPath()) &&
            (!request.getServletPath().equals("/study-question") || request.getMethod().equals("POST"));
        if (!enabled && generation) {
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"공개 데모의 AI 호출은 기본 비활성화되어 있습니다.\",\"responseSource\":\"not_called\"}");
            return;
        }
        chain.doFilter(request,response);
    }
}
