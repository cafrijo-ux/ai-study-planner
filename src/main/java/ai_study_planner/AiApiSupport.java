package ai_study_planner;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Provider error bodies can contain request data. Only safe, actionable messages leave the server. */
final class AiApiSupport {
    private AiApiSupport() {}
    static String httpError(int status) {
        return switch (status) {
            case 400 -> "AI 요청 형식 또는 모델 설정을 확인해 주세요. (HTTP 400)";
            case 401, 403 -> "AI 인증에 실패했습니다. API 키와 접근 권한을 확인해 주세요. (HTTP " + status + ")";
            case 404 -> "설정한 AI 모델 또는 API 경로를 사용할 수 없습니다. 모델은 자동 교체하지 않습니다. (HTTP 404)";
            case 429 -> "AI 사용량·요금 한도 또는 요청 횟수를 초과했습니다. 크레딧과 한도를 확인한 뒤 다시 시도하세요. (HTTP 429)";
            default -> status >= 500 ? "AI 서버에 일시적인 오류가 있습니다. 잠시 후 다시 시도하세요. (HTTP " + status + ")"
                    : "AI 요청이 실패했습니다. 설정과 서비스 상태를 확인해 주세요. (HTTP " + status + ")";
        };
    }
    static String failure(Exception error) {
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return "AI 요청 처리가 중단되었습니다.";
        }
        if (error instanceof java.net.http.HttpTimeoutException) return "AI 응답 시간이 초과됐습니다. 잠시 후 다시 시도하세요.";
        if (error instanceof com.fasterxml.jackson.core.JsonProcessingException) return "AI 응답 형식을 읽을 수 없습니다. 잠시 후 다시 시도하세요.";
        return "AI 요청을 완료하지 못했습니다. 네트워크와 모델 설정을 확인해 주세요.";
    }
    static Map<String, Object> usage(JsonNode root, String field) {
        Map<String, Object> usage = new LinkedHashMap<>();
        JsonNode node = root.path(field);
        for (String name : new String[]{"input_tokens", "output_tokens", "total_tokens", "promptTokenCount", "candidatesTokenCount", "thoughtsTokenCount", "totalTokenCount"}) {
            if (node.path(name).isIntegralNumber() && node.path(name).asLong() >= 0) usage.put(name, node.path(name).asLong());
        }
        return usage;
    }
}
