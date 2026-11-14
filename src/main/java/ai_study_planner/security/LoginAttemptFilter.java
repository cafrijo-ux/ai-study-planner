package ai_study_planner.security;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
/** Bounded per-peer window. Forwarded headers are deliberately not trusted. */
public final class LoginAttemptFilter extends OncePerRequestFilter {
    private record Window(long start,int count) {}
    private final Map<String,Window> windows=new HashMap<>();
    private synchronized boolean admit(String peer) {
        long now=System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now-e.getValue().start()>=300_000);
        Window w=windows.get(peer);
        if(w==null){if(windows.size()>=1024)return false;windows.put(peer,new Window(now,1));return true;}
        if(w.count()>=10)return false;
        windows.put(peer,new Window(w.start(),w.count()+1));return true;
    }
    @Override protected void doFilterInternal(HttpServletRequest q,HttpServletResponse s,FilterChain c)throws ServletException,IOException {
        if(q.getMethod().equals("POST") && q.getServletPath().equals("/auth/login") && !admit(q.getRemoteAddr())){
            s.setHeader("Retry-After","300");DemoSecurityConfiguration.reply(s,429,"로그인 시도가 많습니다. 5분 뒤 다시 시도해 주세요.");return;
        }
        c.doFilter(q,s);
    }
}
