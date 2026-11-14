import 'dart:convert';
import 'dart:io';

import 'api_transport.dart';
import 'planner_api.dart' show ApiFailure;

// Android behavior: connection 10s, normal response 15s, AI response 90s,
// no redirect following and no retries. Cookie filtering stays in PlannerApi.
class IoTransport implements DeadlineTransport {
  final HttpClient _client = HttpClient()
    ..connectionTimeout = const Duration(seconds: 10);

  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) =>
      sendWithDeadline(method, uri, headers, body, const Duration(seconds: 15));

  @override
  Future<Reply> sendWithDeadline(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
    Duration deadline,
  ) async {
    try {
      return await (() async {
        final req = await _client.openUrl(method, uri);
        req.followRedirects = false;
        headers.forEach(req.headers.set);
        if (body != null) req.write(body);
        final res = await req.close();
        final content = await utf8.decodeStream(res);
        return Reply(res.statusCode, content, res.cookies);
      })().timeout(deadline);
    } catch (_) {
      throw const ApiFailure(
        '서버에 연결하지 못했습니다. 설정한 주소·포트와 서버 실행 상태를 확인하세요. USB는 adb reverse, Wi-Fi는 같은 네트워크·PC 주소·승인된 방화벽 규칙을 확인하세요. 자동 재시도하지 않습니다.',
      );
    }
  }

  @override
  void close() => _client.close(force: true);
}
