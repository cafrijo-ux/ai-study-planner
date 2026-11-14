import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:ai_study_planner_mobile/app_design.dart';
import 'package:ai_study_planner_mobile/plan_calendar.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/server_plan_analysis.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> calendarData(
  String date, {
  int goal = 1,
  String status = 'FINAL',
}) => {
  'goalId': goal,
  'scheduleDate': date,
  'planDate': date,
  'saved': true,
  'planSaved': true,
  'planStatus': status,
  'studyMinutes': 200,
  'carryoverMinutes': 9,
  'newStudyMinutes': 191,
  'carryoverTotalPendingMinutes': 60,
  'subjectPlans': [
    {
      'subjectId': 1,
      'subjectName': '운영체제',
      'studyMinutes': 50,
      'carryoverMinutes': 9,
      'newStudyMinutes': 41,
    },
    {
      'subjectId': 2,
      'subjectName': '네트워크',
      'studyMinutes': 150,
      'carryoverMinutes': 0,
      'newStudyMinutes': 150,
    },
  ],
  'timeSlots': [
    {
      'subjectId': 1,
      'subjectName': '운영체제',
      'type': '공부',
      'allocationType': 'CATCHUP',
      'activity': '취약 개념 보충',
      'minutes': 9,
      'startTime': '$date 09:00',
      'endTime': '$date 09:09',
    },
    {
      'subjectId': 1,
      'subjectName': '운영체제',
      'type': '공부',
      'allocationType': 'NEW',
      'activity': '문제풀이',
      'minutes': 41,
      'startTime': '$date 09:09',
      'endTime': '$date 09:50',
    },
    {
      'subjectId': null,
      'subjectName': null,
      'type': '휴식',
      'allocationType': 'BREAK',
      'activity': '휴식',
      'minutes': 10,
      'startTime': '$date 09:50',
      'endTime': '$date 10:00',
    },
    {
      'subjectId': 2,
      'subjectName': '네트워크',
      'type': '공부',
      'allocationType': 'NEW',
      'activity': '개념 복습',
      'minutes': 150,
      'startTime': '$date 10:00',
      'endTime': '$date 12:30',
    },
  ],
};

class CalendarTransport implements Transport {
  final calls = <(String, Uri)>[];
  final held = <String, Completer<Reply>>{};
  int? fail;
  Map<String, dynamic>? responseOverride;
  Reply monthReply(Uri uri) {
    final g = int.parse(uri.queryParameters['goalId']!),
        m = uri.queryParameters['month']!;
    return Reply(
      200,
      jsonEncode(
        responseOverride ??
            {
              'goalId': g,
              'month': m,
              'plans': [calendarData('$m-01', goal: g)],
            },
      ),
    );
  }

  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    calls.add((method, uri));
    expect(method, 'GET');
    if (uri.path == '/study-reschedule-calendar') {
      if (fail != null) return Reply(fail!, '{}');
      final hold =
          held['${uri.queryParameters['goalId']}/${uri.queryParameters['month']}'];
      return hold == null ? monthReply(uri) : hold.future;
    }
    if (uri.path == '/study-reschedule-saved') {
      return Reply(
        200,
        jsonEncode({
          'saved': false,
          'goalId': int.parse(uri.queryParameters['goalId']!),
          'planDate': uri.queryParameters['date'],
        }),
      );
    }
    throw StateError('Unexpected route ${uri.path}');
  }

  @override
  void close() {}
}

PlannerApi calendarApi(CalendarTransport t) => PlannerApi(
  base: Uri.parse('http://localhost:8080'),
  transport: t,
  debugLoopback: true,
);

