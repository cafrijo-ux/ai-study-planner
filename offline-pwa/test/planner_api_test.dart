import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';

class FakeTransport implements Transport {
  final calls = <(String, Uri, Map<String, String>, String?)>[];
  final replies = <Reply>[];
  @override
  Future<Reply> send(String m, Uri u, Map<String, String> h, String? b) async {
    calls.add((m, u, Map.of(h), b));
    return replies.removeAt(0);
  }

  @override
  void close() {}
}

Reply json(Object data, [List<Cookie> cookies = const []]) =>
    Reply(200, jsonEncode(data), cookies);
void main() {
  late FakeTransport t;
  late PlannerApi api;
  setUp(() {
    t = FakeTransport();
    api = PlannerApi(
      base: Uri.parse('http://localhost:8080'),
      transport: t,
      debugLoopback: true,
    );
  });
  test(
    'CSRF form login, rotated cookie and token, verified authentication',
    () async {
      t.replies.addAll([
        json(
          {'headerName': 'X-CSRF-TOKEN', 'token': 'before'},
          [
            Cookie('JSESSIONID', 'first')
              ..secure = true
              ..path = '/',
          ],
        ),
        json(
          {'message': 'ok'},
          [
            Cookie('JSESSIONID', 'second')
              ..secure = true
              ..path = '/',
          ],
        ),
        json({'headerName': 'X-CSRF-TOKEN', 'token': 'after'}),
        json({'authenticated': true}),
        json({'headerName': 'X-CSRF-TOKEN', 'token': 'logout'}),
        json({'message': 'ok'}),
        json([]),
      ]);
      await api.login('demo', 'a&b=한글');
      expect(t.calls[1].$3['X-CSRF-TOKEN'], 'before');
      expect(Uri.splitQueryString(t.calls[1].$4!)['password'], 'a&b=한글');
      expect(t.calls[2].$3['Cookie'], 'JSESSIONID=second');
      await api.logout();
      expect(t.calls[5].$3['X-CSRF-TOKEN'], 'logout');
      await api.goals();
      expect(t.calls.last.$3.containsKey('Cookie'), false);
    },
  );
  test('bad credentials clear session and do not retry', () async {
    t.replies.addAll([
      json(
        {'headerName': 'X-CSRF-TOKEN', 'token': 't'},
        [Cookie('JSESSIONID', 'x')],
      ),
      Reply(401, '{}'),
    ]);
    await expectLater(api.login('demo', 'wrong'), throwsA(isA<ApiFailure>()));
    expect(t.calls.length, 2);
    t.replies.add(json([]));
    await api.goals();
    expect(t.calls.last.$3.containsKey('Cookie'), false);
  });
  test('goal selection is explicit and cross-goal response rejected', () async {
    t.replies.addAll([
      json([
        {'goalId': 1, 'goalName': '운영체제'},
      ]),
      json({'goalId': 3, 'scope': 'CURRENT_GOAL'}),
    ]);
    expect((await api.goals()).single['goalId'], 1);
    await expectLater(api.summary(1), throwsA(isA<ApiFailure>()));
    expect(t.calls.last.$2.queryParameters, {
      'scope': 'current',
      'goalId': '1',
    });
    expect(t.calls.every((c) => c.$1 == 'GET'), true);
  });
  test('403 and invalid JSON are not treated as successful data', () async {
    t.replies.addAll([Reply(403, '{}'), Reply(200, '<html>error</html>')]);
    await expectLater(api.goals(), throwsA(isA<ApiFailure>()));
    await expectLater(api.goals(), throwsA(isA<ApiFailure>()));
    expect(t.calls.length, 2);
  });
  test('release HTTP, LAN HTTP and embedded credentials are blocked', () {
    for (final url in [
      'http://192.168.0.1:8080',
      'https://user:password@example.com',
    ]) {
      expect(
        () =>
            PlannerApi(base: Uri.parse(url), transport: t, debugLoopback: true),
        throwsA(isA<ApiFailure>()),
      );
    }
    expect(
      () => PlannerApi(
        base: Uri.parse('http://localhost:8080'),
        transport: t,
        debugLoopback: false,
      ),
      throwsA(isA<ApiFailure>()),
    );
  });
}
