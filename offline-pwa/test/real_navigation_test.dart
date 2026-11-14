import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/server_screens.dart';
import 'package:ai_study_planner_mobile/app_design.dart';

class DashboardTransport extends NavTransport {
  final summaryData = <int, Map<String, dynamic>>{};
  final pendingSummaries = <int, Completer<Reply>>{};
  final methods = <String>[];
  Completer<Reply>? pendingGoals;
  List<Map<String, dynamic>>? goalData;
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) {
    methods.add(method);
    if (uri.path == '/goals' && pendingGoals != null) {
      calls.add(uri);
      return pendingGoals!.future;
    }
    if (uri.path == '/goals' && goalData != null) {
      calls.add(uri);
      return Future.value(Reply(200, jsonEncode(goalData)));
    }
    if (uri.path == '/analysis-summary') {
      final id = int.parse(uri.queryParameters['goalId']!);
      if (pendingSummaries[id] case final hold?) {
        calls.add(uri);
        return hold.future;
      }
      if (summaryData[id] case final data?) {
        calls.add(uri);
        return Future.value(Reply(200, jsonEncode(data)));
      }
    }
    return super.send(method, uri, headers, body);
  }
}

Map<String, dynamic> dashboardData(
  int goal, {
  num? minutes = 137,
  num? records = 7,
  num? understanding = 4.2,
  num? focus = 2.8,
}) => {
  'goalId': goal,
  'scope': 'CURRENT_GOAL',
  'goalName': '서버 목표 $goal',
  'recordCount': records,
  'totalStudyMinutes': minutes,
  'averageUnderstanding': understanding,
  'averageFocus': focus,
};

Future<void> dashboardHost(
  WidgetTester t,
  DashboardTransport transport, {
  Brightness brightness = Brightness.dark,
  double scale = 1,
  bool reduced = false,
}) async {
  final api = PlannerApi(
    base: Uri.parse('http://localhost:8080'),
    transport: transport,
    debugLoopback: true,
  );
  addTearDown(api.close);
  await t.pumpWidget(
    MaterialApp(
      theme: AppDesign.theme(brightness),
      home: Builder(
        builder: (context) => MediaQuery(
          data: MediaQuery.of(context).copyWith(
            textScaler: TextScaler.linear(scale),
            disableAnimations: reduced,
          ),
          child: ServerDashboard(api: api),
        ),
      ),
    ),
  );
  await t.pump();
}

class NavTransport implements Transport {
  final calls = <Uri>[];
  String? failPath;
  int failure = 401;
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    calls.add(uri);
    if (uri.path == '/study-reschedule-calendar') return Reply(404, '{}');
    if (uri.path == failPath) return Reply(failure, '{}');
    if (method != 'GET') {
      expect(['/auth/login', '/auth/logout'], contains(uri.path));
    }
    final id = int.tryParse(uri.queryParameters['goalId'] ?? '') ?? 1;
    final data = switch (uri.path) {
      '/auth/csrf' => {'headerName': 'X-CSRF-TOKEN', 'token': 'test'},
      '/auth/login' || '/auth/logout' => {'message': 'ok'},
      '/auth/me' => {'authenticated': true, 'userId': 1},
      '/goals' => [
        {'goalId': 1, 'goalName': '목표 하나'},
        {'goalId': 3, 'goalName': '목표 셋'},
      ],
      '/analysis-summary' => {
        'goalId': id,
        'scope': 'CURRENT_GOAL',
        'goalName': '목표 $id',
        'recordCount': id == 1 ? 1 : 0,
        'totalStudyMinutes': id == 1 ? 40 : 0,
        'averageFocus': 3,
        'averageUnderstanding': 3,
      },
      '/study-reschedule-saved' =>
        id == 3
            ? {'saved': false}
            : {
                'saved': true,
                'goalId': id,
                'scheduleDate': uri.queryParameters['date'],
                'planStatus': 'FINAL',
                'studyMinutes': 100,
                'carryoverMinutes': 9,
                'newStudyMinutes': 91,
                'timeSlots': [],
              },
      '/subjects' =>
        id == 3
            ? []
            : [
                {
                  'goalId': 1,
                  'subjectId': 1,
                  'userId': 1,
                  'subjectName': '운영체제',
                },
              ],
      '/study-logs' => [
        {
          'logId': 1,
          'subjectId': 1,
          'userId': 1,
          'studyDate': '2026-10-19',
          'studyMinutes': 40,
          'focusScore': 3,
          'understandingScore': 3,
        },
      ],
      '/ai-responses' =>
        id == 3
            ? []
            : [
                {
                  'responseId': 1,
                  'goalId': 1,
                  'feature': uri.queryParameters['feature'],
                  'actualProvider': 'gemini',
                  'model': 'gemini-model',
                  'responseSource': 'PROVIDER',
                  'responseBody': '저장 답변 하나',
                  'responseComplete': true,
                },
              ],
      _ => throw StateError('Unexpected route ${uri.path}'),
    };
    return Reply(200, jsonEncode(data));
  }

  @override
  void close() {}
}

