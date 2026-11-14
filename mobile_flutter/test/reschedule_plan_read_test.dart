import 'dart:convert';

import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/server_plan_analysis.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class PlanTransport implements Transport {
  final calls = <Uri>[];
  int savedCarry = 9;

  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    expect(method, 'GET');
    if (uri.path == '/study-reschedule-calendar') {
      return Reply(404, '{}'); // legacy server
    }
    expect(['/study-reschedule-saved', '/study-plan'], contains(uri.path));
    calls.add(uri);
    final saved = uri.path == '/study-reschedule-saved';
    final carry = saved ? savedCarry : 9;
    return Reply(
      200,
      jsonEncode({
        'goalId': int.parse(uri.queryParameters['goalId']!),
        'scheduleDate': uri.queryParameters['date'],
        if (saved) 'saved': true,
        'planSaved': saved,
        if (saved) 'planStatus': 'FINAL',
        'studyMinutes': 100,
        'carryoverMinutes': carry,
        'newStudyMinutes': 100 - carry,
        'carryoverTotalPendingMinutes': 60,
        'isScheduleValid': true,
        'hasConflict': false,
        if (!saved) 'rescheduleNotice': '확인된 미완료 60분 중 보충 9분 배정',
        'timeSlots': [
          {
            'subjectName': '가상 운영체제',
            'type': '공부',
            'activity': '서버가 반환한 보충 학습',
            'minutes': carry,
            'startTime': '${uri.queryParameters['date']} 19:00',
            'endTime': '${uri.queryParameters['date']} 19:09',
          },
        ],
      }),
    );
  }

  @override
  void close() {}
}

Future<void> showPlan(WidgetTester tester, PlanTransport transport) async {
  final api = PlannerApi(
    base: Uri.parse('http://localhost:8080'),
    transport: transport,
    debugLoopback: true,
  );
  addTearDown(api.close);
  await tester.pumpWidget(
    MaterialApp(
      home: ServerStudyPlan(api: api, goalId: 17, goalName: '가상 목표 A'),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'calculation shows server allocation separately from saved FINAL',
    (tester) async {
      final transport = PlanTransport();
      await showPlan(tester, transport);
      expect(find.text('저장된 FINAL 계획'), findsOneWidget);
      await tester.ensureVisible(find.byKey(const Key('calculatedPlanButton')));
      await tester.tap(find.byKey(const Key('calculatedPlanButton')));
      await tester.pumpAndSettle();
      expect(find.text('서버 계획 조회 · 저장본 또는 미저장 계산 결과'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('확인된 미완료 60분 중 보충 9분 배정'),
        180,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('현재 계산된 계획입니다. MySQL 저장본이 아닙니다.'), findsOneWidget);
      expect(find.text('확인된 미완료 60분 중 보충 9분 배정'), findsOneWidget);
      expect(find.text('91분'), findsWidgets);
      await tester.scrollUntilVisible(
        find.textContaining('서버가 반환한 보충 학습'),
        150,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.textContaining('서버가 반환한 보충 학습'), findsOneWidget);
      expect(transport.calls.map((u) => u.path), [
        '/study-reschedule-saved',
        '/study-plan',
      ]);
      expect(
        transport.calls.every((u) => u.queryParameters['goalId'] == '17'),
        isTrue,
      );
    },
  );

  testWidgets('explicit reload replaces cached displayed values using GET', (
    tester,
  ) async {
    final transport = PlanTransport();
    await showPlan(tester, transport);
    transport.savedCarry = 12;
    await tester.ensureVisible(find.byKey(const Key('storedPlanButton')));
    await tester.tap(find.byKey(const Key('storedPlanButton')));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('88분'),
      180,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('88분'), findsOneWidget);
    expect(find.text('91분'), findsNothing);
    expect(transport.calls.length, 2);
    expect(
      transport.calls.every((u) => u.path == '/study-reschedule-saved'),
      isTrue,
    );
  });

  testWidgets('date change queries selected goal and new date without saving', (
    tester,
  ) async {
    final transport = PlanTransport();
    await showPlan(tester, transport);
    await tester.ensureVisible(find.byIcon(Icons.date_range));
    await tester.tap(find.byIcon(Icons.date_range));
    await tester.pumpAndSettle();
    await tester.tap(find.byIcon(Icons.edit_outlined));
    await tester.pumpAndSettle();
    final next = DateTime.now().add(const Duration(days: 1));
    await tester.enterText(
      find.byType(TextField).last,
      '${next.month}/${next.day}/${next.year}',
    );
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    final date =
        '${next.year}-${next.month.toString().padLeft(2, '0')}-${next.day.toString().padLeft(2, '0')}';
    expect(transport.calls.length, 2);
    expect(transport.calls.last.queryParameters, {
      'goalId': '17',
      'date': date,
    });
    expect(find.text('저장된 FINAL 계획'), findsOneWidget);
  });
}
