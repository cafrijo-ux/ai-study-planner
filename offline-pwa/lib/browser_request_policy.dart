import 'planner_api.dart' show ApiFailure;

/// Stage 2 is read-only apart from explicit session login/logout.
/// A whitelist also excludes legacy GET endpoints that can spend AI credits.
abstract final class BrowserRequestPolicy {
  static const reads = {
    '/auth/csrf',
    '/auth/me',
    '/goals',
    '/subjects',
    '/analysis-summary',
    '/study-logs',
    '/study-reschedule-saved',
    '/study-reschedule-calendar',
    '/ai-responses',
    '/study-question/capabilities',
    '/llm-config-check',
    '/gpt-weekly-coaching/preview',
    '/ai-generation-policy',
    '/study-questions',
    '/study-log-idempotency',
    '/study-log-requests',
  };
  static void validate(
    Uri page,
    String method,
    Uri target,
    Map<String, String> headers,
  ) {
    if (page.scheme != 'https' ||
        target.scheme != 'https' ||
        target.origin != page.origin ||
        target.userInfo.isNotEmpty ||
        target.fragment.isNotEmpty) {
      throw const ApiFailure('웹 API는 현재 페이지와 같은 origin의 HTTPS만 허용합니다.');
    }
    if (headers.keys.any(
      (h) => {
        'cookie',
        'cookie2',
        'set-cookie',
        'host',
        'origin',
        'referer',
      }.contains(h.toLowerCase()),
    )) {
      throw const ApiFailure('브라우저가 관리하는 보안 헤더는 직접 설정할 수 없습니다.');
    }
    if (!(method == 'GET' && reads.contains(target.path)) &&
        !(method == 'POST' &&
            {'/auth/login', '/auth/logout'}.contains(target.path))) {
      throw const ApiFailure('웹 2단계에서는 실제 기록·계획 저장 및 AI 생성을 사용할 수 없습니다.');
    }
  }
}
