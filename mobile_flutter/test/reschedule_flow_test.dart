import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/server_plan_analysis.dart';
import 'package:ai_study_planner_mobile/server_reschedule.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

const targetDate = '2026-10-29';

class FlowTransport implements Transport {
  final calls = <(String, Uri, Map<String, String>, String?)>[];
  int posts = 0;
  int? postError, csrfError;
  String? failGetPath;
  int getStatus = 500, actualCarry = 9;
  bool lost = false,
      malformed = false,
      wrongDate = false,
      mismatchedRead = false;
  bool existing = false;
  String status = 'FINAL';
  Completer<void>? holdPost, holdGoalOne;
  Completer<void>? holdReadOne;
  Map<String, dynamic>? previewPatch;
  Map<String, dynamic>? stored;
  Map<String, dynamic> preview(int goal) => {
    'goalId': goal,
    'scheduleDate': targetDate,
    'studyMinutes': 100,
    'carryoverMinutes': 9,
    'newStudyMinutes': 91,
    'carryoverTotalPendingMinutes': 60,
    'previewMode': true,
    'isScheduleValid': true,
    'hasConflict': false,
    'previewNotice': '오늘 기록이 현재 상태로 끝난다고 가정한 내일 미리보기입니다.',
    'timeWindowNotice': '서버가 제공한 시간대 안내',
    'carryoverCalculation': [
      {'provisional': true},
    ],
    'subjectPlans': [
      {
        'subjectName': '가상 과목 $goal',
        'carryoverMinutes': 9,
        'newStudyMinutes': 91,
      },
    ],
    'timeSlots': [
      {
        'startTime': '$targetDate 19:00',
        'endTime': '$targetDate 19:09',
        'minutes': 9,
        'activity': '가상 보충',
      },
    ],
  };
  Map<String, dynamic> snapshot(int goal, String date) => {
    'saved': true,
    'goalId': goal,
    'scheduleDate': date,
    'planStatus': status,
    'studyMinutes': 100,
    'carryoverMinutes': actualCarry,
    'newStudyMinutes': 100 - actualCarry,
    'reason': '저장된 가상 목표 $goal 근거',
  };
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    calls.add((method, uri, Map.of(headers), body));
    if (uri.path == '/study-reschedule-calendar') return Reply(404, '{}');
    final goal = int.tryParse(uri.queryParameters['goalId'] ?? '') ?? 1;
    if (method == 'GET' && uri.path == failGetPath) {
      return Reply(getStatus, '{}');
    }
    switch (uri.path) {
      case '/auth/csrf':
        if (csrfError != null) return Reply(csrfError!, '{}');
        return Reply(
          200,
          jsonEncode({'headerName': 'X-CSRF-TOKEN', 'token': 'fake-csrf'}),
          [Cookie('JSESSIONID', 'fake-session')..path = '/'],
        );
      case '/auth/login':
        return Reply(200, '{}');
      case '/auth/me':
        return Reply(200, '{"authenticated":true,"userId":1}');
      case '/study-reschedule-preview':
        expect(method, 'GET');
        if (goal == 1 && holdGoalOne != null) await holdGoalOne!.future;
        return Reply(200, jsonEncode({...preview(goal), ...?previewPatch}));
      case '/study-reschedule-save':
        expect(method, 'POST');
        posts++;
        if (holdPost != null) await holdPost!.future;
        if (postError != null) return Reply(postError!, '{}');
        stored = snapshot(goal, targetDate);
        if (lost) throw const ApiFailure('가상 응답 유실');
        if (malformed) return Reply(200, 'not-json');
        return Reply(
          200,
          jsonEncode({
            'saved': true,
            'planDate': wrongDate ? '2026-10-30' : targetDate,
            'planStatus': status,
            'studyMinutes': 100,
            'carryoverMinutes': actualCarry,
            'newStudyMinutes': 100 - actualCarry,
          }),
        );
      case '/study-reschedule-saved':
        expect(method, 'GET');
        if (goal == 1 && holdReadOne != null) await holdReadOne!.future;
        final result = stored != null && stored!['goalId'] == goal
            ? Map<String, dynamic>.from(stored!)
            : (existing
                  ? snapshot(goal, uri.queryParameters['date']!)
                  : <String, dynamic>{'saved': false});
        if (mismatchedRead && stored != null) result['carryoverMinutes'] = 8;
        return Reply(200, jsonEncode(result));
      default:
        throw StateError('Unexpected route ${uri.path}');
    }
  }

  @override
  void close() {}
}

PlannerApi makeApi(FlowTransport t) => PlannerApi(
  base: Uri.parse('http://localhost:8080'),
  transport: t,
  debugLoopback: true,
);
Future<void> reveal(WidgetTester tester, Finder finder) => tester
    .scrollUntilVisible(finder, 160, scrollable: find.byType(Scrollable).last);