Future<NavTransport> start(WidgetTester t) async {
  final transport = NavTransport();
  final api = PlannerApi(
    base: Uri.parse('http://localhost:8080'),
    transport: transport,
    debugLoopback: true,
  );
  await t.pumpWidget(MaterialApp(home: ServerLogin(api: api)));
  expect(find.byType(NavigationBar), findsNothing);
  await t.enterText(find.byKey(const Key('serverUsername')), 'demo');
  await t.enterText(find.byKey(const Key('serverPassword')), 'fake');
  await t.tap(find.text('서버 로그인'));
  await t.pumpAndSettle();
  expect(find.byType(NavigationBar), findsOneWidget);
  return transport;
}

List<MethodCall> mockAppExit(WidgetTester t) {
  final exits = <MethodCall>[];
  t.binding.defaultBinaryMessenger.setMockMethodCallHandler(
    SystemChannels.platform,
    (call) async {
      if (call.method == 'SystemNavigator.pop') exits.add(call);
      return null;
    },
  );
  addTearDown(() {
    t.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      null,
    );
  });
  return exits;
}

Future<void> predictiveBack(WidgetTester t, String method) async {
  await t.binding.defaultBinaryMessenger.handlePlatformMessage(
    SystemChannels.backGesture.name,
    SystemChannels.backGesture.codec.encodeMethodCall(
      MethodCall(
        method,
        method == 'startBackGesture' || method == 'updateBackGestureProgress'
            ? {
                'touchOffset': [0.0, 100.0],
                'progress': .2,
                'swipeEdge': 0,
              }
            : null,
      ),
    ),
    (_) {},
  );
}

Future<void> tab(WidgetTester t, int i) async {
  await t.tap(find.byKey(Key('realTab$i')));
  await t.pumpAndSettle();
}

Future<void> goal(WidgetTester t, int id) async {
  await tab(t, 0);
  await t.tap(find.byType(DropdownButtonFormField<int>));
  await t.pumpAndSettle();
  await t.tap(find.text(id == 1 ? '목표 하나' : '목표 셋').last);
  await t.pumpAndSettle();
}

