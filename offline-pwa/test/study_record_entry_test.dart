import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/study_record_entry.dart';
import 'package:ai_study_planner_mobile/server_plan_analysis.dart';
import 'package:ai_study_planner_mobile/server_screens.dart';

class SaveTransport implements Transport {
  final calls = <(String, Uri, Map<String, String>, String?)>[];
  int posts = 0, status = 200;
  bool wrongGoal = false, invalidReply = false, networkFailure = false;
  bool loseReplyAfterSave = false;
  Completer<void>? hold;
  Map<String, dynamic>? stored;
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    calls.add((method, uri, headers, body));
    Object data = {};
    switch (uri.path) {
      case '/goals':
        data = [
          {'goalId': 1, 'goalName': '목표'},
        ];
      case '/auth/me':
        data = {'authenticated': true, 'userId': 1};
      case '/auth/csrf':
        data = {'headerName': 'X-CSRF-TOKEN', 'token': 'mock-token'};
      case '/subjects':
        data = [
          {
            'subjectId': 1,
            'goalId': wrongGoal ? 3 : 1,
            'userId': 1,
            'subjectName': '운영체제',
          },
        ];
      case '/analysis-summary':
        data = {
          'goalId': 1,
          'scope': 'CURRENT_GOAL',
          'goalName': '목표',
          'recordCount': stored == null ? 0 : 1,
          'totalStudyMinutes': stored == null ? 0 : 40,
        };
      case '/study-logs':
        if (method == 'POST') {
          posts++;
          if (hold != null) await hold!.future;
          if (networkFailure) throw const ApiFailure('연결 오류');
          if (status != 200) return Reply(status, '{}');
          stored = {...jsonDecode(body!) as Map<String, dynamic>, 'logId': 9};
          if (loseReplyAfterSave) throw const ApiFailure('저장 응답 연결 끊김');
          data = invalidReply ? {'message': 'ok'} : stored!;
        } else {
          data = stored == null ? [] : [stored!];
        }
      default:
        throw StateError('Unexpected path');
    }
    return Reply(200, jsonEncode(data));
  }

  @override
  void close() {}
}

PlannerApi makeApi(SaveTransport t) => PlannerApi(
  base: Uri.parse('http://localhost:8080'),
  transport: t,
  debugLoopback: true,
);
Future<Map<String, dynamic>> save(
  PlannerApi a, {
  int goal = 1,
  int subject = 1,
  int minutes = 40,
  int focus = 3,
  String date = '2026-10-19',
  String content = '개념 복습',
}) => a.saveStudyRecord(
  goalId: goal,
  subjectId: subject,
  date: date,
  minutes: minutes,
  understanding: 3,
  focus: focus,
  fatigue: 2,
  content: content,
  method: '복습',
  memo: '메모',
);
Future<void> fill(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('entrySubject')));
  await tester.pumpAndSettle();
  await tester.tap(find.text('운영체제').last);
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const Key('entryMinutes')), '40');
  await tester.enterText(find.byKey(const Key('entryContent')), '개념 복습');
  for (final k in ['entryFocus', 'entryUnderstanding', 'entryFatigue']) {
    await tester.ensureVisible(find.byKey(Key('${k}_3')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(Key('${k}_3')));
    await tester.pumpAndSettle();
  }
  await tester.ensureVisible(find.byKey(const Key('entrySave')));
}

