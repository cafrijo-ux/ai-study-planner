package review.mobile;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 재조정 모드에서만 사용하는 허용 경로. 기존 공부 기록 Gate는 변경하지 않는다. */
public final class IsolatedRescheduleServer {
    public static final String MODE="SYNTHETIC_H2_RESCHEDULE_ONLY";
    public static void main(String[] args){IsolatedStudyLogServer.launch(args,true);}
    public static final class Gate implements Filter {
        private final AtomicBoolean attempted=new AtomicBoolean();
        private volatile IsolatedRescheduleFixture.Seed seed;
        public void open(IsolatedRescheduleFixture.Seed value){if(seed!=null)throw new IllegalStateException("Already opened");seed=Objects.requireNonNull(value);}
        public boolean attempted(){return attempted.get();}
        private static void error(HttpServletResponse r,int status,String message)throws IOException{IsolatedStudyLogServer.Gate.error(r,status,message);}
        @Override public void doFilter(ServletRequest raw,ServletResponse response,FilterChain chain)throws IOException,ServletException{
            var q=(HttpServletRequest)raw;var r=(HttpServletResponse)response;
            var current=seed;if(current==null){error(r,503,"격리 재조정 fixture 준비 중입니다.");return;}
            String method=q.getMethod(),path=q.getRequestURI();
            boolean publicRead=method.equals("GET")&&Set.of("/auth/csrf","/auth/me","/login.html","/login.js","/auth-client.js").contains(path);
            if(publicRead){chain.doFilter(q,r);return;}
            if(q.getUserPrincipal()==null||!"mobile-h2-owner".equals(q.getUserPrincipal().getName())){error(r,401,"가상 테스트 계정으로 로그인하세요.");return;}
            boolean read=method.equals("GET")&&Set.of("/goals","/subjects","/analysis-summary","/study-logs","/study-reschedule-preview","/study-reschedule-saved","/study-plan").contains(path);
            boolean write=method.equals("POST")&&path.equals("/study-reschedule-save");
            if(!read&&!write){error(r,403,"재조정 전용 환경은 다른 저장과 AI 경로를 차단합니다.");return;}
            if(!current.today().equals(LocalDate.now(IsolatedRescheduleFixture.KOREA))){error(r,409,"실행 날짜가 변경됐습니다. 재전송하지 말고 격리 환경을 종료하세요.");return;}
            if(write){
                if(!attempted.compareAndSet(false,true)){error(r,409,"재조정 저장 시도는 최대 1회입니다. GET으로 확인하세요.");return;}
                if(!("goalId="+current.goalA()).equals(q.getQueryString())||q.getInputStream().read()!=-1){error(r,400,"목표 A의 goalId 쿼리와 빈 본문만 허용합니다. 재전송하지 마세요.");return;}
            }else if(!Set.of("/goals","/study-logs").contains(path)){
                String goal=q.getParameter("goalId");
                if(q.getParameterValues("goalId")==null||q.getParameterValues("goalId").length!=1||!(String.valueOf(current.goalA()).equals(goal)||String.valueOf(current.goalB()).equals(goal))){error(r,400,"가상 목표 A/B의 goalId를 명시하세요.");return;}
            }
            chain.doFilter(q,r);
        }
    }
    @Configuration(proxyBeanMethods=false)
    public static class RuntimeConfiguration {
        @Bean Gate isolatedRescheduleGate(){return new Gate();}
        @Bean FilterRegistrationBean<Gate> isolatedRescheduleFilter(Gate gate){
            var bean=new FilterRegistrationBean<>(gate);
            // 세션 인증/CSRF가 먼저 처리된 요청만 1회 쓰기 Gate에 도달한다.
            bean.setOrder(-90);return bean;
        }
    }
}
