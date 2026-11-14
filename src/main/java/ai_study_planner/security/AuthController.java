package ai_study_planner.security;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class AuthController {
    @GetMapping("/auth/csrf") public Map<String,String> csrf(CsrfToken token){
        return Map.of("token",token.getToken(),"headerName",token.getHeaderName());
    }
    @GetMapping("/auth/me") public Map<String,Object> me(){return Map.of("authenticated",true,"userId",1);}
}
