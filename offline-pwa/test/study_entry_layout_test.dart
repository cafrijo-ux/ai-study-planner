import 'dart:async';
import 'dart:convert';

import 'package:ai_study_planner_mobile/app_design.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/study_record_entry.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'study_record_entry_test.dart' show SaveTransport, makeApi, fill;

class EntryGoalsMock extends SaveTransport {
  final holds = <int, Completer<void>>{};
  final subjectGoals = <int>[];
  String? subjectName;
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    if (uri.path == '/subjects') {
      final goal = int.parse(uri.queryParameters['goalId']!);
      subjectGoals.add(goal);
      await holds[goal]?.future;
      return Reply(
        200,
        jsonEncode([
          {
            'goalId': goal,
            'subjectId': goal,
            'userId': 1,
            'subjectName': subjectName ?? '목표 $goal 과목',
          },
        ]),
      );
    }
    return super.send(method, uri, headers, body);
  }
}

Finder entryScroll() => find
    .descendant(
      of: find.byKey(const Key('entryScroll')),
      matching: find.byType(Scrollable),
    )
    .first;
Future<void> showEntry(WidgetTester t, Finder finder) async {
  await t.scrollUntilVisible(
    finder,
    180,
    scrollable: entryScroll(),
    maxScrolls: 90,
  );
  await t.pumpAndSettle();
}

Future<void> entryHost(
  WidgetTester t,
  SaveTransport m, {
  bool dark = false,
  double scale = 1,
  bool reduced = false,
}) async {
  final api = makeApi(m);
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
      home: StudyRecordEntry(api: api, goalId: 1, goalName: '가상 학습 목표'),
    ),
  );
  await t.pumpAndSettle();
}

