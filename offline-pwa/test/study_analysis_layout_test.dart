import 'dart:async';
import 'dart:convert';

import 'package:ai_study_planner_mobile/app_design.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/plan_calendar.dart';
import 'package:ai_study_planner_mobile/server_plan_analysis.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class AnalysisMock implements Transport {
  final calls = <(String, Uri)>[];
  final summaryHolds = <int, Completer<void>>{};
  Completer<void>? firstLogHold;
  int logReads = 0;
  int summaryStatus = 200, logStatus = 200;
  final summaries = <int, Map<String, dynamic>>{};
  final subjects = [
    {'subjectId': 10, 'goalId': 1, 'userId': 1, 'subjectName': '가상 운영체제'},
    {'subjectId': 11, 'goalId': 1, 'userId': 1, 'subjectName': '가상 네트워크'},
    {'subjectId': 20, 'goalId': 2, 'userId': 1, 'subjectName': '목표 B 과목'},
  ];
  final rows = <Map<String, dynamic>>[
    {
      'logId': 1,
      'subjectId': 10,
      'userId': 1,
      'studyDate': '2026-11-01',
      'studyMinutes': 40,
      'focusScore': 3,
      'understandingScore': 4,
      'fatigueScore': 2,
      'studyContent': '프로세스와 스레드 복습',
      'studyMethod': '개념 정리',
      'memo': '가상 메모',
    },
    {
      'logId': 2,
      'subjectId': 11,
      'userId': 1,
      'studyDate': '2026-10-31',
      'studyMinutes': 20,
    },
    {
      'logId': 3,
      'subjectId': 20,
      'userId': 1,
      'studyDate': '2026-11-01',
      'studyMinutes': 15,
    },
  ];
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    expect(method, 'GET');
    expect(body, isNull);
    calls.add((method, uri));
    final goal = int.tryParse(uri.queryParameters['goalId'] ?? '') ?? 1;
    switch (uri.path) {
      case '/analysis-summary':
        await summaryHolds[goal]?.future;
        return Reply(
          summaryStatus,
          jsonEncode(
            summaries[goal] ??
                {
                  'goalId': goal,
                  'scope': 'CURRENT_GOAL',
                  'totalStudyMinutes': goal == 1 ? 60 : 15,
                  'recordCount': goal == 1 ? 2 : 1,
                  'averageUnderstanding': 4,
                  'averageFocus': 3,
                  'totalCorrect': 6,
                  'totalWrong': 2,
                  'mainMessage': '가상 서버 통계',
                },
          ),
        );
      case '/subjects':
        return Reply(
          200,
          jsonEncode(subjects.where((s) => s['goalId'] == goal).toList()),
        );
      case '/study-logs':
        if (++logReads == 1) await firstLogHold?.future;
        return Reply(logStatus, jsonEncode(rows));
      default:
        throw StateError('Unexpected Mock route');
    }
  }

  @override
  void close() {}
}

PlannerApi analysisApi(AnalysisMock mock) => PlannerApi(
  base: Uri.parse('http://localhost:8080'),
  transport: mock,
  debugLoopback: true,
);

Finder analysisScroll() => find
    .descendant(
      of: find.byKey(const Key('analysisScroll')),
      matching: find.byType(Scrollable),
    )
    .first;

Future<void> reveal(WidgetTester t, Finder finder) async {
  await t.scrollUntilVisible(
    finder,
    180,
    scrollable: analysisScroll(),
    maxScrolls: 70,
  );
  await t.pumpAndSettle();
}

Future<void> host(
  WidgetTester t,
  AnalysisMock m, {
  bool dark = false,
  double scale = 1,
  bool reduced = false,
  VoidCallback? expired,
}) async {
  final api = analysisApi(m);
  addTearDown(api.close);
  await t.pumpWidget(
    MaterialApp(
      theme: AppDesign.theme(dark ? Brightness.dark : Brightness.light),
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(
          textScaler: TextScaler.linear(scale),
          disableAnimations: reduced,
        ),
        child: child!,
      ),
      home: ServerStudyAnalysis(
        api: api,
        goalId: 1,
        goalName: '가상 학습 목표',
        onSessionExpired: expired,
      ),
    ),
  );
}

