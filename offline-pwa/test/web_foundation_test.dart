import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/browser_request_policy.dart';
import 'package:ai_study_planner_mobile/main.dart';
import 'package:ai_study_planner_mobile/plan_calendar.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/platform_services_io.dart'
    if (dart.library.js_interop) 'package:ai_study_planner_mobile/platform_services_web.dart'
    as platform;
import 'package:ai_study_planner_mobile/study_record_entry.dart';
import 'package:ai_study_planner_mobile/study_request_store_web.dart';
import 'package:ai_study_planner_mobile/web_mock_transport.dart';

class BrowserSessionMock implements Transport, BrowserManagedSession {
  final calls = <(String, Uri, Map<String, String>)>[];
  int tokens = 0;
  @override
  Future<Reply> send(String m, Uri u, Map<String, String> h, String? b) async {
    calls.add((m, u, Map.of(h)));
    return Reply(
      200,
      jsonEncode(switch (u.path) {
        '/auth/csrf' => {
          'headerName': 'X-CSRF-TOKEN',
          'token': 'token-${++tokens}',
        },
        '/auth/login' || '/auth/logout' => {'message': 'ok'},
        '/auth/me' => {'authenticated': true, 'userId': 1},
        _ => <Object>[],
      }),
    );
  }

  @override
  void close() {}
}

void main() {
  test(
    'platform selection keeps Android and browser implementations separate',
    () {
      final transport = platform.createTransport();
      final store = platform.createRequestStore();
      expect(transport is BrowserManagedSession, kIsWeb);
      expect(
        store.runtimeType.toString(),
        kIsWeb ? 'WebStudyRequestStore' : 'AndroidStudyRequestStore',
      );
      transport.close();
    },
  );

  test(
    'browser policy blocks foreign origins, HTTP, cookies and costly GETs',
    () {
      final page = Uri.parse('https://planner.example/ipad/');
      void validate(
        String method,
        String url, [
        Map<String, String> h = const {},
      ]) => BrowserRequestPolicy.validate(page, method, Uri.parse(url), h);
      validate('GET', 'https://planner.example/goals');
      validate('POST', 'https://planner.example/auth/login');
      for (final request in [
        ('GET', 'https://other.example/goals'),
        ('GET', 'http://planner.example/goals'),
        ('GET', 'https://planner.example/llm-coaching'),
        ('GET', 'https://planner.example/study-log-summary'),
        ('POST', 'https://planner.example/study-logs'),
        ('POST', 'https://planner.example/study-reschedule-save'),
        ('POST', 'https://planner.example/study-question'),
      ]) {
        expect(
          () => validate(request.$1, request.$2),
          throwsA(isA<ApiFailure>()),
        );
      }
      for (final name in ['Cookie', 'cookie', 'Set-Cookie', 'Origin']) {
        expect(
          () => validate('GET', 'https://planner.example/goals', {name: 'x'}),
          throwsA(isA<ApiFailure>()),
        );
      }
    },
  );

  test('browser session keeps CSRF rotation without Cookie headers', () async {
    final t = BrowserSessionMock();
    final api = PlannerApi(
      base: Uri.parse('https://planner.example'),
      transport: t,
    );
    await api.login('mock-user', 'mock-password');
    expect(t.calls[1].$3['X-CSRF-TOKEN'], 'token-1');
    await api.logout();
    expect(t.calls.last.$3['X-CSRF-TOKEN'], 'token-3');
    expect(
      t.calls.every((c) => !c.$3.keys.any((k) => k.toLowerCase() == 'cookie')),
      isTrue,
    );
    api.close();
  });

  test(
    'unverified web storage refuses keys and write API does zero requests',
    () async {
      final store = WebStudyRequestStore();
      expect(store.persistent, isFalse);
      expect(await store.pending('scope'), isNull);
      await expectLater(store.reserve('scope'), throwsA(isA<ApiFailure>()));
      final t = BrowserSessionMock();
      final api = PlannerApi(
        base: Uri.parse('https://planner.example'),
        transport: t,
        requestStore: store,
      );
      await expectLater(
        api.saveStudyRecord(
          goalId: 1,
          subjectId: 1,
          date: '2026-11-06',
          minutes: 40,
          understanding: 3,
          focus: 3,
          fatigue: 2,
          content: 'Mock',
        ),
        throwsA(isA<ApiFailure>()),
      );
      await expectLater(
        api.saveReschedule(goalId: 1, date: '2026-11-06'),
        throwsA(isA<ApiFailure>()),
      );
      await expectLater(
        api.generateAi(
          feature: AiFeature.question,
          goalId: 1,
          question: 'Mock',
          confirmed: true,
        ),
        throwsA(isA<ApiFailure>()),
      );
      expect(t.calls, isEmpty);
      api.close();
    },
  );

  testWidgets('reused record entry visibly disables real writes', (t) async {
    final api = createWebMockApi();
    addTearDown(api.close);
    await t.pumpWidget(
      MaterialApp(
        home: StudyRecordEntry(api: api, goalId: 1, goalName: '[Mock] 운영체제 학습'),
      ),
    );
    await t.pumpAndSettle();
    final save = t.widget<FilledButton>(find.byKey(const Key('entrySave')));
    expect(save.onPressed, isNull);
    expect(find.text('웹 저장소 검증 전 · 실제 기록 저장 비활성'), findsOneWidget);
  });

  for (final size in [
    const Size(768, 1024),
    const Size(1180, 820),
    const Size(375, 812),
  ]) {
    testWidgets(
      'web Mock reuses dashboard, calendar, analysis and AI at $size',
      (t) async {
        t.view.physicalSize = size;
        t.view.devicePixelRatio = 1;
        addTearDown(t.view.resetPhysicalSize);
        addTearDown(t.view.resetDevicePixelRatio);
        await t.pumpWidget(const PlannerApp());
        await t.enterText(find.byKey(const Key('loginName')), 'mock');
        await t.enterText(find.byKey(const Key('loginPassword')), 'mock-only');
        await t.tap(find.text('Mock 로그인'));
        await t.pumpAndSettle();
        if (kIsWeb) {
          expect(find.textContaining('모든 일정·기록·응답은 가상'), findsOneWidget);
          await t.tap(find.byType(DropdownButtonFormField<int>));
          await t.pumpAndSettle();
          await t.tap(find.text('[Mock] 운영체제 학습').last);
          await t.pumpAndSettle();
        }
        for (final label in ['학습 계획', '학습 분석', 'AI 코칭', '대시보드']) {
          await t.tap(
            find.descendant(
              of: find.byType(NavigationBar),
              matching: find.text(label),
            ),
          );
          await t.pumpAndSettle();
          expect(t.takeException(), isNull);
          if (kIsWeb && label == '학습 계획') {
            expect(find.byKey(const Key('planMonthCalendar')), findsOneWidget);
          }
        }
        // The calendar's shared date convention remains Seoul time.
        expect(
          planDateKey(seoulToday(DateTime.utc(2026, 11, 5, 15))),
          '2026-11-06',
        );
      },
    );
  }
}
