import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';

import 'planner_api_test.dart' show FakeTransport, json;

void main() {
  test('out of range ports are rejected for both HTTP and HTTPS', () {
    for (final url in [
      'http://192.168.50.10:0',
      'http://192.168.50.10:65536',
      'https://example.invalid:65536',
    ]) {
      expect(
        () => PlannerApi(
          base: Uri.parse(url),
          transport: FakeTransport(),
          allowDebugLanHttp: true,
        ),
        throwsA(isA<ApiFailure>()),
      );
    }
  });
  test(
    'approved private LAN port preserves USB and excludes protected ports',
    () {
      final transport = FakeTransport();
      expect(
        PlannerApi(
          base: Uri.parse('http://localhost:8080'),
          transport: transport,
        ).debugHttpAllowed,
        true,
      );
      for (final port in [1024, 8103, 12345, 65535]) {
        expect(
          PlannerApi(
            base: Uri.parse('http://192.168.50.10:$port'),
            transport: transport,
            allowDebugLanHttp: true,
          ).debugHttpAllowed,
          true,
        );
      }
      for (final port in [80, 443, 8083, 8084, 8085, 8086, 8099, 8101, 8102]) {
        expect(
          () => PlannerApi(
            base: Uri.parse('http://192.168.50.10:$port'),
            transport: transport,
            allowDebugLanHttp: true,
          ),
          throwsA(isA<ApiFailure>()),
        );
      }
      expect(
        () => PlannerApi(
          base: Uri.parse('http://localhost:8103'),
          transport: transport,
        ),
        throwsA(isA<ApiFailure>()),
      );
      expect(
        () => PlannerApi(
          base: Uri.parse('http://192.168.50.10:8103'),
          transport: transport,
        ),
        throwsA(isA<ApiFailure>()),
      );
    },
  );
  test(
    'release simulation cannot opt into LAN plaintext; HTTPS stays available',
    () {
      expect(
        () => PlannerApi(
          base: Uri.parse('http://192.168.50.10:8103'),
          transport: FakeTransport(),
          allowDebugLanHttp: true,
          debugLoopback: false,
        ),
        throwsA(isA<ApiFailure>()),
      );
      expect(
        PlannerApi(
          base: Uri.parse('https://example.invalid'),
          transport: FakeTransport(),
          debugLoopback: false,
        ).debugHttpAllowed,
        false,
      );
    },
  );
  test(
    'LAN port login retains cookie, fresh CSRF, origin and no redirect',
    () async {
      final t = FakeTransport();
      t.replies.addAll([
        json(
          {'headerName': 'X-CSRF-TOKEN', 'token': 'before'},
          [
            Cookie('JSESSIONID', 'synthetic')
              ..secure = true
              ..path = '/',
          ],
        ),
        json({'ok': true}),
        json({'headerName': 'X-CSRF-TOKEN', 'token': 'after'}),
        json({'authenticated': true}),
        json([
          {'goalId': 1, 'goalName': '가상 A'},
          {'goalId': 2, 'goalName': '가상 B'},
        ]),
      ]);
      final api = PlannerApi(
        base: Uri.parse('http://192.168.50.10:8103'),
        transport: t,
        allowDebugLanHttp: true,
      );
      await api.login('virtual', 'virtual');
      expect(await api.goals(), hasLength(2));
      expect(t.calls[1].$3['Cookie'], 'JSESSIONID=synthetic');
      expect(t.calls[1].$3['X-CSRF-TOKEN'], 'before');
      expect(
        t.calls.every((c) => c.$2.origin == 'http://192.168.50.10:8103'),
        true,
      );
    },
  );
  test('native debug exact-host policy and release plaintext denial remain explicit', () {
    final main = File('android/app/src/main/AndroidManifest.xml')
        .readAsStringSync();
    final debug = File('android/app/src/debug/AndroidManifest.xml')
        .readAsStringSync();
    final gradle = File('android/app/build.gradle.kts').readAsStringSync();
    expect(main, contains('android:usesCleartextTraffic="false"'));
    expect(main, isNot(contains('android:usesCleartextTraffic="true"')));
    expect(
      debug,
      contains('android:networkSecurityConfig="@xml/network_security_config"'),
    );
    expect(gradle, contains('uri.port in 1024..65535'));
    expect(gradle, contains('8083, 8084, 8085, 8086, 8099, 8101, 8102'));
    expect(
      gradle,
      contains('<base-config cleartextTrafficPermitted="false" />'),
    );
    expect(gradle, contains('includeSubdomains="false"'));
    expect(gradle, contains('android.sourceSets.getByName("debug")'));
    expect(gradle, isNot(contains('192.168.200.134')));
  });
}
