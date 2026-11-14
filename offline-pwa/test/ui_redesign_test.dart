import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:ai_study_planner_mobile/app_design.dart';
import 'package:ai_study_planner_mobile/main.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/server_ai_history.dart';
import 'package:ai_study_planner_mobile/server_plan_analysis.dart';
import 'package:ai_study_planner_mobile/server_reschedule.dart';
import 'package:ai_study_planner_mobile/server_screens.dart';
import 'package:ai_study_planner_mobile/study_record_entry.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'real_navigation_test.dart' show NavTransport;

class UiTransport extends NavTransport {
  final requested = <(String, Uri)>[];
  String date(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  Map<String, dynamic> plan(int goal, String day) => {
    'goalId': goal,
    'scheduleDate': day,
    'studyMinutes': 100,
    'carryoverMinutes': 9,
    'newStudyMinutes': 91,
    'carryoverTotalPendingMinutes': 60,
    'isScheduleValid': true,
    'hasConflict': false,
    'previewMode': true,
    'previewNotice': '오늘 기록이 현재 상태로 마감된다고 가정한 미저장 계획입니다.',
    'carryoverCalculation': [
      {'provisional': true},
    ],
    'subjectPlans': [
      {
        'subjectId': 1,
        'subjectName': '운영체제',
        'studyMinutes': 100,
        'carryoverMinutes': 9,
        'newStudyMinutes': 91,
      },
    ],
    'timeSlots': [
      {
        'startTime': '$day 19:00',
        'endTime': '$day 19:09',
        'minutes': 9,
        'subjectName': '운영체제',
        'activity': '미완료 개념 보충',
        'type': '공부',
        'allocationType': 'CATCHUP',
      },
      {
        'startTime': '$day 19:09',
        'endTime': '$day 19:50',
        'minutes': 41,
        'subjectName': '운영체제',
        'activity': '개념 복습',
        'type': '공부',
        'allocationType': 'NEW',
      },
      {
        'startTime': '$day 19:50',
        'endTime': '$day 20:00',
        'minutes': 10,
        'activity': '잠깐 쉬어가기',
        'type': '휴식',
      },
      {
        'startTime': '$day 20:00',
        'endTime': '$day 20:50',
        'minutes': 50,
        'subjectName': '운영체제',
        'activity': '문제풀이',
        'type': '공부',
        'allocationType': 'NEW',
      },
    ],
  };
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    requested.add((method, uri));
    final goal = int.tryParse(uri.queryParameters['goalId'] ?? '') ?? 1;
    Object? data;
    switch (uri.path) {
      case '/goals':
        data = [
          {'goalId': 1, 'goalName': '운영체제 개념 학습'},
          {'goalId': 3, 'goalName': '네트워크 복습'},
        ];
      case '/analysis-summary':
        data = {
          'goalId': goal,
          'scope': 'CURRENT_GOAL',
          'goalName': '운영체제 개념 학습',
          'recordCount': 1,
          'totalStudyMinutes': 40,
          'averageUnderstanding': 3.0,
          'averageFocus': 3.0,
          'totalCorrect': 0,
          'totalWrong': 0,
          'mainMessage': '아직 기록이 적어요. 꾸준히 기록하면 나의 학습 흐름을 더 정확하게 확인할 수 있어요.',
        };
      case '/study-reschedule-preview':
        data = plan(goal, date(DateTime.now().add(const Duration(days: 1))));
      case '/study-reschedule-saved':
        data = {
          ...plan(goal, uri.queryParameters['date']!),
          'saved': true,
          'planStatus': 'FINAL',
          'planSaved': true,
        };
      case '/ai-responses':
        data = [
          {
            'responseId': 1,
            'goalId': goal,
            'feature': uri.queryParameters['feature'],
            'actualProvider': 'gemini',
            'model': 'mock-gemini',
            'responseSource': 'UNKNOWN',
            'responseComplete': true,
            'createdAt': '2026-10-28T13:00:00',
            'questionId': uri.queryParameters['feature'] == 'QUESTION'
                ? 7
                : null,
            'responseBody': '## 공부를 이어가는 방법\n\n**UI 확인용 가상 응답입니다. 실제 AI 호출이 아닙니다.**\n\n- 프로세스와 스레드의 차이를 내 말로 설명해 보세요.\n- 개념을 정리한 뒤 짧은 문제로 이해를 확인해 보세요.\n\n기록이 적으므로 학습 성과를 단정할 수 없습니다.',
          },
        ];
      default:
        return super.send(method, uri, headers, body);
    }
    expect(method, 'GET');
    return Reply(200, jsonEncode(data));
  }
}