Future<PlannerApi> openFlow(
  WidgetTester tester,
  FlowTransport transport, {
  bool small = false,
}) async {
  tester.view.physicalSize = Size(small ? 360 : 900, small ? 640 : 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final api = makeApi(transport);
  addTearDown(api.close);
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              final result = await Navigator.of(context).push<RescheduleResult>(
                MaterialPageRoute(
                  builder: (_) => ServerReschedule(
                    api: api,
                    goalId: 1,
                    goalName: '가상 목표 A',
                  ),
                ),
              );
              if (context.mounted && result?.expired == true) {
                ScaffoldMessenger.of(context)
                    .showSnackBar(const SnackBar(content: Text('재로그인 필요')));
              }
            },
            child: const Text('열기'),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('열기'));
  await tester.pumpAndSettle();
  return api;
}

Future<void> confirm(WidgetTester tester) async {
  await reveal(tester, find.byKey(const Key('saveReschedule')));
  await tester.tap(find.byKey(const Key('saveReschedule')));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('read before save is not described as an uncertain write', (
    tester,
  ) async {
    final t = FlowTransport()..existing = true;
    await openFlow(tester, t);
    await reveal(tester, find.byKey(const Key('checkRescheduleStored')));
    await tester.tap(find.byKey(const Key('checkRescheduleStored')));
    await tester.pumpAndSettle();
    expect(t.posts, 0);
    expect(find.textContaining('이번 요청의 저장 성공 여부는 확정하지 않습니다.'), findsNothing);
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('saveReschedule')))
          .onPressed,
      isNotNull,
    );
  });
  for (final patch in [
    {'goalId': 3},
    {'scheduleDate': '2026-02-31'},
    {'previewMode': false},
    {'carryoverMinutes': -1},
    {'timeSlots': 'invalid'},
  ]) {
    test('invalid preview is rejected: ${patch.keys.single}', () async {
      final t = FlowTransport()..previewPatch = patch;
      final api = makeApi(t);
      addTearDown(api.close);
      await expectLater(api.reschedulePreview(1), throwsA(isA<ApiFailure>()));
      expect(t.posts, 0);
    });
  }
  testWidgets(
    'unknown existing snapshot blocks save without treating error as empty',
    (tester) async {
      final t = FlowTransport()..failGetPath = '/study-reschedule-saved';
      await openFlow(tester, t);
      await reveal(tester, find.byKey(const Key('saveReschedule')));
      expect(
        tester
            .widget<FilledButton>(find.byKey(const Key('saveReschedule')))
            .onPressed,
        isNull,
      );
      expect(find.text('현재 저장 계획 없음'), findsNothing);
      expect(t.posts, 0);
    },
  );
  testWidgets('preview HTTP failure has no save control', (tester) async {
    final t = FlowTransport()
      ..failGetPath = '/study-reschedule-preview'
      ..getStatus = 404;
    await openFlow(tester, t);
    expect(find.textContaining('404'), findsOneWidget);
    expect(find.byKey(const Key('saveReschedule')), findsNothing);
    expect(t.posts, 0);
  });
  testWidgets('server recalculation may differ from confirmed preview', (
    tester,
  ) async {
    final t = FlowTransport()..actualCarry = 12;
    await openFlow(tester, t);
    await confirm(tester);
    expect(find.textContaining('보충 9분 · 신규 91분'), findsWidgets);
    await tester.tap(find.byKey(const Key('confirmRescheduleSave')));
    await tester.pumpAndSettle();
    expect(find.text('저장 성공 · 서버 저장본을 GET으로 재조회했습니다.'), findsOneWidget);
    await reveal(tester, find.text('88분'));
    expect(find.text('88분'), findsOneWidget);
    expect(t.posts, 1);
  });
  testWidgets('old plan GET cannot overwrite new goal after delayed response', (
    tester,
  ) async {
    final t = FlowTransport()
      ..existing = true
      ..holdReadOne = Completer<void>();
    final api = makeApi(t);
    addTearDown(api.close);
    await tester.pumpWidget(
      MaterialApp(
        home: ServerStudyPlan(api: api, goalId: 1, goalName: '가상 목표 A'),
      ),
    );
    await tester.pump();
    await tester.pumpWidget(
      MaterialApp(
        home: ServerStudyPlan(api: api, goalId: 3, goalName: '가상 목표 B'),
      ),
    );
    await tester.pumpAndSettle();
    await reveal(tester, find.text('저장된 가상 목표 3 근거'));
    t.holdReadOne!.complete();
    await tester.pumpAndSettle();
    expect(find.text('저장된 가상 목표 3 근거'), findsOneWidget);
    expect(find.text('저장된 가상 목표 1 근거'), findsNothing);
  });
  test('preview uses explicit goal only and never posts', () async {
    final t = FlowTransport();
    final api = makeApi(t);
    addTearDown(api.close);
    final p = await api.reschedulePreview(3);
    expect(p['goalId'], 3);
    expect(p['carryoverTotalPendingMinutes'], 60);
    expect(t.posts, 0);
    expect(t.calls.single.$2.queryParameters, {'goalId': '3'});
  });
  test('POST carries session, fresh CSRF, goal query and empty body', () async {
    final t = FlowTransport();
    final api = makeApi(t);
    addTearDown(api.close);
    await api.login('virtual', 'fake-only');
    await api.saveReschedule(goalId: 1, date: targetDate);
    final post = t.calls.singleWhere(
      (c) => c.$2.path == '/study-reschedule-save',
    );
    expect(post.$2.queryParameters, {'goalId': '1'});
    expect(post.$4, '');
    expect(post.$3['X-CSRF-TOKEN'], 'fake-csrf');
    expect(post.$3['Cookie'], 'JSESSIONID=fake-session');
    expect(t.calls[t.calls.indexOf(post) - 1].$2.path, '/auth/csrf');
    expect(t.posts, 1);
  });
  test('concurrent API saves produce one POST', () async {
    final t = FlowTransport()..holdPost = Completer<void>();
    final api = makeApi(t);
    addTearDown(api.close);
    final first = api.saveReschedule(goalId: 1, date: targetDate);
    await Future<void>.delayed(Duration.zero);
    await expectLater(
      api.saveReschedule(goalId: 1, date: targetDate),
      throwsA(isA<RescheduleSaveFailure>()),
    );
    t.holdPost!.complete();
    await first;
    expect(t.posts, 1);
  });
  for (final kind in ['network', 'json', 'date', '500']) {
    test(
      '$kind after POST is uncertain, never automatically retried',
      () async {
        final t = FlowTransport()
          ..lost = kind == 'network'
          ..malformed = kind == 'json'
          ..wrongDate = kind == 'date'
          ..postError = kind == '500' ? 500 : null;
        final api = makeApi(t);
        addTearDown(api.close);
        await expectLater(
          api.saveReschedule(goalId: 1, date: targetDate),
          throwsA(
            isA<RescheduleSaveFailure>().having(
              (e) => e.uncertain,
              'uncertain',
              true,
            ),
          ),
        );
        await expectLater(
          api.saveReschedule(goalId: 1, date: targetDate),
          throwsA(isA<RescheduleSaveFailure>()),
        );
        await api.storedPlan(goalId: 1, date: targetDate);
        expect(t.posts, 1);
      },
    );
  }
  test('CSRF preflight failure sends no save POST', () async {
    final t = FlowTransport()..csrfError = 403;
    final api = makeApi(t);
    addTearDown(api.close);
    await expectLater(
      api.saveReschedule(goalId: 1, date: targetDate),
      throwsA(
        isA<RescheduleSaveFailure>().having((e) => e.status, 'status', 403),
      ),
    );
    expect(t.posts, 0);
    expect(api.rescheduleUncertain(1, targetDate), false);
  });
  testWidgets(
    'preview shows 60/9/91, provisional notice, and small screen scrolls',
    (tester) async {
      final t = FlowTransport();
      await openFlow(tester, t, small: true);
      expect(find.text('60분'), findsOneWidget);
      expect(find.text('9분'), findsWidgets);
      expect(find.text('91분'), findsOneWidget);
      await reveal(tester, find.text('공부 종료 전 기록의 잠정 계산이 포함되어 있습니다.'));
      await reveal(tester, find.byKey(const Key('saveReschedule')));
      expect(
        find.byKey(const Key('saveReschedule')).hitTestable(),
        findsOneWidget,
      );
      expect(t.posts, 0);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('overwrite warning is explicit and cancel sends zero POST', (
    tester,
  ) async {
    final t = FlowTransport()..existing = true;
    await openFlow(tester, t);
    await confirm(tester);
    expect(find.textContaining('기존 FINAL 저장 계획을 덮어쓸 수 있습니다.'), findsOneWidget);
    expect(find.textContaining('보충 9분 · 신규 91분'), findsWidgets);
    await tester.tap(find.text('취소'));
    await tester.pumpAndSettle();
    expect(t.posts, 0);
  });
  for (final status in ['DRAFT', 'FINAL']) {
    testWidgets(
      '$status is chosen by server; save then GET shows actual snapshot',
      (tester) async {
        final t = FlowTransport()..status = status;
        await openFlow(tester, t);
        await confirm(tester);
        await tester.tap(find.byKey(const Key('confirmRescheduleSave')));
        await tester.pumpAndSettle();
        expect(t.posts, 1);
        expect(find.text('저장 성공 · 서버 저장본을 GET으로 재조회했습니다.'), findsOneWidget);
        await reveal(tester, find.text('서버 저장본 · $status'));
        expect(find.text('서버 저장본 · $status'), findsOneWidget);
        final writes = t.calls
            .where((c) => c.$2.path == '/study-reschedule-save')
            .length;
        await tester.tap(find.byKey(const Key('checkRescheduleStored')));
        await tester.pumpAndSettle();
        expect(t.posts, writes);
      },
    );
  }
  testWidgets('waiting save disables submit and does not send twice', (
    tester,
  ) async {
    final t = FlowTransport()..holdPost = Completer<void>();
    await openFlow(tester, t);
    await confirm(tester);
    await tester.tap(find.byKey(const Key('confirmRescheduleSave')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('saveReschedule')))
          .onPressed,
      isNull,
    );
    expect(t.posts, 1);
    t.holdPost!.complete();
    await tester.pumpAndSettle();
    expect(t.posts, 1);
  });
  testWidgets('lost response checks GET without claiming request succeeded', (
    tester,
  ) async {
    final t = FlowTransport()..lost = true;
    await openFlow(tester, t);
    await confirm(tester);
    await tester.tap(find.byKey(const Key('confirmRescheduleSave')));
    await tester.pumpAndSettle();
    expect(t.posts, 1);
    expect(find.textContaining('이번 요청의 저장 성공 여부는 확정하지 않습니다.'), findsOneWidget);
    expect(find.textContaining('저장 성공 ·'), findsNothing);
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('saveReschedule')))
          .onPressed,
      isNull,
    );
    expect(
      t.calls.where((c) => c.$2.path == '/study-reschedule-saved').length,
      2,
    );
  });
  for (final code in [401, 403]) {
    testWidgets('$code save failure requests re-login without retry', (
      tester,
    ) async {
      final t = FlowTransport()..postError = code;
      await openFlow(tester, t);
      await confirm(tester);
      await tester.tap(find.byKey(const Key('confirmRescheduleSave')));
      await tester.pumpAndSettle();
      expect(find.text('재로그인 필요'), findsOneWidget);
      expect(t.posts, 1);
    });
  }
  testWidgets('mismatched stored result is not displayed as verified success', (
    tester,
  ) async {
    final t = FlowTransport()..mismatchedRead = true;
    await openFlow(tester, t);
    await confirm(tester);
    await tester.tap(find.byKey(const Key('confirmRescheduleSave')));
    await tester.pumpAndSettle();
    expect(find.textContaining('저장 응답과 재조회 값이 다릅니다.'), findsOneWidget);
    expect(find.textContaining('저장 성공 ·'), findsNothing);
    expect(t.posts, 1);
  });
  testWidgets('late previous goal preview cannot replace newly selected goal', (
    tester,
  ) async {
    final t = FlowTransport()..holdGoalOne = Completer<void>();
    final api = makeApi(t);
    addTearDown(api.close);
    await tester.pumpWidget(
      MaterialApp(
        home: ServerReschedule(api: api, goalId: 1, goalName: '목표 A'),
      ),
    );
    await tester.pump();
    await tester.pumpWidget(
      MaterialApp(
        home: ServerReschedule(api: api, goalId: 3, goalName: '목표 B'),
      ),
    );
    await tester.pumpAndSettle();
    t.holdGoalOne!.complete();
    await tester.pumpAndSettle();
    expect(find.text('목표: 목표 B (ID 3)'), findsOneWidget);
    expect(find.textContaining('가상 과목 1'), findsNothing);
    expect(
      t.calls
          .where((c) => c.$2.path == '/study-reschedule-saved')
          .every((c) => c.$2.queryParameters['goalId'] == '3'),
      true,
    );
  });
  testWidgets(
    'existing plan screen opens preview and reloads saved target on return',
    (tester) async {
      tester.view.physicalSize = const Size(900, 1600);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final t = FlowTransport();
      final api = makeApi(t);
      addTearDown(api.close);
      await tester.pumpWidget(
        MaterialApp(
          home: ServerStudyPlan(api: api, goalId: 1, goalName: '가상 목표 A'),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('openReschedulePreview')));
      await tester.pumpAndSettle();
      await confirm(tester);
      await tester.tap(find.byKey(const Key('confirmRescheduleSave')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('leaveReschedule')));
      await tester.pumpAndSettle();
      expect(find.text('학습 계획 · 실제 조회'), findsOneWidget);
      expect(find.text('대상 날짜: $targetDate'), findsOneWidget);
      expect(
        t.calls
            .where((c) => c.$2.path == '/study-reschedule-saved')
            .last
            .$2
            .queryParameters['date'],
        targetDate,
      );
      expect(t.calls.last.$2.path, '/study-reschedule-calendar');
      expect(t.calls.last.$2.queryParameters, {
        'month': targetDate.substring(0, 7),
        'goalId': '1',
      });
    },
  );
}