Future<void> calendarHost(
  WidgetTester t, {
  DateTime? initial,
  List<CalendarPlan>? plans,
  double scale = 1,
  bool dark = true,
  bool reduceMotion = false,
  GlobalKey? boundary,
}) async {
  var month = initial ?? DateTime(2026, 11),
      selected = initial ?? DateTime(2026, 11, 1);
  final data =
      plans ??
      [
        CalendarPlan(
          calendarData('2026-11-01'),
          goalId: 1,
          source: CalendarPlanSource.saved,
        ),
      ];
  var theme = AppDesign.theme(dark ? Brightness.dark : Brightness.light);
  theme = theme.copyWith(
    textTheme: theme.textTheme.apply(fontFamily: 'CalendarReviewKorean'),
  );
  await t.pumpWidget(
    MaterialApp(
      theme: theme,
      home: MediaQuery(
        data: MediaQueryData(
          size: const Size(360, 900),
          textScaler: TextScaler.linear(scale),
          disableAnimations: reduceMotion,
        ),
        child: Scaffold(
          body: RepaintBoundary(
            key: boundary,
            child: StatefulBuilder(
              builder: (context, set) => SingleChildScrollView(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const Text(
                        'MOCK UI · 서버 미연결',
                        style: TextStyle(color: Colors.orange),
                      ),
                      const Text('목표: 가상 학습 목표 A'),
                      PlanMonthCalendar(
                        key: ValueKey(data.firstOrNull?.goalId),
                        month: month,
                        selected: selected,
                        today: DateTime(2026, 11, 1),
                        plans: data,
                        onMonth: (d) => set(() => month = d),
                        onDay: (d) => set(() {
                          selected = d;
                          month = DateTime(d.year, d.month);
                        }),
                      ),
                      if (data
                              .where((p) => p.dateKey == planDateKey(selected))
                              .firstOrNull
                          case final p?)
                        PlanScheduleDetails(plan: p, goalName: '가상 학습 목표 A')
                      else
                        const Text('일정 없음'),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await t.pumpAndSettle();
}

AnimationController monthMotion(WidgetTester t) =>
    t
            .widget<AnimatedBuilder>(
              find
                  .descendant(
                    of: find.byKey(const Key('calendar-month-transition')),
                    matching: find.byType(AnimatedBuilder),
                  )
                  .first,
            )
            .animation
        as AnimationController;

Future<TestGesture> calendarDrag(WidgetTester t, double distance) async {
  final rect = t.getRect(find.byKey(const Key('calendar-swipe-surface')));
  final gesture = await t.startGesture(Offset(rect.center.dx, rect.top + 40));
  await gesture.moveBy(Offset(distance, 0));
  await t.pump();
  return gesture;
}

void main() {
  test(
    'PC and Android parse the same saved calendar fixture and subject palette',
    () {
      final fixture = jsonDecode(
        File('../src/test/fixtures/plan-calendar-contract.json')
            .readAsStringSync(),
      ) as Map<String, dynamic>;
      final p = CalendarPlan(
        Map<String, dynamic>.from((fixture['plans'] as List).first as Map),
        goalId: fixture['goalId'] as int,
        source: CalendarPlanSource.saved,
      );
      expect(p.data['studyMinutes'], 200);
      expect(p.data['carryoverMinutes'], 9);
      expect(p.data['newStudyMinutes'], 191);
      expect(p.slots.length, 4);
      expect(p.dateKey, '2026-11-01');
      for (var i = 0; i < 8; i++) {
        final hex = (fixture['palette'] as List)[i] as String;
        expect(
          subjectPlanColor(i + 1),
          Color(0xff000000 | int.parse(hex.substring(1), radix: 16)),
        );
      }
    },
  );
  setUpAll(() async {
    final font = File('C:/Windows/Fonts/malgun.ttf');
    if (await font.exists()) {
      final loader = FontLoader('CalendarReviewKorean')
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
  test(
    'server plan parser retains totals slots activities and stable ID colors',
    () {
      final p = CalendarPlan(
        calendarData('2026-11-01'),
        goalId: 1,
        source: CalendarPlanSource.saved,
      );
      expect(p.dateKey, '2026-11-01');
      expect(p.slots.length, 4);
      expect(p.subjects.length, 2);
      expect(p.data['newStudyMinutes'], 191);
      expect(p.status, contains('FINAL'));
      expect(
        subjectPlanColor(p.subjects.first['subjectId']),
        subjectPlanColor(1),
      );
      expect(subjectPlanColor(1), isNot(subjectPlanColor(2)));
      expect(subjectPlanColor(null), isNot(subjectPlanColor(1)));
    },
  );
  test('preview draft final and calculated sources remain distinct', () {
    for (final s in CalendarPlanSource.values) {
      expect(
        CalendarPlan(calendarData('2026-11-01'), goalId: 1, source: s).status,
        contains(s == CalendarPlanSource.saved ? '서버 저장본' : '미저장'),
      );
    }
    expect(
      CalendarPlan(
        calendarData('2026-11-01', status: 'DRAFT'),
        goalId: 1,
        source: CalendarPlanSource.saved,
      ).status,
      startsWith('임시 계획 (DRAFT)'),
    );
  });
  test('invalid calendar dates cross-goal and cross-date slots rejected', () {
    expect(() => parsePlanDate('2026-02-30'), throwsA(isA<ApiFailure>()));
    expect(
      () => parsePlanDate('2026-11-01T00:00:00Z'),
      throwsA(isA<ApiFailure>()),
    );
    expect(
      () => CalendarPlan(
        {
          ...calendarData('2026-11-01'),
          'timeSlots': ['invalid'],
        },
        goalId: 1,
        source: CalendarPlanSource.saved,
      ),
      throwsA(isA<ApiFailure>()),
    );
    expect(
      () => CalendarPlan(
        calendarData('2026-11-01', goal: 3),
        goalId: 1,
        source: CalendarPlanSource.saved,
      ),
      throwsA(isA<ApiFailure>()),
    );
    final p = calendarData('2026-11-01');
    (p['timeSlots'] as List).first['startTime'] = '2026-11-02 09:00';
    expect(
      () => CalendarPlan(p, goalId: 1, source: CalendarPlanSource.saved),
      throwsA(isA<ApiFailure>()),
    );
  });
  test('Seoul date uses UTC boundary without PC local timezone', () {
    expect(
      planDateKey(seoulToday(DateTime.parse('2026-12-31T14:59:59Z'))),
      '2026-12-31',
    );
    expect(
      planDateKey(seoulToday(DateTime.parse('2026-12-31T15:00:00Z'))),
      '2027-01-01',
    );
  });
  test(
    'monthly API is one GET with exact goal/month and saved snapshots',
    () async {
      final t = CalendarTransport();
      final a = calendarApi(t);
      addTearDown(a.close);
      final p = await a.savedPlanMonth(goalId: 1, month: '2026-11');
      expect(p.length, 1);
      expect(t.calls.single.$2.queryParameters, {
        'goalId': '1',
        'month': '2026-11',
      });
      expect(t.calls.single.$1, 'GET');
    },
  );
  test(
    'monthly API rejects wrong scope duplicate date and invalid bounds',
    () async {
      final t = CalendarTransport(), api = calendarApi(t);
      addTearDown(api.close);
      for (final m in ['2026-1', '2101-01', '2026-13']) {
        await expectLater(
          api.savedPlanMonth(goalId: 1, month: m),
          throwsA(isA<ApiFailure>()),
        );
      }
      expect(t.calls, isEmpty);
      for (final bad in [
        {'goalId': 3, 'month': '2026-11', 'plans': []},
        {'goalId': 1, 'month': '2026-10', 'plans': []},
        {
          'goalId': 1,
          'month': '2026-11',
          'plans': [calendarData('2026-11-01'), calendarData('2026-11-01')],
        },
        {
          'goalId': 1,
          'month': '2026-11',
          'plans': [calendarData('2026-12-01')],
        },
      ]) {
        t.responseOverride = bad;
        await expectLater(
          api.savedPlanMonth(goalId: 1, month: '2026-11'),
          throwsA(isA<ApiFailure>()),
        );
      }
    },
  );
  testWidgets(
    'monthly calendar renders Sunday-first today selection and daily details',
    (t) async {
      final semantics = t.ensureSemantics();

      await calendarHost(t);
      expect(find.text('2026년 11월'), findsOneWidget);
      expect(find.byKey(const Key('calendar-day-2026-11-01')), findsOneWidget);
      expect(
        find.bySemanticsLabel(RegExp('2026-11-01 오늘 선택됨.*FINAL')),
        findsOneWidget,
      );
      expect(find.text('취약 개념 보충'), findsOneWidget);
      expect(find.text('보충 학습'), findsOneWidget);
      expect(find.text('신규 학습'), findsWidgets);
      expect(find.text('09:50–10:00'), findsOneWidget);
      expect(find.text('휴식'), findsWidgets);
      semantics.dispose();
    },
  );
  testWidgets('month arrows cross year boundary and today restores month', (
    t,
  ) async {
    await calendarHost(t, initial: DateTime(2026, 12, 31));
    await t.tap(find.byKey(const Key('calendarNext')));
    await t.pumpAndSettle();
    expect(find.text('2027년 1월'), findsOneWidget);
    await t.tap(find.byKey(const Key('calendarPrevious')));
    await t.pumpAndSettle();
    expect(find.text('2026년 12월'), findsOneWidget);
    await t.tap(find.byKey(const Key('calendarToday')));
    await t.pumpAndSettle();
    expect(find.text('2026년 11월'), findsOneWidget);
  });
  testWidgets('selecting empty future day never invents an event', (t) async {
    await calendarHost(t);
    await t.tap(find.byKey(const Key('calendar-day-2026-11-15')));
    await t.pumpAndSettle();
    expect(find.text('일정 없음'), findsOneWidget);
    expect(find.text('취약 개념 보충'), findsNothing);
  });
  testWidgets(
    'calendar API year bounds also disable adjacent out-of-range dates',
    (t) async {
      await calendarHost(t, initial: DateTime(1970, 1, 1));
      expect(
        t
            .widget<IconButton>(find.byKey(const Key('calendarPrevious')))
            .onPressed,
        isNull,
      );
      expect(
        t
            .widget<InkWell>(find.byKey(const Key('calendar-day-1969-12-31')))
            .onTap,
        isNull,
      );
      await calendarHost(t, initial: DateTime(2100, 12, 1));
      expect(
        t.widget<IconButton>(find.byKey(const Key('calendarNext'))).onPressed,
        isNull,
      );
    },
  );
  testWidgets(
    'subject chip filters visible study segments while preserving breaks',
    (t) async {
      await calendarHost(t);
      final chip = find.byWidgetPredicate(
        (w) =>
            w is ChoiceChip &&
            w.label is Text &&
            (w.label as Text).data == '네트워크',
      );
      await t.ensureVisible(chip);
      await t.tap(chip);
      await t.pumpAndSettle();
      expect(find.text('문제풀이'), findsNothing);
      expect(find.text('개념 복습'), findsOneWidget);
      expect(find.text('09:50–10:00'), findsOneWidget);
      await t.tap(find.widgetWithText(ChoiceChip, '전체 구간'));
      await t.pumpAndSettle();
      expect(find.text('문제풀이'), findsOneWidget);
    },
  );
  testWidgets(
    'saved FINAL and unsaved preview on the same day have explicit marker',
    (t) async {
      final data = calendarData('2026-11-01');
      await calendarHost(
        t,
        plans: [
          CalendarPlan(data, goalId: 1, source: CalendarPlanSource.saved),
          CalendarPlan(data, goalId: 1, source: CalendarPlanSource.preview),
        ],
      );
      expect(find.text('확정'), findsOneWidget);
      expect(find.byTooltip('미저장 미리보기도 있음'), findsOneWidget);
    },
  );
  testWidgets(
    'many subjects abbreviate cells but details keep all activities',
    (t) async {
      final p = calendarData('2026-11-01');
      (p['timeSlots'] as List).add({
        'subjectId': 3,
        'subjectName': '자료구조',
        'type': '공부',
        'minutes': 5,
        'activity': '트리 복습',
      });
      await calendarHost(
        t,
        plans: [CalendarPlan(p, goalId: 1, source: CalendarPlanSource.saved)],
      );
      expect(find.text('+1개'), findsOneWidget);
      expect(find.text('트리 복습'), findsOneWidget);
    },
  );
  for (final scale in [1.0, 1.6, 2.0]) {
    testWidgets(
      '360dp dark calendar and timeline at text scale $scale have no overflow',
      (t) async {
        t.view.physicalSize = const Size(360, 800);
        t.view.devicePixelRatio = 1;
        addTearDown(t.view.resetPhysicalSize);
        addTearDown(t.view.resetDevicePixelRatio);
        await calendarHost(t, scale: scale);
        expect(t.takeException(), isNull);
        await t.drag(find.byType(SingleChildScrollView), const Offset(0, -600));
        await t.pumpAndSettle();
        expect(t.takeException(), isNull);
      },
    );
  }
  testWidgets('goal changes discard delayed prior month responses', (t) async {
    final transport = CalendarTransport(), api = calendarApi(transport);
    addTearDown(api.close);
    final month = planMonthKey(seoulToday());
    final hold = Completer<Reply>();
    transport.held['1/$month'] = hold;
    await t.pumpWidget(
      MaterialApp(
        home: ServerStudyPlan(api: api, goalId: 1, goalName: '목표 A'),
      ),
    );
    await t.pump();
    await t.pumpWidget(
      MaterialApp(
        home: ServerStudyPlan(api: api, goalId: 3, goalName: '목표 B'),
      ),
    );
    await t.pumpAndSettle();
    hold.complete(
      Reply(
        200,
        jsonEncode({
          'goalId': 1,
          'month': month,
          'plans': [calendarData('$month-01')],
        }),
      ),
    );
    await t.pumpAndSettle();
    await t.ensureVisible(find.byKey(Key('calendar-day-$month-01')));
    await t.tap(find.byKey(Key('calendar-day-$month-01')));
    await t.pumpAndSettle();
    expect(find.textContaining('목표 B'), findsWidgets);
    expect(find.textContaining('목표 A'), findsNothing);
    expect(transport.calls.every((c) => c.$1 == 'GET'), isTrue);
  });
  testWidgets(
    'late previous month cannot overwrite visible month and cached return avoids repeated GET',
    (t) async {
      final transport = CalendarTransport(), api = calendarApi(transport);
      addTearDown(api.close);
      final now = seoulToday(), m = planMonthKey(now);
      final hold = Completer<Reply>();
      transport.held['1/$m'] = hold;
      await t.pumpWidget(
        MaterialApp(
          home: ServerStudyPlan(api: api, goalId: 1, goalName: '목표 A'),
        ),
      );
      await t.pump();
      await t.ensureVisible(find.byKey(const Key('calendarNext')));
      await t.tap(find.byKey(const Key('calendarNext')));
      await t.pumpAndSettle();
      final next = DateTime(now.year, now.month + 1);
      hold.complete(
        Reply(
          200,
          jsonEncode({
            'goalId': 1,
            'month': m,
            'plans': [calendarData('$m-01')],
          }),
        ),
      );
      await t.pumpAndSettle();
      expect(find.text('${next.year}년 ${next.month}월'), findsOneWidget);
      final count = transport.calls
          .where((c) => c.$2.path == '/study-reschedule-calendar')
          .length;
      await t.tap(find.byKey(const Key('calendarNext')));
      await t.pumpAndSettle();
      await t.tap(find.byKey(const Key('calendarPrevious')));
      await t.pumpAndSettle();
      expect(
        transport.calls
            .where((c) => c.$2.path == '/study-reschedule-calendar')
            .length,
        count + 1,
      );
      expect(transport.calls.every((c) => c.$1 == 'GET'), isTrue);
    },
  );
  testWidgets('monthly error is not empty data and explicit retry works', (
    t,
  ) async {
    final transport = CalendarTransport()..fail = 500,
        api = calendarApi(transport);
    addTearDown(api.close);
    await t.pumpWidget(
      MaterialApp(
        home: ServerStudyPlan(api: api, goalId: 1, goalName: '목표 A'),
      ),
    );
    await t.pumpAndSettle();
    expect(find.textContaining('월간 조회 실패'), findsOneWidget);
    transport.fail = null;
    await t.ensureVisible(find.byKey(const Key('reloadPlanMonth')));
    await t.tap(find.byKey(const Key('reloadPlanMonth')));
    await t.pumpAndSettle();
    expect(find.textContaining('월간 조회 실패'), findsNothing);
  });
  testWidgets('offline dark calendar and timeline render captures', (t) async {
    t.view.physicalSize = const Size(360, 900);
    t.view.devicePixelRatio = 1;
    addTearDown(t.view.resetPhysicalSize);
    addTearDown(t.view.resetDevicePixelRatio);
    final boundary = GlobalKey();
    await calendarHost(t, boundary: boundary);
    Future<void> capture(String name) async {
      await t.runAsync(() async {
        final dir = Directory(
          '../build/reports/plan-calendar-review/screenshots',
        );
        await dir.create(recursive: true);
        final image =
            await (boundary.currentContext!.findRenderObject()
                    as RenderRepaintBoundary)
                .toImage(pixelRatio: 1);
        final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
        await File('${dir.path}/$name.png')
            .writeAsBytes(bytes!.buffer.asUint8List());
        image.dispose();
      });
    }

    await capture('calendar-dark-360');
    await t.ensureVisible(find.text('취약 개념 보충'));
    await t.pumpAndSettle();
    await capture('timeline-dark-360');
    expect(t.takeException(), isNull);
  });

  testWidgets('today ring remains distinct when another day is selected', (
    t,
  ) async {
    await calendarHost(t);
    await t.tap(find.byKey(const Key('calendar-day-2026-11-02')));
    await t.pumpAndSettle();
    expect(
      find.byKey(const Key('calendar-today-ring-2026-11-01')),
      findsOneWidget,
    );
    final selected = t.widget<AnimatedContainer>(
      find.descendant(
        of: find.byKey(const Key('calendar-day-2026-11-02')),
        matching: find.byType(AnimatedContainer),
      ),
    );
    final color = Theme.of(
      t.element(find.byKey(const Key('planMonthCalendar'))),
    ).colorScheme.primary;
    expect(
      ((selected.decoration as BoxDecoration).border as Border).top.color,
      color,
    );
    expect(find.text('일정 없음'), findsOneWidget);
  });

  testWidgets('calendar legend filter and timeline share subject ID color', (
    t,
  ) async {
    await calendarHost(t);
    final bar = t.widget<Container>(
      find.byKey(const Key('calendar-subject-2026-11-01-1')),
    );
    expect((bar.decoration as BoxDecoration).color, subjectPlanColor(1));
    final chip = t.widget<ChoiceChip>(
      find.byWidgetPredicate(
        (w) =>
            w is ChoiceChip &&
            w.label is Text &&
            (w.label as Text).data == '운영체제',
      ),
    );
    expect((chip.avatar as Icon).color, subjectPlanColor(1));
    final card = find.ancestor(
      of: find.text('취약 개념 보충'),
      matching: find.byType(AnimatedContainer),
    );
    await t.ensureVisible(find.text('취약 개념 보충'));
    await t.tap(find.text('취약 개념 보충'));
    await t.pumpAndSettle();
    expect(
      ((t.widget<AnimatedContainer>(card).decoration as BoxDecoration).border
              as Border)
          .top
          .color,
      subjectPlanColor(1),
    );
    expect(find.text('09:00–09:09'), findsOneWidget);
    expect(find.text('취약 개념 보충'), findsOneWidget);
  });

  testWidgets(
    'day summary displays exact supplied minutes without filling data',
    (t) async {
      final raw = calendarData('2026-11-01')
        ..['studyMinutes'] = 137
        ..['newStudyMinutes'] = 128;
      final before = jsonEncode(raw);
      await calendarHost(
        t,
        plans: [CalendarPlan(raw, goalId: 1, source: CalendarPlanSource.saved)],
      );
      expect(find.text('137분'), findsWidgets);
      expect(find.text('128'), findsOneWidget);
      expect(find.text('전체 공부'), findsOneWidget);
      expect(jsonEncode(raw), before);
    },
  );

  testWidgets(
    'FINAL DRAFT and unsaved preview use distinct labeled status cards',
    (t) async {
      final colors = <Color?>[];
      for (final entry in [
        ('FINAL', CalendarPlanSource.saved),
        ('DRAFT', CalendarPlanSource.saved),
        ('FINAL', CalendarPlanSource.preview),
      ]) {
        final p = CalendarPlan(
          calendarData('2026-11-01', status: entry.$1),
          goalId: 1,
          source: entry.$2,
        );
        await calendarHost(t, plans: [p]);
        final badge = t.widget<Container>(
          find.byKey(const Key('calendar-plan-status')),
        );
        colors.add((badge.decoration as BoxDecoration).color);
        expect(find.text(p.status), findsOneWidget);
        if (entry.$1 == 'DRAFT') expect(find.text('임시'), findsOneWidget);
      }
      expect(colors.toSet().length, 3);
    },
  );

  for (final dark in [true, false]) {
    testWidgets(
      '320dp long content at 2x text in ${dark ? 'dark' : 'light'} theme',
      (t) async {
        t.view.physicalSize = const Size(320, 740);
        t.view.devicePixelRatio = 1;
        addTearDown(t.view.resetPhysicalSize);
        addTearDown(t.view.resetDevicePixelRatio);
        final raw = calendarData('2026-11-01');
        final longName = '운영체제의 프로세스와 스레드 및 메모리 관리 심화 학습';
        final longActivity = List.filled(
          8,
          '서버에서 받은 긴 학습 활동과 복습 내용을 읽습니다.',
        ).join(' ');
        (raw['timeSlots'] as List).first['subjectName'] = longName;
        (raw['timeSlots'] as List).first['activity'] = longActivity;
        await calendarHost(
          t,
          scale: 2,
          dark: dark,
          plans: [
            CalendarPlan(raw, goalId: 1, source: CalendarPlanSource.saved),
          ],
        );
        expect(t.takeException(), isNull);
        await t.ensureVisible(find.text(longActivity));
        await t.pumpAndSettle();
        expect(t.takeException(), isNull);
        expect(t.widget<Text>(find.text(longActivity)).maxLines, isNull);
      },
    );
  }

  testWidgets('reduced motion disables calendar card and detail transitions', (
    t,
  ) async {
    await calendarHost(t, reduceMotion: true);
    expect(
      t
          .widgetList<AnimatedContainer>(find.byType(AnimatedContainer))
          .every((w) => w.duration == Duration.zero),
      isTrue,
    );
    expect(monthMotion(t).duration, Duration.zero);
    expect(
      t
          .widget<AnimatedSwitcher>(
            find.byKey(const Key('calendar-details-transition')),
          )
          .duration,
      Duration.zero,
    );
    await t.tap(find.byKey(const Key('calendarNext')));
    await t.pump();
    expect(find.text('2026년 12월'), findsOneWidget);
    expect(t.takeException(), isNull);
  });

  testWidgets(
    'calendar and detail transitions use short existing design motion',
    (t) async {
      await calendarHost(t);
      expect(monthMotion(t).duration, const Duration(milliseconds: 270));
      expect(
        t
            .widget<AnimatedSwitcher>(
              find.byKey(const Key('calendar-details-transition')),
            )
            .duration,
        const Duration(milliseconds: 240),
      );
      final card = find.ancestor(
        of: find.text('취약 개념 보충'),
        matching: find.byType(AnimatedContainer),
      );
      expect(
        t.widget<AnimatedContainer>(card).duration,
        const Duration(milliseconds: 240),
      );
    },
  );

  testWidgets(
    'detail transition never retains old goal content for an animation frame',
    (t) async {
      Widget host(int goal, String name) => MaterialApp(
        theme: AppDesign.theme(Brightness.dark),
        home: Scaffold(
          body: SingleChildScrollView(
            child: PlanScheduleDetails(
              plan: CalendarPlan(
                calendarData('2026-11-01', goal: goal),
                goalId: goal,
                source: CalendarPlanSource.saved,
              ),
              goalName: name,
            ),
          ),
        ),
      );
      await t.pumpWidget(host(1, '이전 목표 A'));
      await t.pumpAndSettle();
      await t.pumpWidget(host(3, '현재 목표 B'));
      expect(find.text('이전 목표 A'), findsNothing);
      expect(find.text('현재 목표 B'), findsOneWidget);
      await t.pumpAndSettle();
      expect(t.takeException(), isNull);
    },
  );

  testWidgets(
    'next and previous months translate both pages in opposite directions at mid-frame',
    (t) async {
      await calendarHost(t);
      final viewport = find.byKey(const Key('calendar-month-transition'));
      final left = t.getTopLeft(viewport).dx;
      final width = t.getSize(viewport).width;
      final beforeSize = t.getSize(find.byKey(const Key('planMonthCalendar')));
      final november = find.byKey(const Key('calendar-page-2026-11'));
      final december = find.byKey(const Key('calendar-page-2026-12'));
      await t.tap(find.byKey(const Key('calendarNext')));
      await t.pump();
      expect(t.getTopLeft(november).dx, closeTo(left, .1));
      expect(t.getTopLeft(december).dx, closeTo(left + width, .1));
      await t.pump(const Duration(milliseconds: 90));
      expect(t.getTopLeft(november).dx, lessThan(left - 2));
      expect(
        t.getTopLeft(december).dx,
        inExclusiveRange(left + 2, left + width - 2),
      );
      final fade = t.widget<Opacity>(
        find.ancestor(of: december, matching: find.byType(Opacity)).first,
      );
      expect(fade.opacity, inExclusiveRange(.85, 1));
      expect(t.getSize(find.byKey(const Key('planMonthCalendar'))), beforeSize);
      await t.pumpAndSettle();
      expect(november, findsNothing);
      expect(t.getTopLeft(december).dx, closeTo(left, .1));
      await t.tap(find.byKey(const Key('calendarPrevious')));
      await t.pump();
      expect(t.getTopLeft(november).dx, closeTo(left - width, .1));
      await t.pump(const Duration(milliseconds: 90));
      expect(t.getTopLeft(december).dx, greaterThan(left + 2));
      expect(
        t.getTopLeft(november).dx,
        inExclusiveRange(left - width + 2, left - 2),
      );
      expect(t.getSize(find.byKey(const Key('planMonthCalendar'))), beforeSize);
      await t.pumpAndSettle();
      expect(december, findsNothing);
      expect(t.getTopLeft(november).dx, closeTo(left, .1));
      expect(t.takeException(), isNull);
    },
  );

  testWidgets(
    'four and six week months share a fixed viewport without height jumps',
    (t) async {
      await calendarHost(t, initial: DateTime(2026, 2));
      final size = t.getSize(
        find.byKey(const Key('calendar-month-transition')),
      );
      expect(find.byKey(const Key('calendar-day-2026-03-14')), findsOneWidget);
      await t.tap(find.byKey(const Key('calendarNext')));
      await t.pump();
      await t.pump(const Duration(milliseconds: 135));
      expect(
        t.getSize(find.byKey(const Key('calendar-month-transition'))),
        size,
      );
      await t.pumpAndSettle();
      expect(
        t.getSize(find.byKey(const Key('calendar-month-transition'))),
        size,
      );
      expect(t.takeException(), isNull);
    },
  );

  for (final empty in [false, true]) {
    testWidgets(
      'goal change during slide removes outgoing data immediately (empty=$empty)',
      (t) async {
        var month = DateTime(2026, 11);
        Widget host(int goal) => MaterialApp(
          theme: AppDesign.theme(Brightness.dark),
          home: StatefulBuilder(
            builder: (context, set) {
              final raw = calendarData('${planMonthKey(month)}-01', goal: goal);
              for (final slot in raw['timeSlots'] as List) {
                if (slot['subjectId'] != null) {
                  slot['subjectName'] = goal == 1
                      ? '이전 목표 A 전용 과목'
                      : '새 목표 B 전용 과목';
                }
              }
              return Scaffold(
                body: SingleChildScrollView(
                  child: PlanMonthCalendar(
                    key: ValueKey(goal),
                    month: month,
                    selected: month,
                    today: DateTime(2026, 11, 1),
                    plans: empty && goal == 3
                        ? []
                        : [
                            CalendarPlan(
                              raw,
                              goalId: goal,
                              source: CalendarPlanSource.saved,
                            ),
                          ],
                    onDay: (_) {},
                    onMonth: (d) => set(() => month = d),
                  ),
                ),
              );
            },
          ),
        );
        await t.pumpWidget(host(1));
        await t.pumpAndSettle();
        await t.tap(find.byKey(const Key('calendarNext')));
        await t.pump();
        await t.pump(const Duration(milliseconds: 90));
        expect(find.byKey(const Key('calendar-page-2026-11')), findsOneWidget);
        await t.pumpWidget(host(3));
        expect(find.textContaining('이전 목표 A 전용 과목'), findsNothing);
        expect(find.byKey(const Key('calendar-page-2026-11')), findsNothing);
        expect(monthMotion(t).isAnimating, isFalse);
        expect(monthMotion(t).value, 1);
        await t.pumpAndSettle();
        expect(find.textContaining('이전 목표 A 전용 과목'), findsNothing);
        expect(t.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'outgoing month is non-interactive and excluded from accessibility',
    (t) async {
      final semantics = t.ensureSemantics();
      await calendarHost(t);
      await t.tap(find.byKey(const Key('calendarNext')));
      await t.pump();
      await t.pump(const Duration(milliseconds: 90));
      final old = find.byKey(const Key('calendar-page-2026-11'));
      expect(
        t
            .widget<IgnorePointer>(
              find
                  .ancestor(of: old, matching: find.byType(IgnorePointer))
                  .first,
            )
            .ignoring,
        isTrue,
      );
      expect(
        t
            .widget<ExcludeSemantics>(
              find
                  .ancestor(of: old, matching: find.byType(ExcludeSemantics))
                  .first,
            )
            .excluding,
        isTrue,
      );
      expect(find.bySemanticsLabel(RegExp('2026-11-01 오늘 선택됨')), findsNothing);
      await t.pumpAndSettle();
      semantics.dispose();
    },
  );

  for (final scale in [1.0, 1.6, 2.0]) {
    testWidgets(
      '320dp Korean status labels at text scale $scale remain visible',
      (t) async {
        t.view.physicalSize = const Size(320, 780);
        t.view.devicePixelRatio = 1;
        addTearDown(t.view.resetPhysicalSize);
        addTearDown(t.view.resetDevicePixelRatio);
        final data = [
          CalendarPlan(
            calendarData('2026-11-01'),
            goalId: 1,
            source: CalendarPlanSource.saved,
          ),
          CalendarPlan(
            calendarData('2026-11-02', status: 'DRAFT'),
            goalId: 1,
            source: CalendarPlanSource.saved,
          ),
          CalendarPlan(
            calendarData('2026-11-03'),
            goalId: 1,
            source: CalendarPlanSource.preview,
          ),
        ];
        await calendarHost(t, scale: scale, plans: data);
        for (final entry in [('01', '확정'), ('02', '임시'), ('03', '미저장')]) {
          final cell = find.byKey(Key('calendar-day-2026-11-${entry.$1}'));
          final label = find.descendant(
            of: cell,
            matching: find.text(entry.$2),
          );
          expect(label, findsOneWidget);
          expect(
            t.getBottomRight(label).dy,
            lessThanOrEqualTo(t.getBottomRight(cell).dy),
          );
          expect(
            t.getSize(label).width,
            lessThanOrEqualTo(t.getSize(cell).width),
          );
        }
        expect(find.text('확정 계획 (FINAL) · 서버 저장본'), findsOneWidget);
        await t.tap(find.byKey(const Key('calendar-day-2026-11-02')));
        await t.pumpAndSettle();
        expect(find.text('임시 계획 (DRAFT) · 서버 저장본'), findsOneWidget);
        await t.tap(find.byKey(const Key('calendar-day-2026-11-03')));
        await t.pumpAndSettle();
        expect(find.text('미리보기 · 미저장'), findsOneWidget);
        expect(t.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'rapid month changes settle on the latest month without stale pages',
    (t) async {
      await calendarHost(t);
      for (final key in ['calendarNext', 'calendarNext', 'calendarPrevious']) {
        await t.tap(find.byKey(Key(key)));
        await t.pump();
        await t.pump(const Duration(milliseconds: 50));
      }
      await t.pumpAndSettle();
      expect(find.text('2026년 12월'), findsOneWidget);
      expect(find.byKey(const Key('calendar-page-2026-12')), findsOneWidget);
      expect(find.byKey(const Key('calendar-page-2026-11')), findsNothing);
      expect(find.byKey(const Key('calendar-page-2027-01')), findsNothing);
      expect(t.takeException(), isNull);
    },
  );

  testWidgets(
    'enabling reduced motion mid-slide clears outgoing page immediately',
    (t) async {
      await calendarHost(t);
      await t.tap(find.byKey(const Key('calendarNext')));
      await t.pump();
      await t.pump(const Duration(milliseconds: 90));
      expect(monthMotion(t).isAnimating, isTrue);
      await calendarHost(t, initial: DateTime(2026, 12), reduceMotion: true);
      expect(monthMotion(t).duration, Duration.zero);
      expect(monthMotion(t).isAnimating, isFalse);
      expect(find.byKey(const Key('calendar-page-2026-11')), findsNothing);
      expect(find.byKey(const Key('calendar-page-2026-12')), findsOneWidget);
      expect(t.takeException(), isNull);
    },
  );

  testWidgets(
    'swipe follows finger then completes next and previous without a jump',
    (t) async {
      await calendarHost(t);
      final page = find.byKey(const Key('calendar-page-2026-11'));
      final left = t.getTopLeft(page).dx;
      final gesture = await calendarDrag(t, -140);
      final dragged = t.getTopLeft(page).dx;
      expect(dragged, closeTo(left - 140, 1));
      expect(find.text('2026년 11월'), findsOneWidget);
      expect(find.byKey(const Key('calendar-page-2026-12')), findsNothing);
      await gesture.up();
      await t.pump();
      expect(find.text('2026년 12월'), findsOneWidget);
      expect(t.getTopLeft(page).dx, closeTo(dragged, 1));
      expect(monthMotion(t).duration, const Duration(milliseconds: 270));
      await t.pump(const Duration(milliseconds: 90));
      expect(t.getTopLeft(page).dx, lessThan(dragged - 2));
      await t.pumpAndSettle();
      final december = find.byKey(const Key('calendar-page-2026-12'));
      final back = await calendarDrag(t, 140);
      expect(t.getTopLeft(december).dx, closeTo(left + 140, 1));
      await back.up();
      await t.pump();
      expect(find.text('2026년 11월'), findsOneWidget);
      await t.pumpAndSettle();
      expect(t.getTopLeft(page).dx, closeTo(left, 1));
      expect(t.takeException(), isNull);
    },
  );

  testWidgets(
    'short drag springs back at an intermediate frame without changing month',
    (t) async {
      await calendarHost(t);
      final page = find.byKey(const Key('calendar-page-2026-11'));
      final left = t.getTopLeft(page).dx;
      final gesture = await calendarDrag(t, -36);
      final dragged = t.getTopLeft(page).dx;
      expect(dragged, lessThan(left - 20));
      await gesture.up();
      await t.pump();
      expect(find.text('2026년 11월'), findsOneWidget);
      await t.pump(const Duration(milliseconds: 135));
      expect(t.getTopLeft(page).dx, inExclusiveRange(dragged, left));
      await t.pumpAndSettle();
      expect(t.getTopLeft(page).dx, closeTo(left, 1));
      expect(find.byKey(const Key('calendar-page-2026-12')), findsNothing);
    },
  );

  testWidgets('cancelled long drag never commits a month', (t) async {
    await calendarHost(t);
    final gesture = await calendarDrag(t, -160);
    await gesture.cancel();
    await t.pumpAndSettle();
    expect(find.text('2026년 11월'), findsOneWidget);
    expect(monthMotion(t).isAnimating, isFalse);
  });

  testWidgets(
    'vertical gesture scrolls page and tiny movement still selects date',
    (t) async {
      final semantics = t.ensureSemantics();
      await calendarHost(t);
      final surface = find.byKey(const Key('calendar-swipe-surface'));
      final before = t.getTopLeft(surface), rect = t.getRect(surface);
      final vertical = await t.startGesture(
        Offset(rect.center.dx, rect.top + 90),
      );
      await vertical.moveBy(const Offset(0, -120));
      await vertical.moveBy(const Offset(0, -80));
      await vertical.up();
      await t.pumpAndSettle();
      expect(t.getTopLeft(surface).dy, lessThan(before.dy));
      expect(find.text('2026년 11월'), findsOneWidget);
      await t.ensureVisible(find.byKey(const Key('calendar-day-2026-11-08')));
      final tap = await t.startGesture(
        t.getCenter(find.byKey(const Key('calendar-day-2026-11-08'))),
      );
      await tap.moveBy(const Offset(5, 1));
      await tap.up();
      await t.pumpAndSettle();
      expect(find.bySemanticsLabel(RegExp('2026-11-08 선택됨')), findsOneWidget);
      expect(find.text('2026년 11월'), findsOneWidget);
      semantics.dispose();
    },
  );

  testWidgets('swipes respect both calendar year bounds', (t) async {
    await calendarHost(t, initial: DateTime(1970, 1));
    final first = await calendarDrag(t, 190);
    await first.up();
    await t.pumpAndSettle();
    expect(find.text('1970년 1월'), findsOneWidget);
    await calendarHost(t, initial: DateTime(2100, 12));
    final last = await calendarDrag(t, -190);
    await last.up();
    await t.pumpAndSettle();
    expect(find.text('2100년 12월'), findsOneWidget);
    expect(t.takeException(), isNull);
  });

  testWidgets(
    'repeated swipe while settling is ignored then a fresh swipe works',
    (t) async {
      await calendarHost(t);
      final first = await calendarDrag(t, -170);
      await first.up();
      await t.pump();
      await t.pump(const Duration(milliseconds: 30));
      final repeated = await calendarDrag(t, -170);
      await repeated.up();
      await t.pumpAndSettle();
      expect(find.text('2026년 12월'), findsOneWidget);
      final next = await calendarDrag(t, -170);
      await next.up();
      await t.pumpAndSettle();
      expect(find.text('2027년 1월'), findsOneWidget);
      expect(t.takeException(), isNull);
    },
  );

  testWidgets('goal switch during live drag discards old data and gesture', (
    t,
  ) async {
    final a = calendarData('2026-11-01');
    (a['timeSlots'] as List).first['subjectName'] = '이전 목표 A 전용 과목';
    await calendarHost(
      t,
      plans: [CalendarPlan(a, goalId: 1, source: CalendarPlanSource.saved)],
    );
    final gesture = await calendarDrag(t, -160);
    final b = calendarData('2026-11-01', goal: 3);
    (b['timeSlots'] as List).first['subjectName'] = '새 목표 B 전용 과목';
    await calendarHost(
      t,
      plans: [CalendarPlan(b, goalId: 3, source: CalendarPlanSource.saved)],
    );
    expect(find.textContaining('이전 목표 A 전용 과목'), findsNothing);
    await gesture.up();
    await t.pumpAndSettle();
    expect(find.text('2026년 11월'), findsOneWidget);
    expect(find.textContaining('이전 목표 A 전용 과목'), findsNothing);
    expect(t.takeException(), isNull);
  });

  testWidgets('reduced motion accepts swipe without automatic slide', (
    t,
  ) async {
    await calendarHost(t, reduceMotion: true);
    final gesture = await calendarDrag(t, -160);
    await gesture.up();
    await t.pump();
    expect(find.text('2026년 12월'), findsOneWidget);
    expect(monthMotion(t).duration, Duration.zero);
    expect(monthMotion(t).isAnimating, isFalse);
    expect(find.byKey(const Key('calendar-page-2026-11')), findsNothing);
  });

  testWidgets('swipe reuses monthly GET and cached return with zero POST', (
    t,
  ) async {
    final transport = CalendarTransport(), api = calendarApi(transport);
    addTearDown(api.close);
    await t.pumpWidget(
      MaterialApp(
        home: ServerStudyPlan(api: api, goalId: 1, goalName: '목표 A'),
      ),
    );
    await t.pumpAndSettle();
    int reads() => transport.calls
        .where((c) => c.$2.path == '/study-reschedule-calendar')
        .length;
    final before = reads();
    final gesture = await calendarDrag(t, -160);
    expect(reads(), before);
    await gesture.up();
    await t.pumpAndSettle();
    expect(reads(), before + 1);
    final back = await calendarDrag(t, 160);
    await back.up();
    await t.pumpAndSettle();
    expect(reads(), before + 1);
    expect(transport.calls.every((c) => c.$1 == 'GET'), isTrue);
    expect(
      transport.calls
          .where((c) => c.$2.path == '/study-reschedule-calendar')
          .every((c) => c.$2.queryParameters['goalId'] == '1'),
      isTrue,
    );
  });

  testWidgets(
    'a declined parent month change snaps back without automatic callback retry',
    (t) async {
      var requests = 0;
      await t.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: PlanMonthCalendar(
                key: const ValueKey(1),
                month: DateTime(2026, 11),
                selected: DateTime(2026, 11, 1),
                plans: [
                  CalendarPlan(
                    calendarData('2026-11-01'),
                    goalId: 1,
                    source: CalendarPlanSource.saved,
                  ),
                ],
                onDay: (_) {},
                onMonth: (_) => requests++,
              ),
            ),
          ),
        ),
      );
      await t.pumpAndSettle();
      final gesture = await calendarDrag(t, -170);
      await gesture.up();
      await t.pumpAndSettle();
      expect(requests, 1);
      expect(find.text('2026년 11월'), findsOneWidget);
      final next = await calendarDrag(t, -170);
      await next.up();
      await t.pumpAndSettle();
      expect(requests, 2);
      expect(find.text('2026년 11월'), findsOneWidget);
      expect(t.takeException(), isNull);
    },
  );
}