void main() {
  for (final scenario in [(390.0, 1.0, 5), (320.0, 1.0, 3), (320.0, 2.0, 2)]) {
    testWidgets(
      'equal rating targets width ${scenario.$1} text ${scenario.$2}',
      (t) async {
        t.view.physicalSize = Size(scenario.$1, 800);
        t.view.devicePixelRatio = 1;
        addTearDown(t.view.resetPhysicalSize);
        addTearDown(t.view.resetDevicePixelRatio);
        await entryHost(t, SaveTransport(), scale: scenario.$2);
        await showEntry(t, find.byKey(const Key('entryFocus')));
        final chips = [
          for (var i = 1; i <= 5; i++) find.byKey(Key('entryFocus_$i')),
        ];
        final widths = chips.map((f) => t.getSize(f).width).toList();
        expect(widths.every((w) => (w - widths.first).abs() < .01), true);
        expect(widths.first, greaterThanOrEqualTo(48));
        expect(
          t.getTopLeft(chips[scenario.$3 - 1]).dy,
          t.getTopLeft(chips.first).dy,
        );
        if (scenario.$3 < 5) {
          expect(
            t.getTopLeft(chips[scenario.$3]).dy,
            greaterThan(t.getTopLeft(chips.first).dy),
          );
        }
        expect(t.takeException(), isNull);
      },
    );
  }
  testWidgets('off-screen required fields are validated and no POST occurs', (
    t,
  ) async {
    final m = SaveTransport();
    await entryHost(t, m);
    await showEntry(t, find.byKey(const Key('entrySave')));
    await t.tap(find.byKey(const Key('entrySave')));
    await t.pumpAndSettle();
    for (final text in [
      '과목을 선택하세요.',
      '유효한 양의 정수(분)를 입력하세요.',
      '공부 내용을 입력하세요.',
      '집중도 선택이 필요합니다.',
      '이해도 선택이 필요합니다.',
      '피로도 선택이 필요합니다.',
    ]) {
      expect(find.text(text), findsOneWidget);
    }
    expect(find.text('과목을 선택하세요.').hitTestable(), findsOneWidget);
    expect(m.posts, 0);
    expect(t.takeException(), isNull);
  });

  for (final invalid in ['', '0', '-1', '1.5', '2147483648']) {
    testWidgets(
      'minutes UI rejects "$invalid" without altering server limits',
      (t) async {
        final m = SaveTransport();
        await entryHost(t, m);
        await t.enterText(find.byKey(const Key('entryMinutes')), invalid);
        await t.tap(find.byKey(const Key('entrySaveShortcut')));
        await t.pumpAndSettle();
        await showEntry(t, find.text('유효한 양의 정수(분)를 입력하세요.'));
        expect(find.text('유효한 양의 정수(분)를 입력하세요.'), findsOneWidget);
        expect(m.posts, 0);
      },
    );
  }

  testWidgets('duration shortcuts and keyboard next focus never submit', (
    t,
  ) async {
    final m = SaveTransport();
    await entryHost(t, m);
    await showEntry(t, find.byKey(const Key('entryPreset30')));
    await t.tap(find.byKey(const Key('entryPreset30')));
    await t.pumpAndSettle();
    final input = t.widget<TextFormField>(
      find.byKey(const Key('entryMinutes')),
    );
    expect(input.controller!.text, '30');
    final editable = t.widget<EditableText>(
      find.descendant(
        of: find.byKey(const Key('entryMinutes')),
        matching: find.byType(EditableText),
      ),
    );
    expect(editable.keyboardType, TextInputType.number);
    await t.showKeyboard(find.byKey(const Key('entryMinutes')));
    await t.testTextInput.receiveAction(TextInputAction.next);
    await t.pumpAndSettle();
    final content = t.widget<EditableText>(
      find.descendant(
        of: find.byKey(const Key('entryContent')),
        matching: find.byType(EditableText),
      ),
    );
    expect(content.focusNode.hasFocus, true);
    expect(m.posts, 0);
  });

  for (final reduced in [false, true]) {
    testWidgets(
      'direct rating selection respects 240ms animation and reduced motion $reduced',
      (t) async {
        final m = SaveTransport();
        await entryHost(t, m, reduced: reduced);
        final chip = find.byKey(const Key('entryFocus_4'));
        await showEntry(t, chip);
        final style = t.widget<ChoiceChip>(chip).chipAnimationStyle!;
        Color chipColor() =>
            (t
                        .widget<Ink>(
                          find
                              .descendant(of: chip, matching: find.byType(Ink))
                              .first,
                        )
                        .decoration
                    as ShapeDecoration)
                .color ??
            Colors.transparent;
        final before = chipColor();
        expect(
          style.selectAnimation!.duration,
          reduced ? Duration.zero : const Duration(milliseconds: 240),
        );
        await t.tap(chip);
        await t.pump();
        await t.pump(const Duration(milliseconds: 100));
        final middle = chipColor();
        expect(t.widget<ChoiceChip>(chip).selected, true);
        expect(
          t.widget<ChoiceChip>(find.byKey(const Key('entryFocus_3'))).selected,
          false,
        );
        await t.pumpAndSettle();
        if (reduced) {
          expect(middle, chipColor());
        } else {
          expect(middle, isNot(before));
          expect(middle, isNot(chipColor()));
        }
        expect(m.posts, 0);
        expect(t.takeException(), isNull);
      },
    );
  }

  for (final dark in [false, true]) {
    for (final scale in [1.0, 1.6, 2.0]) {
      testWidgets('320dp entry keyboard long input scale $scale dark $dark', (
        t,
      ) async {
        t.view.physicalSize = const Size(320, 800);
        t.view.devicePixelRatio = 1;
        addTearDown(t.view.resetPhysicalSize);
        addTearDown(t.view.resetDevicePixelRatio);
        final m = EntryGoalsMock()
          ..subjectName = '아주 긴 운영체제 과목 이름과 네트워크의 실제 학습 단원';
        await entryHost(t, m, dark: dark, scale: scale);
        await showEntry(t, find.byKey(const Key('entrySubject')));
        await t.tap(find.byKey(const Key('entrySubject')));
        await t.pumpAndSettle();
        await t.tap(find.text(m.subjectName!).last);
        await t.pumpAndSettle();
        expect(find.text('선택 과목: ${m.subjectName}'), findsOneWidget);
        t.view.viewInsets = const FakeViewPadding(bottom: 280);
        addTearDown(t.view.resetViewInsets);
        await t.pumpAndSettle();
        await showEntry(t, find.byKey(const Key('entryContent')));
        final long = List.filled(12, '가상 학습 내용과 긴 메모를 입력합니다.').join('\n');
        await t.enterText(find.byKey(const Key('entryContent')), long);
        await showEntry(t, find.byKey(const Key('entryMemo')));
        await t.enterText(find.byKey(const Key('entryMemo')), long);
        // Simulate the user's outer drag: dismiss text focus before moving to
        // Save, rather than fighting EditableText's focused-caret auto-scroll.
        await t.drag(entryScroll(), const Offset(0, -80));
        await t.pumpAndSettle();
        await showEntry(t, find.byKey(const Key('entrySave')));
        expect(
          find.byKey(const Key('entrySave')).hitTestable(),
          findsOneWidget,
        );
        expect(
          t
              .widget<TextFormField>(find.byKey(const Key('entryContent')))
              .controller!
              .text,
          long,
        );
        expect(
          t
              .widget<TextFormField>(find.byKey(const Key('entryMemo')))
              .controller!
              .text,
          long,
        );
        expect(m.posts, 0);
        expect(t.takeException(), isNull);
      });
    }
  }

  testWidgets(
    'save in flight blocks back and all duplicate actions; draft survives server failure',
    (t) async {
      final m = SaveTransport()
        ..hold = Completer<void>()
        ..status = 400;
      await entryHost(t, m);
      await fill(t);
      await t.pumpAndSettle();
      await t.tap(find.byKey(const Key('entrySave')));
      await t.pump();
      await t.pump(const Duration(milliseconds: 300));
      await t.binding.handlePopRoute();
      await t.pump();
      expect(find.byType(StudyRecordEntry), findsOneWidget);
      expect(
        t.widget<FilledButton>(find.byKey(const Key('entrySave'))).onPressed,
        isNull,
      );
      expect(
        t
            .widget<TextButton>(find.byKey(const Key('entrySaveShortcut')))
            .onPressed,
        isNull,
      );
      expect(m.posts, 1);
      m.hold!.complete();
      await t.pumpAndSettle();
      expect(
        t
            .widget<TextFormField>(find.byKey(const Key('entryContent')))
            .controller!
            .text,
        '개념 복습',
      );
      expect(
        t.widget<ChoiceChip>(find.byKey(const Key('entryFatigue_3'))).selected,
        true,
      );
      expect(find.textContaining('요청 실패 (400)'), findsOneWidget);
      expect(m.posts, 1);
    },
  );

  testWidgets('date picker cancel keeps date and draft without submitting', (
    t,
  ) async {
    final m = SaveTransport();
    await entryHost(t, m);
    await t.enterText(find.byKey(const Key('entryContent')), '가상 입력 내용');
    final date = t
        .widget<OutlinedButton>(find.byKey(const Key('entryDate')))
        .child;
    await showEntry(t, find.byKey(const Key('entryDate')));
    await t.tap(find.byKey(const Key('entryDate')));
    await t.pumpAndSettle();
    await t.tap(find.text('Cancel'));
    await t.pumpAndSettle();
    expect(
      (t.widget<OutlinedButton>(find.byKey(const Key('entryDate'))).child
              as Text)
          .data,
      (date as Text).data,
    );
    expect(
      t
          .widget<TextFormField>(find.byKey(const Key('entryContent')))
          .controller!
          .text,
      '가상 입력 내용',
    );
    expect(m.posts, 0);
  });

  testWidgets(
    'late save result cannot pop or overwrite a newly selected goal',
    (t) async {
      final m = EntryGoalsMock()
        ..subjectName = '운영체제'
        ..hold = Completer<void>();
      final api = makeApi(m), goal = ValueNotifier<int>(1);
      addTearDown(api.close);
      addTearDown(goal.dispose);
      await t.pumpWidget(
        MaterialApp(
          home: ValueListenableBuilder<int>(
            valueListenable: goal,
            builder: (_, id, _) =>
                StudyRecordEntry(api: api, goalId: id, goalName: '목표 $id'),
          ),
        ),
      );
      await t.pumpAndSettle();
      await fill(t);
      await t.pumpAndSettle();
      await t.tap(find.byKey(const Key('entrySave')));
      await t.pump();
      await t.pump(const Duration(milliseconds: 300));
      expect(m.posts, 1);
      goal.value = 2;
      await t.pump();
      expect(find.text('선택 과목: 운영체제'), findsNothing);
      m.hold!.complete();
      await t.pumpAndSettle();
      expect(find.byType(StudyRecordEntry), findsOneWidget);
      expect(
        t.widget<StudyRecordEntry>(find.byType(StudyRecordEntry)).goalId,
        2,
      );
      expect(
        t
            .widget<TextFormField>(find.byKey(const Key('entryContent')))
            .controller!
            .text,
        isEmpty,
      );
      expect(
        t
            .state<FormFieldState<int>>(find.byKey(const Key('entrySubject')))
            .value,
        isNull,
      );
      expect(m.posts, 1);
      expect(t.takeException(), isNull);
    },
  );

  testWidgets(
    'changing goal clears selected subject, ratings and previous draft',
    (t) async {
      final m = EntryGoalsMock();
      final api = makeApi(m), goal = ValueNotifier<int>(1);
      addTearDown(api.close);
      addTearDown(goal.dispose);
      await t.pumpWidget(
        MaterialApp(
          home: ValueListenableBuilder<int>(
            valueListenable: goal,
            builder: (_, id, _) =>
                StudyRecordEntry(api: api, goalId: id, goalName: '목표 $id'),
          ),
        ),
      );
      await t.pumpAndSettle();
      await t.tap(find.byKey(const Key('entrySubject')));
      await t.pumpAndSettle();
      await t.tap(find.text('목표 1 과목').last);
      await t.pumpAndSettle();
      await t.enterText(find.byKey(const Key('entryContent')), '이전 목표 입력');
      await showEntry(t, find.byKey(const Key('entryFocus_3')));
      await t.tap(find.byKey(const Key('entryFocus_3')));
      await t.pumpAndSettle();
      goal.value = 2;
      await t.pump();
      expect(find.text('선택 과목: 목표 1 과목'), findsNothing);
      await t.pumpAndSettle();
      expect(
        t
            .widget<TextFormField>(find.byKey(const Key('entryContent')))
            .controller!
            .text,
        isEmpty,
      );
      expect(
        t.widget<ChoiceChip>(find.byKey(const Key('entryFocus_3'))).selected,
        false,
      );
      expect(
        t
            .state<FormFieldState<int>>(find.byKey(const Key('entrySubject')))
            .value,
        isNull,
      );
      expect(m.posts, 0);
    },
  );
  testWidgets('goal change reloads subjects and ignores late previous goal', (
    t,
  ) async {
    final m = EntryGoalsMock()..holds[1] = Completer<void>();
    final api = makeApi(m), goal = ValueNotifier<int>(1);
    addTearDown(api.close);
    addTearDown(goal.dispose);
    await t.pumpWidget(
      MaterialApp(
        home: ValueListenableBuilder<int>(
          valueListenable: goal,
          builder: (_, id, _) =>
              StudyRecordEntry(api: api, goalId: id, goalName: '목표 $id'),
        ),
      ),
    );
    await t.pump();
    goal.value = 2;
    await t.pump();
    expect(m.subjectGoals, contains(2));
    m.holds[1]!.complete();
    await t.pumpAndSettle();
    final field = t.widget<DropdownButton<int>>(
      find.descendant(
        of: find.byKey(const Key('entrySubject')),
        matching: find.byType(DropdownButton<int>),
      ),
    );
    expect(field.items!.map((i) => i.value), [2]);
    expect(find.text('목표 1 과목'), findsNothing);
    expect(m.posts, 0);
  });
}
