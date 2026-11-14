import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/main.dart';

import 'planner_api_test.dart' show FakeTransport, json;

class OfflineTransport implements Transport {
  int attempts = 0;
  @override
  Future<Reply> send(String m, Uri u, Map<String, String> h, String? b) async {
    attempts++;
    throw const ApiFailure('서버에 연결하지 못했습니다. Wi-Fi와 서버 주소를 확인하세요.');
  }

  @override
  void close() {}
}

void main() {
  testWidgets(
    'LAN network failure shows login error without retry or success',
    (tester) async {
      final transport = OfflineTransport();
      final api = PlannerApi(
        base: Uri.parse('http://192.168.1.20:8080'),
        transport: transport,
        allowDebugLanHttp: true,
      );
      await tester.pumpWidget(PlannerApp(api: api));
      await tester.enterText(find.byKey(const Key('serverUsername')), 'fake');
      await tester.enterText(find.byKey(const Key('serverPassword')), 'fake');
      await tester.tap(find.text('서버 로그인'));
      await tester.pumpAndSettle();
      expect(find.textContaining('서버에 연결하지 못했습니다'), findsOneWidget);
      expect(find.byType(NavigationBar), findsNothing);
      expect(transport.attempts, 1);
    },
  );
  test(
    'LAN opt-in accepts RFC1918 only in debug; other addresses fail closed',
    () {
      final t = FakeTransport();
      for (final host in [
        '192.168.1.20',
        '10.2.3.4',
        '172.16.1.2',
        '172.31.255.254',
      ]) {
        final api = PlannerApi(
          base: Uri.parse('http://$host:8080'),
          transport: t,
          allowDebugLanHttp: true,
        );
        expect(api.debugHttpAllowed, true);
      }
      for (final url in [
        'http://8.8.8.8:8080',
        'http://0.0.0.0:8080',
        'http://169.254.1.1:8080',
        'http://172.32.1.2:8080',
        'http://192.168.1.20:80',
        'http://pc.local:8080',
        'http://192.168.1.20:8080/path',
        'https:///',
        'http://user:pass@192.168.1.20:8080',
      ]) {
        expect(
          () => PlannerApi(
            base: Uri.parse(url),
            transport: t,
            allowDebugLanHttp: true,
          ),
          throwsA(isA<ApiFailure>()),
        );
      }
      expect(
        () => PlannerApi(
          base: Uri.parse('http://192.168.1.20:8080'),
          transport: t,
          allowDebugLanHttp: true,
          debugLoopback: false,
        ),
        throwsA(isA<ApiFailure>()),
      );
      expect(t.calls, isEmpty);
    },
  );
  test(
    'opt-in LAN login preserves secure session, fresh CSRF and same origin',
    () async {
      final t = FakeTransport();
      t.replies.addAll([
        json(
          {'headerName': 'X-CSRF-TOKEN', 'token': 'before'},
          [
            Cookie('JSESSIONID', 'mock')
              ..secure = true
              ..path = '/',
          ],
        ),
        json({'ok': true}),
        json({'headerName': 'X-CSRF-TOKEN', 'token': 'after'}),
        json({'authenticated': true}),
        json([]),
      ]);
      final api = PlannerApi(
        base: Uri.parse('http://192.168.1.20:8080'),
        transport: t,
        allowDebugLanHttp: true,
      );
      await api.login('fake', 'fake');
      await api.goals();
      expect(t.calls[1].$3['Cookie'], 'JSESSIONID=mock');
      expect(t.calls[1].$3['X-CSRF-TOKEN'], 'before');
      expect(
        t.calls.every((c) => c.$2.origin == 'http://192.168.1.20:8080'),
        true,
      );
    },
  );
  testWidgets('bad address configuration is explicit and not Mock/login', (
    tester,
  ) async {
    await tester.pumpWidget(
      const PlannerApp(configurationError: '주소 설정을 확인하세요.'),
    );
    expect(find.text('서버 연결 설정 오류'), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
  });
}
