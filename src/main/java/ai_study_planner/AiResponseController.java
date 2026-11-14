package ai_study_planner;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.List;

@RestController
public class AiResponseController {
    private final AiResponseService service;
    public AiResponseController(AiResponseService service){this.service=service;}
    @GetMapping("/ai-responses")
    public ResponseEntity<List<AiResponse>> history(@RequestParam(required=false) Long goalId,
                                                   @RequestParam(required=false) AiResponse.Feature feature) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.history(goalId,feature));
    }
    @GetMapping("/ai-responses/{id}")
    public ResponseEntity<AiResponse> find(@PathVariable long id) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.find(id));
    }
}