void large(WidgetTester tester) {
  tester.view.physicalSize = const Size(800, 1800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

void main() {
  testWidgets('small viewport with keyboard can scroll to memo and save', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewInsets);
    await tester.pumpWidget(
      MaterialApp(
        home: StudyRecordEntry(
          api: makeApi(SaveTransport()),
          goalId: 1,
          goalName: '목표',
        ),
      ),
    );
    await tester.pumpAndSettle();
    final pageScroll = find
        .descendant(
          of: find.byType(ListView),
          matching: find.byType(Scrollable),
        )
        .first;
    await tester.scrollUntilVisible(
      find.byKey(const Key('entryMemo')),
      200,
      scrollable: pageScroll,
    );
    await tester.enterText(find.byKey(const Key('entryMemo')), '입력 유지');
    await tester.scrollUntilVisible(
      find.byKey(const Key('entrySave')),
      200,
      scrollable: pageScroll,
    );
    expect(find.byKey(const Key('entrySave')).hitTestable(), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'dashboard entry keeps selected goal and refreshes saved records',
    (tester) async {
      large(tester);
      final t = SaveTransport();
      await tester.pumpWidget(
        MaterialApp(home: ServerDashboard(api: makeApi(t))),
      );
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<FilledButton>(find.byKey(const Key('openStudyEntry')))
            .onPressed,
        isNull,
      );
      await tester.tap(find.byType(DropdownButtonFormField<int>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('목표').last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('openStudyEntry')));
      await tester.pumpAndSettle();
      await fill(tester);
      await tester.tap(find.byKey(const Key('entrySave')));
      await tester.pumpAndSettle();
      expect(find.text('학습 분석 · 실제 조회'), findsOneWidget);
      expect(find.text('공부시간: 40분'), findsOneWidget);
      expect(t.posts, 1);
      await tester.tap(find.byKey(const Key('realTab0')));
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<DropdownButtonFormField<int>>(
              find.byType(DropdownButtonFormField<int>),
            )
            .initialValue,
        1,
      );
    },
  );
  test('JSON POST carries required fields and fresh CSRF, no logId or invented goal field', () async {
    final t = SaveTransport();
    final result = await save(makeApi(t));
    expect(result['logId'], 9);
    expect(t.posts, 1);
    final c = t.calls.singleWhere((c) => c.$1 == 'POST');
    expect(c.$2.path, '/study-logs');
    expect(c.$3['Content-Type'], 'application/json; charset=UTF-8');
    expect(c.$3['X-CSRF-TOKEN'], 'mock-token');
    final body = jsonDecode(c.$4!);
    expect(body['userId'], 1);
    expect(body['subjectId'], 1);
    expect(body['fatigueScore'], 2);
    expect(body['studyContent'], '개념 복습');
    expect(body.containsKey('goalId'), false);
    expect(body.containsKey('logId'), false);
  });
  test('invalid inputs fail before any request', () async {
    final t = SaveTransport();
    final a = makeApi(t);
    for (final f in [
      () => save(a, goal: 0),
      () => save(a, minutes: 0),
      () => save(a, minutes: 2147483648),
      () => save(a, focus: 6),
      () => save(a, date: '2026-02-31'),
      () => save(a, content: ' '),
    ]) {
      await expectLater(f(), throwsA(isA<ApiFailure>()));
    }
    expect(t.calls, isEmpty);
  });
  test('foreign goal subjects and unknown subject prevent POST', () async {
    final t = SaveTransport()..wrongGoal = true;
    await expectLater(save(makeApi(t)), throwsA(isA<ApiFailure>()));
    expect(t.posts, 0);
    final t2 = SaveTransport();
    await expectLater(
      save(makeApi(t2), subject: 99),
      throwsA(isA<ApiFailure>()),
    );
    expect(t2.posts, 0);
  });
  test('duplicate concurrent save is blocked', () async {
    final t = SaveTransport()..hold = Completer<void>();
    final a = makeApi(t);
    final first = save(a);
    await expectLater(save(a), throwsA(isA<ApiFailure>()));
    t.hold!.complete();
    await first;
    expect(t.posts, 1);
  });
  test('HTTP failures are not success and never retry', () async {
    for (final status in [400, 401, 403, 404, 409, 500]) {
      final t = SaveTransport()..status = status;
      await expectLater(
        save(makeApi(t)),
        throwsA(
          isA<StudySaveFailure>().having((e) => e.status, 'status', status),
        ),
      );
      expect(t.posts, 1);
    }
  });
  test('network loss and malformed success are uncertain, no automatic second POST', () async {
    for (final t in [
      SaveTransport()..networkFailure = true,
      SaveTransport()..invalidReply = true,
    ]) {
      await expectLater(
        save(makeApi(t)),
        throwsA(
          isA<StudySaveFailure>().having((e) => e.uncertain, 'uncertain', true),
        ),
      );
      expect(t.posts, 1);
    }
  });
  testWidgets('missing form fields stop submission', (tester) async {
    large(tester);
    final t = SaveTransport();
    await tester.pumpWidget(
      MaterialApp(
        home: StudyRecordEntry(api: makeApi(t), goalId: 1, goalName: '목표'),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('entrySave')));
    await tester.pumpAndSettle();
    expect(t.posts, 0);
    expect(find.text('공부 내용을 입력하세요.'), findsOneWidget);
  });
  testWidgets(
    'double tap submits once and success reloads analysis and record list',
    (tester) async {
      large(tester);
      final t = SaveTransport()..hold = Completer<void>();
      await tester.pumpWidget(
        MaterialApp(
          home: ServerStudyAnalysis(api: makeApi(t), goalId: 1, goalName: '목표'),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('analysisStudyEntry')));
      await tester.pumpAndSettle();
      await fill(tester);
      await tester.tap(find.byKey(const Key('entrySave')));
      await tester.tap(find.byKey(const Key('entrySave')));
      await tester.pump();
      t.hold!.complete();
      await tester.pumpAndSettle();
      expect(t.posts, 1);
      expect(find.text('서버에서 공부 기록 저장을 확인했습니다.'), findsOneWidget);
      expect(t.calls.where((c) => c.$2.path == '/analysis-summary').length, 2);
      expect(
        t.calls
            .where((c) => c.$2.path == '/study-logs' && c.$1 == 'GET')
            .length,
        2,
      );
      expect(find.text('공부시간: 40분'), findsOneWidget);
    },
  );
  testWidgets('failed save keeps input and does not show success', (
    tester,
  ) async {
    large(tester);
    final t = SaveTransport()..status = 400;
    await tester.pumpWidget(
      MaterialApp(
        home: StudyRecordEntry(api: makeApi(t), goalId: 1, goalName: '목표'),
      ),
    );
    await tester.pumpAndSettle();
    await fill(tester);
    await tester.tap(find.byKey(const Key('entrySave')));
    await tester.pumpAndSettle();
    expect(find.text('개념 복습'), findsOneWidget);
    expect(find.textContaining('요청 실패 (400)'), findsOneWidget);
    expect(t.posts, 1);
  });
  testWidgets('uncertain save disables resend and keeps draft', (tester) async {
    large(tester);
    final t = SaveTransport()..networkFailure = true;
    await tester.pumpWidget(
      MaterialApp(
        home: StudyRecordEntry(api: makeApi(t), goalId: 1, goalName: '목표'),
      ),
    );
    await tester.pumpAndSettle();
    await fill(tester);
    await tester.tap(find.byKey(const Key('entrySave')));
    await tester.pumpAndSettle();
    expect(
      tester.widget<FilledButton>(find.byKey(const Key('entrySave'))).onPressed,
      isNull,
    );
    expect(find.text('재전송 없이 기록 확인'), findsOneWidget);
    expect(find.text('개념 복습'), findsOneWidget);
  });
  testWidgets(
    'system back after lost save reply refreshes real tab data without resending',
    (tester) async {
      large(tester);
      final t = SaveTransport()..loseReplyAfterSave = true;
      await tester.pumpWidget(
        MaterialApp(home: ServerDashboard(api: makeApi(t))),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byType(DropdownButtonFormField<int>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('목표').last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('openStudyEntry')));
      await tester.pumpAndSettle();
      await fill(tester);
      await tester.tap(find.byKey(const Key('entrySave')));
      await tester.pumpAndSettle();
      expect(t.stored, isNotNull);
      expect(find.text('재전송 없이 기록 확인'), findsOneWidget);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.text('학습 분석 · 실제 조회'), findsOneWidget);
      expect(find.text('공부시간: 40분'), findsOneWidget);
      expect(find.text('서버에서 공부 기록 저장을 확인했습니다.'), findsNothing);
      await tester.tap(find.byKey(const Key('realTab0')));
      await tester.pumpAndSettle();
      expect(find.text('40분'), findsOneWidget);
      expect(t.posts, 1);
    },
  );
  testWidgets(
    'system back after authentication failure returns expired result',
    (tester) async {
      large(tester);
      final t = SaveTransport()..status = 401;
      StudyEntryResult? result;
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: FilledButton(
                onPressed: () async {
                  result = await Navigator.of(context).push<StudyEntryResult>(
                    MaterialPageRoute(
                      builder: (_) => StudyRecordEntry(
                        api: makeApi(t),
                        goalId: 1,
                        goalName: '목표',
                      ),
                    ),
                  );
                },
                child: const Text('입력 열기'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('입력 열기'));
      await tester.pumpAndSettle();
      await fill(tester);
      await tester.tap(find.byKey(const Key('entrySave')));
      await tester.pumpAndSettle();
      expect(find.text('입력 종료'), findsOneWidget);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(result, StudyEntryResult.expired);
      expect(t.posts, 1);
    },
  );
}
