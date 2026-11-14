import 'session_cookie_io.dart'
    if (dart.library.js_interop) 'session_cookie_web.dart';
export 'session_cookie_io.dart'
    if (dart.library.js_interop) 'session_cookie_web.dart';

class Reply {
  Reply(this.status, this.body, [this.cookies = const []]);
  final int status;
  final String body;
  final List<SessionCookie> cookies;
}

abstract class Transport {
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  );
  void close();
}

// Optional capability: existing Mock transports keep the original interface.
abstract interface class DeadlineTransport implements Transport {
  Future<Reply> sendWithDeadline(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
    Duration deadline,
  );
}

abstract interface class BrowserManagedSession {}

abstract interface class MockDataTransport {}