void main() {
  testWidgets('login required; unselected tabs do not request; logout', (
    t,
  ) async {
    final transport = await start(t);
    for (var i = 1; i < 4; i++) {
      await tab(t, i);
      expect(find.text('홈에서 목표를 선택하세요.'), findsOneWidget);
    }
    expect(
      transport.calls.where((u) => u.path == '/study-reschedule-saved'),
      isEmpty,
    );
    await tab(t, 0);
    await t.tap(find.byTooltip('서버 로그아웃'));
    await t.pumpAndSettle();
    expect(find.text('서버 로그인'), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);
  });
  testWidgets(
    'real tabs preserve state without repeated GETs and back goes home',
    (t) async {
      final transport = await start(t);
      await goal(t, 1);
      await tab(t, 1);
      expect(find.text('학습 계획 · 실제 조회'), findsOneWidget);
      expect(find.textContaining('100분'), findsWidgets);
      await tab(t, 2);
      await t.scrollUntilVisible(
        find.text('공부시간: 40분'),
        180,
        scrollable: find.byType(Scrollable).last,
      );
      expect(find.text('공부시간: 40분'), findsOneWidget);
      await tab(t, 3);
      await t.scrollUntilVisible(
        find.text('저장 답변 하나'),
        180,
        scrollable: find
            .descendant(
              of: find.byKey(const Key('aiHistoryScroll')),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      await t.pumpAndSettle();
      expect(find.text('저장 답변 하나'), findsOneWidget);
      expect(find.text('저장 답변 하나').hitTestable(), findsOneWidget);
      expect(find.textContaining('Mock 예시'), findsNothing);
      final count = transport.calls.length;
      for (var i = 0; i < 4; i++) {
        await tab(t, i);
      }
      expect(transport.calls.length, count);
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      expect(find.text('대시보드 · 실제 조회'), findsOneWidget);
    },
  );
  testWidgets('goal changes discard previous goal results in all tabs', (
    t,
  ) async {
    final transport = await start(t);
    await goal(t, 1);
    for (var i = 1; i < 4; i++) {
      await tab(t, i);
    }
    await goal(t, 3);
    await tab(t, 1);
    expect(find.textContaining('저장 계획이 없습니다.'), findsOneWidget);
    expect(find.textContaining('100분'), findsNothing);
    await tab(t, 2);
    await t.scrollUntilVisible(
      find.text('공부 기록 없음'),
      180,
      scrollable: find.byType(Scrollable).last,
    );
    expect(find.text('공부 기록 없음'), findsOneWidget);
    expect(find.text('공부시간: 40분'), findsNothing);
    await tab(t, 3);
    await t.scrollUntilVisible(
      find.text('저장된 응답 없음'),
      180,
      scrollable: find
          .descendant(
            of: find.byKey(const Key('aiHistoryScroll')),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    expect(find.text('저장된 응답 없음'), findsOneWidget);
    expect(find.text('저장 답변 하나'), findsNothing);
    await goal(t, 1);
    await tab(t, 3);
    await t.scrollUntilVisible(
      find.text('저장 답변 하나'),
      180,
      scrollable: find
          .descendant(
            of: find.byKey(const Key('aiHistoryScroll')),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    await t.pumpAndSettle();
    expect(find.text('저장 답변 하나'), findsOneWidget);
    expect(find.text('저장 답변 하나').hitTestable(), findsOneWidget);
    expect(
      transport.calls
          .where((u) => u.path == '/ai-responses')
          .map((u) => u.queryParameters['goalId']),
      ['1', '3', '1'],
    );
  });
  testWidgets('expired tab returns to login with explanation', (t) async {
    final transport = await start(t);
    await goal(t, 1);
    transport.failPath = '/ai-responses';
    await tab(t, 3);
    expect(find.text('서버 로그인'), findsOneWidget);
    expect(find.textContaining('로그인이 만료됐습니다'), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);
  });
  testWidgets('HTTP failure is not empty plan and explicit retry recovers', (
    t,
  ) async {
    final transport = await start(t);
    await goal(t, 1);
    transport.failPath = '/study-reschedule-saved';
    transport.failure = 500;
    await tab(t, 1);
    expect(find.textContaining('저장 계획이 없습니다.'), findsNothing);
    transport.failPath = null;
    await t.ensureVisible(find.byKey(const Key('storedPlanButton')));
    await t.tap(find.byKey(const Key('storedPlanButton')));
    await t.pumpAndSettle();
    expect(find.textContaining('100분'), findsWidgets);
  });

  testWidgets('dashboard shows exact cumulative values and only score scales', (
    t,
  ) async {
    final transport = DashboardTransport()..summaryData[1] = dashboardData(1);
    final original = jsonEncode(transport.summaryData[1]);
    await dashboardHost(t, transport);
    await t.pumpAndSettle();
    await goal(t, 1);
    expect(find.text('선택 목표 · 목표 하나'), findsOneWidget);
    expect(find.text('137분'), findsOneWidget);
    expect(find.text('7건'), findsOneWidget);
    expect(find.text('4.2 / 5'), findsOneWidget);
    expect(find.text('2.8 / 5'), findsOneWidget);
    expect(find.text('선택 목표 · 누적 기록'), findsOneWidget);
    for (final entry in [('understanding', .84), ('focus', .56)]) {
      final bar = t.widget<LinearProgressIndicator>(
        find.descendant(
          of: find.byKey(Key('dashboard-stat-${entry.$1}')),
          matching: find.byType(LinearProgressIndicator),
        ),
      );
      expect(bar.value, closeTo(entry.$2, .0001));
    }
    for (final id in ['time', 'records']) {
      expect(
        find.descendant(
          of: find.byKey(Key('dashboard-stat-$id')),
          matching: find.byType(LinearProgressIndicator),
        ),
        findsNothing,
      );
    }
    expect(transport.calls.map((u) => u.path), ['/goals', '/analysis-summary']);
    expect(transport.methods.every((m) => m == 'GET'), isTrue);
    expect(jsonEncode(transport.summaryData[1]), original);
  });

  testWidgets('missing summary values remain unknown rather than zero', (
    t,
  ) async {
    final transport = DashboardTransport()
      ..summaryData[1] = dashboardData(
        1,
        minutes: null,
        records: null,
        understanding: null,
        focus: null,
      );
    await dashboardHost(t, transport);
    await t.pumpAndSettle();
    await goal(t, 1);
    expect(find.text('정보 없음'), findsNWidgets(4));
    expect(find.text('0분'), findsNothing);
    expect(find.text('0건'), findsNothing);
    expect(find.byType(LinearProgressIndicator), findsNothing);
    expect(find.textContaining('공부 기록 없음'), findsNothing);
  });

  testWidgets(
    'explicit zero records is an empty state and unmeasured scores stay unknown',
    (t) async {
      final transport = DashboardTransport()
        ..summaryData[1] = dashboardData(
          1,
          minutes: 0,
          records: 0,
          understanding: null,
          focus: null,
        );
      await dashboardHost(t, transport);
      await t.pumpAndSettle();
      await goal(t, 1);
      expect(find.text('0분'), findsOneWidget);
      expect(find.text('0건'), findsOneWidget);
      expect(find.textContaining('공부 기록 없음'), findsOneWidget);
      expect(find.text('정보 없음'), findsNWidgets(2));
      expect(find.byType(LinearProgressIndicator), findsNothing);
    },
  );

  testWidgets(
    'invalid score ranges do not produce percentage or progress bars',
    (t) async {
      final transport = DashboardTransport()
        ..summaryData[1] = dashboardData(1, understanding: 0, focus: 6);
      await dashboardHost(t, transport);
      await t.pumpAndSettle();
      await goal(t, 1);
      expect(find.text('정보 없음'), findsNWidgets(2));
      expect(find.byType(LinearProgressIndicator), findsNothing);
      expect(find.textContaining('%'), findsNothing);
    },
  );

  testWidgets(
    'goals loading empty and error states are distinct and retry remains available',
    (t) async {
      final held = Completer<Reply>();
      final transport = DashboardTransport()..pendingGoals = held;
      await dashboardHost(t, transport);
      expect(find.byType(LinearProgressIndicator), findsOneWidget);
      expect(find.textContaining('등록된 목표가 없습니다'), findsNothing);
      expect(find.byKey(const Key('dashboard-summary')), findsNothing);
      held.complete(Reply(500, '{}'));
      await t.pumpAndSettle();
      expect(find.byType(LinearProgressIndicator), findsNothing);
      expect(find.textContaining('등록된 목표가 없습니다'), findsNothing);
      expect(find.text('조회 다시 시도'), findsOneWidget);
      transport.pendingGoals = null;
      transport.goalData = [];
      await t.ensureVisible(find.text('조회 다시 시도'));
      await t.tap(find.text('조회 다시 시도'));
      await t.pumpAndSettle();
      expect(find.textContaining('등록된 목표가 없습니다'), findsOneWidget);
      expect(find.text('조회 다시 시도'), findsNothing);
    },
  );

  testWidgets(
    'summary failure clears values without being presented as no records',
    (t) async {
      final transport = DashboardTransport();
      await dashboardHost(t, transport);
      await t.pumpAndSettle();
      await goal(t, 1);
      transport.failPath = '/analysis-summary';
      transport.failure = 500;
      t
          .widget<DropdownButtonFormField<int>>(
            find.byType(DropdownButtonFormField<int>),
          )
          .onChanged!(1);
      await t.pumpAndSettle();
      expect(find.text('40분'), findsNothing);
      expect(find.byKey(const Key('dashboard-summary')), findsNothing);
      expect(find.textContaining('공부 기록 없음'), findsNothing);
      transport.failPath = null;
      await t.ensureVisible(find.text('조회 다시 시도'));
      await t.tap(find.text('조회 다시 시도'));
      await t.pumpAndSettle();
      expect(find.text('40분'), findsOneWidget);
    },
  );

  testWidgets('late summary cannot restore prior goal during entry animation', (
    t,
  ) async {
    final transport = DashboardTransport()
      ..summaryData[3] = dashboardData(3, minutes: 83);
    await dashboardHost(t, transport);
    await t.pumpAndSettle();
    await goal(t, 1);
    final choose = t
        .widget<DropdownButtonFormField<int>>(
          find.byType(DropdownButtonFormField<int>),
        )
        .onChanged!;
    final held = Completer<Reply>();
    transport.pendingSummaries[1] = held;
    choose(1);
    await t.pump();
    expect(find.text('40분'), findsNothing);
    expect(find.byKey(const Key('dashboard-summary')), findsNothing);
    choose(3);
    await t.pump();
    await t.pump(const Duration(milliseconds: 90));
    expect(find.text('선택 목표 · 목표 셋'), findsOneWidget);
    expect(find.text('40분'), findsNothing);
    held.complete(Reply(200, jsonEncode(dashboardData(1, minutes: 999))));
    await t.pumpAndSettle();
    expect(find.text('83분'), findsOneWidget);
    expect(find.text('999분'), findsNothing);
    expect(find.text('40분'), findsNothing);
    expect(transport.methods.every((m) => m == 'GET'), isTrue);
  });

  testWidgets(
    'dashboard entry has measurable opacity and position at an intermediate frame',
    (t) async {
      final held = Completer<Reply>();
      final transport = DashboardTransport()..pendingSummaries[1] = held;
      await dashboardHost(t, transport);
      await t.pumpAndSettle();
      t
          .widget<DropdownButtonFormField<int>>(
            find.byType(DropdownButtonFormField<int>),
          )
          .onChanged!(1);
      await t.pump();
      expect(find.byKey(const Key('dashboard-summary')), findsNothing);
      held.complete(Reply(200, jsonEncode(dashboardData(1))));
      await t.pump();
      final summary = find.byKey(const Key('dashboard-summary'));
      final startY = t.getTopLeft(summary).dy;
      final calls = transport.calls.length;
      await t.pump(const Duration(milliseconds: 120));
      final opacity = t
          .widget<Opacity>(
            find.ancestor(of: summary, matching: find.byType(Opacity)).first,
          )
          .opacity;
      expect(opacity, inExclusiveRange(0, 1));
      expect(t.getTopLeft(summary).dy, lessThan(startY));
      await t.pumpAndSettle();
      expect(
        t
            .widget<Opacity>(
              find.ancestor(of: summary, matching: find.byType(Opacity)).first,
            )
            .opacity,
        1,
      );
      expect(transport.calls.length, calls);
    },
  );

  testWidgets(
    'metric press scales at mid-frame and opens existing analysis only on tap',
    (t) async {
      final transport = DashboardTransport();
      await dashboardHost(t, transport);
      await t.pumpAndSettle();
      await goal(t, 1);
      final card = find.byKey(const Key('dashboard-stat-time'));
      await t.ensureVisible(card);
      final calls = transport.calls.length;
      final gesture = await t.startGesture(t.getCenter(card));
      await t.pump(const Duration(milliseconds: 110));
      await t.pump(const Duration(milliseconds: 100));
      final transform = find
          .descendant(
            of: find.byKey(const Key('dashboard-stat-scale-time')),
            matching: find.byType(Transform),
          )
          .first;
      expect(
        t.widget<Transform>(transform).transform.storage[0],
        inExclusiveRange(.98, 1),
      );
      expect(transport.calls.length, calls);
      await gesture.cancel();
      await t.pumpAndSettle();
      expect(t.widget<Transform>(transform).transform.storage[0], 1);
      await t.tap(card);
      await t.pumpAndSettle();
      expect(find.text('학습 분석 · 실제 조회'), findsOneWidget);
      expect(
        t.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex,
        2,
      );
      expect(transport.methods.every((m) => m == 'GET'), isTrue);
    },
  );

  testWidgets(
    'dashboard reduced motion skips custom entry and press transitions',
    (t) async {
      final transport = DashboardTransport();
      await dashboardHost(t, transport, reduced: true);
      await t.pumpAndSettle();
      await goal(t, 1);
      expect(
        t
            .widget<TweenAnimationBuilder<double>>(
              find.byKey(const Key('dashboard-summary-entry')),
            )
            .duration,
        Duration.zero,
      );
      expect(
        t
            .widget<AnimatedScale>(
              find.byKey(const Key('dashboard-stat-scale-time')),
            )
            .duration,
        Duration.zero,
      );
    },
  );

  for (final brightness in Brightness.values) {
    for (final scale in [1.0, 1.6, 2.0]) {
      testWidgets(
        '320dp dashboard ${brightness.name} at text scale $scale keeps all actions reachable',
        (t) async {
          t.view.physicalSize = const Size(320, 800);
          t.view.devicePixelRatio = 1;
          addTearDown(t.view.resetPhysicalSize);
          addTearDown(t.view.resetDevicePixelRatio);
          final transport = DashboardTransport();
          await dashboardHost(
            t,
            transport,
            brightness: brightness,
            scale: scale,
          );
          await t.pumpAndSettle();
          await t.ensureVisible(find.byType(DropdownButtonFormField<int>));
          await t.tap(find.byType(DropdownButtonFormField<int>));
          await t.pumpAndSettle();
          await t.tap(find.text('목표 하나').last);
          await t.pumpAndSettle();
          expect(t.takeException(), isNull);
          for (final key in [
            'openStudyEntry',
            'openRealPlan',
            'openRealAnalysis',
          ]) {
            await t.scrollUntilVisible(
              find.byKey(Key(key)),
              180,
              scrollable: find.byType(Scrollable).first,
            );
            await t.pumpAndSettle();
            expect(find.byKey(Key(key)).hitTestable(), findsOneWidget);
            expect(t.takeException(), isNull);
          }
          await t.scrollUntilVisible(
            find.text('저장된 AI 답변 보기'),
            180,
            scrollable: find.byType(Scrollable).first,
          );
          await t.pumpAndSettle();
          expect(find.text('저장된 AI 답변 보기').hitTestable(), findsOneWidget);
          expect(transport.methods.every((m) => m == 'GET'), isTrue);
        },
      );
    }
  }

  testWidgets(
    'Android home double back requests platform exit once without logout',
    (t) async {
      final exits = mockAppExit(t);
      final transport = await start(t);
      await t.binding.handlePopRoute();
      await t.pump();
      expect(find.text('한 번 더 누르면 앱이 종료됩니다.'), findsOneWidget);
      expect(exits, isEmpty);
      expect(
        ModalRoute.of(t.element(find.text('대시보드 · 실제 조회')))!.popDisposition,
        RoutePopDisposition.doNotPop,
      );
      await t.pump(const Duration(milliseconds: 300));
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      expect(exits.length, 1);
      expect(find.text('대시보드 · 실제 조회'), findsOneWidget);
      expect(find.text('서버 로그인'), findsNothing);
      expect(transport.calls.where((u) => u.path == '/auth/logout'), isEmpty);
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      expect(exits.length, 1);
    },
  );

  testWidgets(
    'home confirmation expires after two seconds and starts again safely',
    (t) async {
      final exits = mockAppExit(t);
      await start(t);
      await t.binding.handlePopRoute();
      await t.pump();
      await t.pump(const Duration(milliseconds: 2100));
      await t.pumpAndSettle();
      await t.binding.handlePopRoute();
      await t.pump();
      expect(exits, isEmpty);
      expect(find.text('한 번 더 누르면 앱이 종료됩니다.'), findsOneWidget);
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      expect(exits.length, 1);
      expect(t.takeException(), isNull);
    },
  );

  for (final index in [1, 2, 3]) {
    testWidgets(
      'back from tab $index returns home without counting as first exit press',
      (t) async {
        final exits = mockAppExit(t);
        await start(t);
        await goal(t, 1);
        await tab(t, index);
        await t.binding.handlePopRoute();
        await t.pumpAndSettle();
        expect(
          t.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex,
          0,
        );
        expect(find.text('한 번 더 누르면 앱이 종료됩니다.'), findsNothing);
        expect(exits, isEmpty);
        await t.binding.handlePopRoute();
        await t.pump();
        expect(find.text('한 번 더 누르면 앱이 종료됩니다.'), findsOneWidget);
        expect(exits, isEmpty);
        await t.pumpWidget(const SizedBox.shrink());
        await t.pumpAndSettle();
      },
    );
  }

  testWidgets('tab navigation resets an armed home exit', (t) async {
    final exits = mockAppExit(t);
    await start(t);
    await t.binding.handlePopRoute();
    await t.pump();
    await tab(t, 1);
    await tab(t, 0);
    await t.binding.handlePopRoute();
    await t.pump();
    expect(exits, isEmpty);
    expect(find.text('한 번 더 누르면 앱이 종료됩니다.'), findsOneWidget);
    await t.pumpWidget(const SizedBox.shrink());
    await t.pumpAndSettle();
  });

  testWidgets('entry form uses normal back and resets the home confirmation', (
    t,
  ) async {
    final exits = mockAppExit(t);
    final transport = await start(t);
    await goal(t, 1);
    await t.binding.handlePopRoute();
    await t.pump();
    await t.scrollUntilVisible(
      find.byKey(const Key('openStudyEntry')),
      180,
      scrollable: find.byType(Scrollable).first,
    );
    await t.pumpAndSettle();
    await t.tap(find.byKey(const Key('openStudyEntry')));
    await t.pumpAndSettle();
    expect(find.text('공부 기록 입력 · 서버 저장'), findsOneWidget);
    await t.binding.handlePopRoute();
    await t.pumpAndSettle();
    expect(find.text('대시보드 · 실제 조회'), findsOneWidget);
    expect(exits, isEmpty);
    await t.binding.handlePopRoute();
    await t.pump();
    expect(exits, isEmpty);
    expect(find.text('한 번 더 누르면 앱이 종료됩니다.'), findsOneWidget);
    expect(transport.calls.where((u) => u.path == '/auth/logout'), isEmpty);
    await t.pumpWidget(const SizedBox.shrink());
    await t.pumpAndSettle();
  });

  testWidgets('backgrounding clears home exit confirmation', (t) async {
    final exits = mockAppExit(t);
    await start(t);
    await t.binding.handlePopRoute();
    await t.pump();
    t.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    t.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await t.pumpAndSettle();
    await t.binding.handlePopRoute();
    await t.pump();
    expect(exits, isEmpty);
    await t.pumpWidget(const SizedBox.shrink());
    await t.pumpAndSettle();
  });

  testWidgets(
    'predictive back cancel is harmless and two commits use the same home policy',
    (t) async {
      final exits = mockAppExit(t);
      await start(t);
      await predictiveBack(t, 'startBackGesture');
      await predictiveBack(t, 'updateBackGestureProgress');
      await predictiveBack(t, 'cancelBackGesture');
      await t.pump();
      expect(find.text('한 번 더 누르면 앱이 종료됩니다.'), findsNothing);
      expect(exits, isEmpty);
      await predictiveBack(t, 'startBackGesture');
      await predictiveBack(t, 'commitBackGesture');
      await t.pump();
      expect(find.text('한 번 더 누르면 앱이 종료됩니다.'), findsOneWidget);
      await predictiveBack(t, 'startBackGesture');
      await predictiveBack(t, 'commitBackGesture');
      await t.pumpAndSettle();
      expect(exits.length, 1);
      expect(find.text('대시보드 · 실제 조회'), findsOneWidget);
    },
  );

  testWidgets(
    'non-Android home preserves normal navigator back without app-exit channel',
    (t) async {
      final exits = mockAppExit(t);
      await start(t);
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      expect(find.text('서버 로그인'), findsOneWidget);
      expect(exits, isEmpty);
      expect(find.text('한 번 더 누르면 앱이 종료됩니다.'), findsNothing);
    },
    variant: TargetPlatformVariant.only(TargetPlatform.iOS),
  );

  testWidgets(
    'login keeps its original system back without the dashboard confirmation',
    (t) async {
      final exits = mockAppExit(t);
      final api = PlannerApi(
        base: Uri.parse('http://localhost:8080'),
        transport: NavTransport(),
        debugLoopback: true,
      );
      addTearDown(api.close);
      await t.pumpWidget(MaterialApp(home: ServerLogin(api: api)));
      await t.pumpAndSettle();
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      expect(find.text('한 번 더 누르면 앱이 종료됩니다.'), findsNothing);
      expect(exits.length, 1);
    },
  );

  testWidgets(
    'opening and cancelling the goal menu resets home exit confirmation',
    (t) async {
      final exits = mockAppExit(t);
      await start(t);
      await t.binding.handlePopRoute();
      await t.pump();
      await t.tap(find.byType(DropdownButtonFormField<int>));
      await t.pumpAndSettle();
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      await t.binding.handlePopRoute();
      await t.pump();
      expect(exits, isEmpty);
      expect(find.text('한 번 더 누르면 앱이 종료됩니다.'), findsOneWidget);
      await t.pumpWidget(const SizedBox.shrink());
      await t.pumpAndSettle();
    },
  );

  testWidgets(
    'platform exit failure is reported without automatic exit retries',
    (t) async {
      mockAppExit(t);
      var attempts = 0;
      t.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async {
          if (call.method == 'SystemNavigator.pop') {
            attempts++;
            if (attempts == 1) {
              throw PlatformException(code: 'SYNTHETIC_EXIT_FAILURE');
            }
          }
          return null;
        },
      );
      final transport = await start(t);
      await t.binding.handlePopRoute();
      await t.pump();
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      expect(attempts, 1);
      expect(find.textContaining('앱 종료 요청을 완료하지 못했습니다.'), findsOneWidget);
      await t.binding.handlePopRoute();
      await t.pump();
      expect(attempts, 1);
      await t.binding.handlePopRoute();
      await t.pumpAndSettle();
      expect(attempts, 2);
      expect(transport.calls.where((u) => u.path == '/auth/logout'), isEmpty);
    },
  );
}