void main() {
  testWidgets(
    'server summary and recorded subject shares match exact inputs using only GET',
    (t) async {
      final m = AnalysisMock();
      final original = jsonEncode(m.rows);
      await host(t, m);
      await t.pumpAndSettle();
      for (final text in ['60분', '2건', '4.0 / 5', '3.0 / 5', '75.0%']) {
        expect(find.text(text), findsOneWidget);
      }
      await reveal(t, find.byKey(const ValueKey('analysis-subject-bar-10')));
      final bar = t.widget<LinearProgressIndicator>(
        find.byKey(const ValueKey('analysis-subject-bar-10')),
      );
      expect(bar.value, closeTo(40 / 60, .00001));
      expect(bar.color, subjectPlanColor(10));
      await reveal(t, find.byKey(const ValueKey('analysis-record-1')));
      final card = find.byKey(const ValueKey('analysis-record-1'));
      expect(
        find.descendant(of: card, matching: find.text('학습 내용: 프로세스와 스레드 복습')),
        findsOneWidget,
      );
      expect(
        find.descendant(of: card, matching: find.text('피로도: 2.0 / 5')),
        findsOneWidget,
      );
      final container = t.widget<Container>(
        find.descendant(of: card, matching: find.byType(Container)).first,
      );
      expect(
        ((container.decoration as BoxDecoration).border as Border).left.color,
        subjectPlanColor(10),
      );
      expect(jsonEncode(m.rows), original);
      expect(m.calls.map((c) => c.$2.path), [
        '/analysis-summary',
        '/subjects',
        '/study-logs',
      ]);
      expect(m.calls.first.$2.queryParameters, {
        'scope': 'current',
        'goalId': '1',
      });
      expect(find.text('목표 B 과목'), findsNothing);
      expect(t.takeException(), isNull);
    },
  );

  for (final dark in [false, true]) {
    for (final scale in [1.0, 1.6, 2.0]) {
      testWidgets(
        'analysis 320dp scale $scale dark $dark long names and history scroll',
        (t) async {
          t.view.physicalSize = const Size(320, 800);
          t.view.devicePixelRatio = 1;
          addTearDown(t.view.resetPhysicalSize);
          addTearDown(t.view.resetDevicePixelRatio);
          final m = AnalysisMock();
          m.subjects.first['subjectName'] = List.filled(8, '긴 과목 이름').join(' ');
          m.rows.first['studyContent'] = List.filled(
            15,
            '긴 학습 내용을 줄바꿈하며 확인합니다.',
          ).join('\n');
          await host(t, m, dark: dark, scale: scale);
          await t.pumpAndSettle();
          await reveal(t, find.byKey(const ValueKey('analysis-record-2')));
          expect(find.text('공부시간: 20분').hitTestable(), findsOneWidget);
          expect(find.text('2026-11-02'), findsNothing);
          expect(t.takeException(), isNull);
        },
      );
    }
  }

  testWidgets(
    'null measurements stay unknown and incomplete minutes produce no share bar',
    (t) async {
      final m = AnalysisMock();
      m.summaries[1] = {
        'goalId': 1,
        'scope': 'CURRENT_GOAL',
        'averageUnderstanding': 0,
        'averageFocus': 6,
      };
      m.rows.first['studyMinutes'] = null;
      await host(t, m);
      await t.pumpAndSettle();
      expect(find.text('정보 없음'), findsWidgets);
      expect(find.text('0분'), findsNothing);
      expect(find.text('0건'), findsNothing);
      await reveal(t, find.text('과목별 학습 현황'));
      expect(
        find.byKey(const ValueKey('analysis-subject-bar-10')),
        findsNothing,
      );
      await reveal(t, find.byKey(const ValueKey('analysis-record-1')));
      expect(find.text('공부시간: 정보 없음'), findsOneWidget);
      expect(t.takeException(), isNull);
    },
  );

  testWidgets(
    'pending records are not empty and completed empty result is explicit',
    (t) async {
      final m = AnalysisMock()..firstLogHold = Completer<void>();
      m.rows.clear();
      m.summaries[1] = {
        'goalId': 1,
        'scope': 'CURRENT_GOAL',
        'recordCount': 0,
        'totalStudyMinutes': 0,
      };
      await host(t, m);
      await t.pump();
      expect(find.text('공부 기록 없음'), findsNothing);
      expect(find.text('선택 목표의 학습 데이터를 조회하고 있습니다.'), findsOneWidget);
      m.firstLogHold!.complete();
      await t.pumpAndSettle();
      await reveal(t, find.text('공부 기록 없음'));
      expect(find.text('공부 기록 없음'), findsOneWidget);
      expect(find.text('과목별 학습 현황'), findsNothing);
      expect(m.calls.length, 3);
    },
  );

  for (final recordsFailure in [false, true]) {
    testWidgets(
      'analysis ${recordsFailure ? 'records' : 'summary'} error is not empty and explicit refresh recovers',
      (t) async {
        final m = AnalysisMock();
        if (recordsFailure) {
          m.logStatus = 503;
        } else {
          m.summaryStatus = 503;
        }
        await host(t, m);
        await t.pumpAndSettle();
        await reveal(t, find.text(recordsFailure ? '날짜별 기록 조회 오류' : '다시 조회'));
        expect(find.text('공부 기록 없음'), findsNothing);
        expect(find.textContaining('503'), findsOneWidget);
        final retry = find.text(recordsFailure ? '기록 다시 조회' : '다시 조회');
        await reveal(t, retry);
        m.logStatus = 200;
        m.summaryStatus = 200;
        await t.tap(retry);
        await t.pumpAndSettle();
        await reveal(t, find.byKey(const ValueKey('analysis-record-1')));
        expect(find.text('공부시간: 40분'), findsOneWidget);
        expect(t.takeException(), isNull);
      },
    );
  }

  for (final status in [401, 403]) {
    testWidgets(
      'analysis $status invokes existing session expiry without automatic retry',
      (t) async {
        final m = AnalysisMock()..summaryStatus = status;
        var expired = 0;
        await host(t, m, expired: () => expired++);
        await t.pump(const Duration(milliseconds: 300));
        expect(expired, 1);
        expect(m.calls.length, 1);
      },
    );
  }

  for (final reduced in [false, true]) {
    testWidgets(
      'analysis incoming summary has real mid-frame fade and respects reduced motion $reduced',
      (t) async {
        final m = AnalysisMock()..firstLogHold = Completer<void>();
        await host(t, m, reduced: reduced);
        await t.pump();
        await t.pump(const Duration(milliseconds: 100));
        final opacity = t
            .widget<Opacity>(find.byKey(const Key('analysisSummaryOpacity')))
            .opacity;
        if (reduced) {
          expect(opacity, 1);
        } else {
          expect(opacity, greaterThan(0));
          expect(opacity, lessThan(1));
        }
        m.firstLogHold!.complete();
        await t.pumpAndSettle();
        expect(
          t
              .widget<Opacity>(find.byKey(const Key('analysisSummaryOpacity')))
              .opacity,
          1,
        );
        expect(t.takeException(), isNull);
      },
    );
  }
  for (final lateRecords in [false, true]) {
    testWidgets(
      'analysis clears goal A and ignores late ${lateRecords ? 'records' : 'summary'}',
      (t) async {
        final m = AnalysisMock(), goal = ValueNotifier<int>(1);
        final hold = Completer<void>();
        if (lateRecords) {
          m.firstLogHold = hold;
        } else {
          m.summaryHolds[1] = hold;
        }
        final api = analysisApi(m);
        addTearDown(api.close);
        addTearDown(goal.dispose);
        await t.pumpWidget(
          MaterialApp(
            home: ValueListenableBuilder<int>(
              valueListenable: goal,
              builder: (_, id, _) =>
                  ServerStudyAnalysis(api: api, goalId: id, goalName: '목표 $id'),
            ),
          ),
        );
        await t.pump();
        goal.value = 2;
        await t.pump();
        expect(find.text('60분'), findsNothing);
        await t.pumpAndSettle();
        expect(find.text('15분'), findsWidgets);
        hold.complete();
        await t.pumpAndSettle();
        expect(find.text('60분'), findsNothing);
        await reveal(t, find.byKey(const ValueKey('analysis-record-3')));
        expect(find.text('목표 B 과목'), findsWidgets);
        expect(find.text('가상 운영체제'), findsNothing);
        expect(t.takeException(), isNull);
      },
    );
  }
}
