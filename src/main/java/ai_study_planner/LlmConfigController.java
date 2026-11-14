package ai_study_planner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class LlmConfigController {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private AiCostProtection costProtection;

    @Value("${llm.provider:openai}")
    private String provider;

    @Value("${llm.api.key:none}")
    private String apiKey;

    @Value("${llm.model:gpt-4o-mini}")
    private String model;

    @Value("${gemini.api.key:${GEMINI_API_KEY:none}}")
    private String geminiApiKey;

    @Value("${llm.demo-mode:true}")
    private boolean demoMode;

    @GetMapping("/llm-config-check")
    public Map<String, Object> checkLlmConfig() {
        Map<String, Object> result = new HashMap<>();

        result.put("provider", provider);
        result.put("model", model);
        result.put("aiCostProtection",costProtection==null?Map.of("enabled",false,"version",1,"schemaVerified",false):costProtection.capabilities());

        if ("openai".equalsIgnoreCase(provider)) {
            result.put("providerStatus", "OpenAI API 사용 설정입니다.");
        } else if ("gemini".equalsIgnoreCase(provider)) {
            result.put("providerStatus", "Gemini API 사용 설정입니다.");
        } else if ("anthropic".equalsIgnoreCase(provider)) {
            result.put("providerStatus", "Claude API 사용 설정입니다. 현재는 확장 예정 상태입니다.");
        } else {
            result.put("providerStatus", "지원하지 않는 LLM provider입니다.");
        }

        String selectedKey = "gemini".equalsIgnoreCase(provider) ? geminiApiKey : apiKey;
        if (selectedKey == null || selectedKey.isBlank() || selectedKey.equalsIgnoreCase("none")) {
            result.put("apiKeyStatus", "API 키가 아직 설정되지 않았습니다.");
            result.put("ready", false);
        } else {
            result.put("apiKeyStatus", "API 키가 설정되어 있습니다.");
            result.put("ready", !demoMode && ("gemini".equalsIgnoreCase(provider) || "openai".equalsIgnoreCase(provider)));
        }

        result.put("demoMode", demoMode);
        result.put("message", "설정 존재 여부만 확인했습니다. 실제 인증·요금 한도·모델 접근은 검증하지 않았습니다.");

        return result;
    }
}
