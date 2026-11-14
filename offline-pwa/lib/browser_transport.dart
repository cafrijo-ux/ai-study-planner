import 'dart:async';
import 'dart:js_interop';

import 'api_transport.dart';
import 'browser_request_policy.dart';
import 'planner_api.dart' show ApiFailure;

@JS('fetch')
external JSPromise<_Response> _fetch(String url, _RequestOptions options);

@JS('AbortController')
extension type _AbortController._(JSObject _) implements JSObject {
  external factory _AbortController();
  external JSObject get signal;
  external void abort();
}

extension type _RequestOptions._(JSObject _) implements JSObject {
  external factory _RequestOptions({
    required String method,
    required String credentials,
    required String mode,
    required String redirect,
    required String cache,
    required JSObject headers,
    required JSObject signal,
    JSString? body,
  });
}

extension type _Response._(JSObject _) implements JSObject {
  external int get status;
  external JSPromise<JSString> text();
}

/// No Cookie/Set-Cookie access. Only same-origin HTTPS requests are accepted.
/// Fetch is never retried; abort covers the response headers and body deadline.
class BrowserTransport implements DeadlineTransport, BrowserManagedSession {
  final Set<_AbortController> _active = {};
  bool _closed = false;

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
    BrowserRequestPolicy.validate(Uri.base, method, uri, headers);
    if (_closed) throw const ApiFailure('이미 닫힌 웹 연결입니다.');
    final controller = _AbortController();
    _active.add(controller);
    try {
      return await (() async {
        final response = await _fetch(
          uri.toString(),
          _RequestOptions(
            method: method,
            credentials: 'same-origin',
            mode: 'same-origin',
            redirect: 'error',
            cache: 'no-store',
            headers: headers.jsify() as JSObject,
            signal: controller.signal,
            body: body?.toJS,
          ),
        ).toDart;
        final content = (await response.text().toDart).toDart;
        return Reply(response.status, content);
      })().timeout(
        deadline,
        onTimeout: () {
          controller.abort();
          throw const ApiFailure('웹 요청 시간이 초과되었습니다. 자동 재시도하지 않습니다.');
        },
      );
    } on ApiFailure {
      rethrow;
    } catch (_) {
      throw const ApiFailure(
        '웹 서버에 연결하지 못했습니다. HTTPS·인증·연결 상태를 확인하세요. 자동 재시도하지 않습니다.',
      );
    } finally {
      _active.remove(controller);
    }
  }

  @override
  void close() {
    _closed = true;
    for (final controller in _active.toList()) {
      controller.abort();
    }
    _active.clear();
  }
}
