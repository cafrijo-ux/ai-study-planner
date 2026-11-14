import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/server_plan_analysis.dart';

class RecordsTransport implements Transport {
  RecordsTransport(this.subjects, this.logs, {this.logStatus = 200});
  final Object subjects, logs;
  final int logStatus;
  final List<Uri> calls = [];
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    expect(method, 'GET');
    expect(body, isNull);
    calls.add(uri);
    if (uri.path == '/analysis-summary') {
      return Reply(
        200,
        jsonEncode({
          'goalId': 1,
          'scope': 'CURRENT_GOAL',
          'goalName': '목표',
          'recordCount': 1,
          'totalStudyMinutes': 40,
          'averageUnderstanding': 3,
          'averageFocus': 3,
          'totalCorrect': 0,
          'totalWrong': 0,
          'mainMessage': '기록 부족',
        }),
      );
    }
    if (uri.path == '/subjects') return Reply(200, jsonEncode(subjects));
    if (uri.path == '/study-logs') return Reply(logStatus, jsonEncode(logs));
    throw StateError('Unexpected route');
  }

  @override
  void close() {}
}

final subjects = [
  {'subjectId': 1, 'goalId': 1, 'userId': 1, 'subjectName': '운영체제'},
];
Map<String, dynamic> log(int id, String date, {int subject = 1}) => {
  'logId': id,
  'subjectId': subject,
  'userId': 1,
  'studyDate': date,
  'studyMinutes': 40,
  'understandingScore': 3,
  'focusScore': 3,
};
PlannerApi api(RecordsTransport t) => PlannerApi(
  base: Uri.parse('http://localhost:8080'),
  transport: t,
  debugLoopback: true,
);
void main() {
  test(
    'subject join filters other goals and sorts dates without fabricating days',
    () async {
      final t = RecordsTransport(subjects, [
        log(1, '2026-10-19'),
        log(2, '2026-10-20'),
        log(3, '2026-10-20'),
        log(4, '2026-10-21', subject: 99),
      ]);
      final rows = await api(t).studyRecords(1);
      expect(rows.map((r) => r['logId']), [2, 3, 1]);
      expect(rows.every((r) => r['subjectName'] == '운영체제'), true);
      expect(t.calls.first.queryParameters, {'goalId': '1'});
      expect(t.calls.last.path, '/study-logs');
    },
  );
  test('no subjects returns empty without loading unrelated logs', () async {
    final t = RecordsTransport([], [log(1, '2026-10-19')]);
    expect(await api(t).studyRecords(3), isEmpty);
    expect(t.calls.length, 1);
  });
  test('empty logs and null measurements stay empty or null', () async {
    expect(await api(RecordsTransport(subjects, [])).studyRecords(1), isEmpty);
    final row = log(1, '2026-10-19')
      ..['understandingScore'] = null
      ..['focusScore'] = null;
    expect(
      (await api(RecordsTransport(subjects, [row])).studyRecords(1))
          .single['focusScore'],
      isNull,
    );
  });
  test(
    'wrong subject goal, wrong owner and invalid date are rejected',
    () async {
      await expectLater(
        api(
          RecordsTransport([
            {'subjectId': 1, 'goalId': 3, 'userId': 1, 'subjectName': '다른 목표'},
          ], []),
        ).studyRecords(1),
        throwsA(isA<ApiFailure>()),
      );
      for (final row in [
        log(1, '2026-02-31'),
        log(1, '2026-10-19')..['userId'] = 2,
      ]) {
        await expectLater(
          api(RecordsTransport(subjects, [row])).studyRecords(1),
          throwsA(isA<ApiFailure>()),
        );
      }
    },
  );
  test('HTTP failure is not an empty record list', () async {
    await expectLater(
      api(RecordsTransport(subjects, {}, logStatus: 500)).studyRecords(1),
      throwsA(isA<ApiFailure>()),
    );
  });
  testWidgets('dated record is displayed and unmeasured scores are not zero', (
    tester,
  ) async {
    final row = log(1, '2026-10-19')..['focusScore'] = null;
    await tester.pumpWidget(
      MaterialApp(
        home: ServerStudyAnalysis(
          api: api(RecordsTransport(subjects, [row])),
          goalId: 1,
          goalName: '목표',
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.drag(find.byType(ListView), const Offset(0, -700));
    await tester.pumpAndSettle();
    expect(find.text('2026-10-19'), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('analysis-record-1')),
        matching: find.text('운영체제'),
      ),
      findsOneWidget,
    );
    expect(find.text('공부시간: 40분'), findsOneWidget);
    expect(find.text('이해도: 3.0 / 5'), findsOneWidget);
    expect(find.text('집중도: 정보 없음'), findsOneWidget);
    expect(find.text('피로도: 정보 없음'), findsOneWidget);
    expect(find.text('2026-10-20'), findsNothing);
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'server fatigue score is shown without changing existing statistics',
    (tester) async {
      final row = log(1, '2026-10-19')..['fatigueScore'] = 2;
      await tester.pumpWidget(
        MaterialApp(
          home: ServerStudyAnalysis(
            api: api(RecordsTransport(subjects, [row])),
            goalId: 1,
            goalName: '목표',
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.drag(find.byType(ListView), const Offset(0, -700));
      await tester.pumpAndSettle();
      expect(find.text('피로도: 2.0 / 5'), findsOneWidget);
      expect(find.text('공부시간: 40분'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('empty records have explicit empty state', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: ServerStudyAnalysis(
          api: api(RecordsTransport(subjects, [])),
          goalId: 1,
          goalName: '목표',
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.drag(find.byType(ListView), const Offset(0, -700));
    await tester.pumpAndSettle();
    expect(find.text('공부 기록 없음'), findsOneWidget);
  });
  testWidgets(
    'record failure retains existing summary and shows error not no records',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: ServerStudyAnalysis(
            api: api(RecordsTransport(subjects, {}, logStatus: 500)),
            goalId: 1,
            goalName: '목표',
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('현재 목표 · 실제 학습 누적'), findsOneWidget);
      await tester.drag(find.byType(ListView), const Offset(0, -700));
      await tester.pumpAndSettle();
      expect(find.text('날짜별 기록 조회 오류'), findsOneWidget);
      expect(find.text('공부 기록 없음'), findsNothing);
    },
  );
}