PlannerApi uiApi(UiTransport t) => PlannerApi(
  base: Uri.parse('http://localhost:8080'),
  transport: t,
  debugLoopback: true,
);
ThemeData reviewTheme(Brightness brightness) {
  final theme = AppDesign.theme(brightness);
  return theme.copyWith(
    textTheme: theme.textTheme.apply(fontFamily: 'UiReviewKorean'),
    primaryTextTheme: theme.primaryTextTheme.apply(
      fontFamily: 'UiReviewKorean',
    ),
    appBarTheme: theme.appBarTheme.copyWith(
      titleTextStyle: theme.appBarTheme.titleTextStyle?.copyWith(
        fontFamily: 'UiReviewKorean',
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: theme.filledButtonTheme.style?.copyWith(
        textStyle: const WidgetStatePropertyAll(
          TextStyle(
            fontFamily: 'UiReviewKorean',
            fontWeight: FontWeight.w700,
            fontSize: 15,
          ),
        ),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: theme.outlinedButtonTheme.style?.copyWith(
        textStyle: const WidgetStatePropertyAll(
          TextStyle(
            fontFamily: 'UiReviewKorean',
            fontWeight: FontWeight.w600,
            fontSize: 14,
          ),
        ),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: theme.textButtonTheme.style?.copyWith(
        textStyle: const WidgetStatePropertyAll(
          TextStyle(
            fontFamily: 'UiReviewKorean',
            fontWeight: FontWeight.w600,
            fontSize: 14,
          ),
        ),
      ),
    ),
  );
}

Widget shell(
  Widget child, {
  Brightness brightness = Brightness.light,
  double scale = 1,
  bool reduced = false,
  double bottomPadding = 0,
  Key? boundary,
}) => RepaintBoundary(
  key: boundary,
  child: MaterialApp(
    debugShowCheckedModeBanner: false,
    theme: reviewTheme(brightness),
    builder: (context, child) => MediaQuery(
      data: MediaQuery.of(context).copyWith(
        textScaler: TextScaler.linear(scale),
        disableAnimations: reduced,
        padding: EdgeInsets.only(bottom: bottomPadding),
      ),
      child: child!,
    ),
    home: child,
  ),
);
void viewport(WidgetTester tester, double width, double height) {
  tester.view.physicalSize = Size(width, height);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Future<void> selectGoal(WidgetTester t) async {
  await t.ensureVisible(find.byType(DropdownButtonFormField<int>));
  await t.tap(find.byType(DropdownButtonFormField<int>));
  await t.pumpAndSettle();
  await t.tap(find.text('운영체제 개념 학습').last);
  await t.pumpAndSettle();
}

void main() {
  testWidgets('bottom tabs respect system inset and reduced motion', (t) async {
    viewport(t, 360, 800);
    final transport = UiTransport();
    final api = uiApi(transport);
    addTearDown(api.close);
    await t.pumpWidget(
      shell(ServerDashboard(api: api), reduced: true, bottomPadding: 24),
    );
    await t.pumpAndSettle();
    expect(
      t.widget<NavigationBar>(find.byType(NavigationBar)).animationDuration,
      Duration.zero,
    );
    expect(t.getCenter(find.byKey(const Key('realTab0'))).dy, lessThan(776));
    await t.tap(find.byKey(const Key('realTab1')));
    await t.pumpAndSettle();
    expect(
      t.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex,
      1,
    );
    expect(find.text('홈에서 목표를 선택하세요.'), findsOneWidget);
  });
  setUpAll(() async {
    // 검증용 PC 글꼴만 읽는다. 앱 자산으로 복제하거나 배포하지 않는다.
    final font = File('C:/Windows/Fonts/malgun.ttf');
    if (await font.exists()) {
      final loader = FontLoader('UiReviewKorean')
        ..addFont(Future.value(ByteData.sublistView(await font.readAsBytes())));
      await loader.load();
    }
    final icons = File(
      '${Platform.environment['FLUTTER_ROOT'] ?? ''}/bin/cache/artifacts/material_fonts/MaterialIcons-Regular.otf',
    );
    if (await icons.exists()) {
      final loader = FontLoader(
        'MaterialIcons',
      )..addFont(Future.value(ByteData.sublistView(await icons.readAsBytes())));
      await loader.load();
    }
  });
  testWidgets(
    'allocation ratios use response values and animate to new values',
    (t) async {
      await t.pumpWidget(
        shell(
          const Scaffold(
            body: AllocationBar(catchup: 9, newStudy: 91, total: 100),
          ),
        ),
      );
      await t.pumpAndSettle();
      expect(find.text('● 보충 9분 · 9%'), findsOneWidget);
      expect(find.text('● 신규 91분 · 91%'), findsOneWidget);
      expect(
        t
            .widget<FractionallySizedBox>(find.byType(FractionallySizedBox))
            .widthFactor,
        closeTo(.09, .0001),
      );
      await t.pumpWidget(
        shell(
          const Scaffold(
            body: AllocationBar(catchup: 40, newStudy: 60, total: 100),
          ),
        ),
      );
      await t.pumpAndSettle();
      expect(find.text('● 보충 40분 · 40%'), findsOneWidget);
      expect(
        t
            .widget<FractionallySizedBox>(find.byType(FractionallySizedBox))
            .widthFactor,
        closeTo(.4, .0001),
      );
    },
  );
  testWidgets('zero allocation has no divide by zero or invented study', (
    t,
  ) async {
    await t.pumpWidget(
      shell(
        const Scaffold(body: AllocationBar(catchup: 0, newStudy: 0, total: 0)),
      ),
    );
    await t.pumpAndSettle();
    expect(find.text('배정된 학습 시간이 없습니다.'), findsOneWidget);
    expect(find.byType(FractionallySizedBox), findsNothing);
    expect(t.takeException(), isNull);
  });
  testWidgets('invalid allocation and unmeasured score remain unknown', (
    t,
  ) async {
    await t.pumpWidget(
      shell(
        Scaffold(
          body: Column(
            children: [
              const AllocationBar(catchup: 9, newStudy: 91, total: 101),
              ScoreMeter(label: '이해도', score: measuredNumber('미측정')),
            ],
          ),
        ),
      ),
    );
    await t.pumpAndSettle();
    expect(find.text('정보 없음'), findsOneWidget);
    expect(find.byType(LinearProgressIndicator), findsNothing);
    expect(find.byType(StateNotice), findsOneWidget);
  });
  testWidgets('reduced motion disables allocation transition', (t) async {
    await t.pumpWidget(
      shell(
        const Scaffold(
          body: AllocationBar(catchup: 25, newStudy: 75, total: 100),
        ),
        reduced: true,
      ),
    );
    await t.pumpAndSettle();
    expect(
      t
          .widget<TweenAnimationBuilder<double>>(
            find.byType(TweenAnimationBuilder<double>),
          )
          .duration,
      Duration.zero,
    );
  });
  testWidgets('system dark preference changes actual app theme', (t) async {
    t.binding.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
    addTearDown(t.binding.platformDispatcher.clearPlatformBrightnessTestValue);
    await t.pumpWidget(const PlannerApp());
    await t.pumpAndSettle();
    expect(
      Theme.of(t.element(find.byKey(const Key('loginName')))).brightness,
      Brightness.dark,
    );
  });
  test('light and dark core text pairs have readable contrast', () {
    double contrast(Color a, Color b) {
      final x = a.computeLuminance(), y = b.computeLuminance();
      return x > y ? (x + .05) / (y + .05) : (y + .05) / (x + .05);
    }

    for (final mode in Brightness.values) {
      final c = AppDesign.theme(mode).colorScheme;
      expect(contrast(c.onSurface, c.surface), greaterThanOrEqualTo(4.5));
      expect(
        contrast(c.onPrimaryContainer, c.primaryContainer),
        greaterThanOrEqualTo(4.5),
      );
      expect(
        contrast(c.onErrorContainer, c.errorContainer),
        greaterThanOrEqualTo(4.5),
      );
    }
  });
  testWidgets('native answer formatting keeps HTML as literal text', (t) async {
    await t.pumpWidget(
      shell(
        const Scaffold(
          body: AnswerText(text: '## 요약\n**핵심** 설명\n<script>alert(1)</script>'),
        ),
      ),
    );
    await t.pumpAndSettle();
    final answer = t.widget<SelectableText>(find.byType(SelectableText));
    expect(
      answer.textSpan!.toPlainText(),
      contains('<script>alert(1)</script>'),
    );
    expect(answer.textSpan!.toPlainText(), contains('핵심'));
  });
  for (final scale in [1.0, 1.6, 2.0]) {
    testWidgets('360dp real screens support text scale $scale and dark mode', (
      t,
    ) async {
      viewport(t, 360, 800);
      final transport = UiTransport();
      final api = uiApi(transport);
      addTearDown(api.close);
      final pages = <Widget>[
        ServerLogin(api: api),
        ServerDashboard(api: api),
        ServerStudyPlan(api: api, goalId: 1, goalName: '운영체제 개념 학습'),
        ServerStudyAnalysis(api: api, goalId: 1, goalName: '운영체제 개념 학습'),
        ServerReschedule(api: api, goalId: 1, goalName: '운영체제 개념 학습'),
        StudyRecordEntry(api: api, goalId: 1, goalName: '운영체제 개념 학습'),
        ServerAiHistory(api: api, goalId: 1, goalName: '운영체제 개념 학습'),
      ];
      for (final page in pages) {
        await t.pumpWidget(
          shell(
            page,
            brightness: Brightness.dark,
            scale: scale,
            reduced: true,
            boundary: UniqueKey(),
          ),
        );
        await t.pumpAndSettle();
        if (page is ServerDashboard) await selectGoal(t);
        if (find.byType(Scrollable).evaluate().isNotEmpty) {
          for (int i = 0; i < 3; i++) {
            await t.drag(find.byType(Scrollable).first, const Offset(0, -600));
            await t.pumpAndSettle();
            expect(
              t.takeException(),
              isNull,
              reason: page.runtimeType.toString(),
            );
          }
        }
        expect(t.takeException(), isNull, reason: page.runtimeType.toString());
      }
      expect(transport.requested.every((c) => c.$1 == 'GET'), true);
    });
  }
  testWidgets('themed input scrolls above the keyboard on a small screen', (
    t,
  ) async {
    viewport(t, 360, 640);
    final transport = UiTransport();
    final api = uiApi(transport);
    addTearDown(api.close);
    await t.pumpWidget(
      shell(StudyRecordEntry(api: api, goalId: 1, goalName: '운영체제 개념 학습')),
    );
    await t.pumpAndSettle();
    t.view.viewInsets = const FakeViewPadding(bottom: 280);
    addTearDown(t.view.resetViewInsets);
    await t.pumpAndSettle();
    await t.scrollUntilVisible(
      find.byKey(const Key('entryMemo')),
      180,
      scrollable: find.byType(Scrollable).first,
    );
    await t.enterText(find.byKey(const Key('entryMemo')), '화면 검증용 메모');
    await t.scrollUntilVisible(
      find.byKey(const Key('entrySave')),
      160,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.byKey(const Key('entrySave')).hitTestable(), findsOneWidget);
    expect(t.takeException(), isNull);
  });
  testWidgets('capture rendered Mock UI evidence without server or AI', (
    t,
  ) async {
    viewport(t, 390, 844);
    final transport = UiTransport();
    final api = uiApi(transport);
    addTearDown(api.close);
    final dir = Directory('../build/reports/flutter-ui-review/screenshots');
    await t.runAsync(() => dir.create(recursive: true));
    Future<void> capture(
      String name,
      Widget page, {
      Brightness mode = Brightness.light,
      bool goal = false,
      String? focusTitle,
    }) async {
      final key = GlobalKey();
      await t.pumpWidget(
        shell(
          Column(
            children: [
              Container(
                height: 24,
                color: const Color(0xFFFFE6A9),
                alignment: Alignment.center,
                child: const Text(
                  'MOCK UI · 가상 데이터 · 서버 미연결',
                  style: TextStyle(
                    fontFamily: 'UiReviewKorean',
                    fontSize: 12,
                    color: Colors.black,
                    decoration: TextDecoration.none,
                  ),
                ),
              ),
              Expanded(child: page),
            ],
          ),
          brightness: mode,
          boundary: key,
        ),
      );
      await t.pumpAndSettle();
      if (goal) await selectGoal(t);
      if (page is ServerDashboard) {
        await t.drag(find.byType(Scrollable).first, const Offset(0, 1400));
        await t.pumpAndSettle();
      }
      if (focusTitle != null) {
        await t.scrollUntilVisible(
          find.text(focusTitle),
          160,
          scrollable: find.byType(Scrollable).first,
        );
        await t.pumpAndSettle();
      }
      final boundary =
          key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      await t.runAsync(() async {
        final image = await boundary.toImage(pixelRatio: 1);
        final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
        await File('${dir.path}/$name.png')
            .writeAsBytes(bytes!.buffer.asUint8List());
        image.dispose();
      });
      expect(t.takeException(), isNull);
    }

    await capture('01-login-light-mock', ServerLogin(api: api));
    await capture(
      '02-dashboard-light-mock',
      ServerDashboard(api: api),
      goal: true,
    );
    await capture(
      '03-plan-light-mock',
      ServerStudyPlan(api: api, goalId: 1, goalName: '운영체제 개념 학습'),
    );
    await capture(
      '04-analysis-light-mock',
      ServerStudyAnalysis(api: api, goalId: 1, goalName: '운영체제 개념 학습'),
    );
    await capture(
      '05-reschedule-light-mock',
      ServerReschedule(api: api, goalId: 1, goalName: '운영체제 개념 학습'),
    );
    await capture(
      '06-record-entry-light-mock',
      StudyRecordEntry(api: api, goalId: 1, goalName: '운영체제 개념 학습'),
    );
    await capture(
      '07-ai-history-light-mock',
      ServerAiHistory(api: api, goalId: 1, goalName: '운영체제 개념 학습'),
    );
    await capture(
      '08-reschedule-dark-mock',
      ServerReschedule(api: api, goalId: 1, goalName: '운영체제 개념 학습'),
      mode: Brightness.dark,
    );
    await capture(
      '09-plan-timeline-light-mock',
      ServerStudyPlan(api: api, goalId: 1, goalName: '운영체제 개념 학습'),
      focusTitle: '선택 날짜 상세 시간표',
    );
    await capture(
      '10-record-ratings-light-mock',
      StudyRecordEntry(api: api, goalId: 1, goalName: '운영체제 개념 학습'),
      focusTitle: '나의 학습 컨디션',
    );
    expect(transport.requested.every((c) => c.$1 == 'GET'), true);
  });
}
