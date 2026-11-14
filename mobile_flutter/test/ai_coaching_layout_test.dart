import 'dart:async';
import 'dart:convert';
import 'dart:ui' show SemanticsAction;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/app_design.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/server_ai_history.dart';
import 'package:ai_study_planner_mobile/server_ai_request.dart';

import 'ai_generation_test.dart' show AiMock, apiFor, open, press;

class BrokenSetup extends AiMock {
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) {
    if (uri.path == '/study-question/capabilities') {
      return Future.value(Reply(503, '{}'));
    }
    return super.send(method, uri, headers, body);
  }
}

class DelayedHistory extends AiMock {
  final pending = <int, Completer<Reply>>{};
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) {
    if (uri.path == '/ai-responses') {
      final goal = int.parse(uri.queryParameters['goalId']!);
      return (pending[goal] = Completer<Reply>()).future;
    }
    return super.send(method, uri, headers, body);
  }

  void complete(int goal) => pending[goal]!.complete(
    Reply(
      200,
      jsonEncode([
        {
          'responseId': goal,
          'goalId': goal,
          'feature': 'DAILY_COACHING',
          'actualProvider': 'gemini',
          'model': 'gemini-test',
          'responseSource': 'PROVIDER',
          'responseBody': '목표 $goal 전용 가상 답변',
          'createdAt': '2026-11-02T09:00:00',
        },
      ]),
    ),
  );
}

void main() {
  for (final reduced in [false, true]) {
    testWidgets(
      'model card color really interpolates; reduced motion $reduced',
      (t) async {
        var provider = 'gemini';
        final selected = <String>[];
        await t.pumpWidget(
          MaterialApp(
            theme: AppDesign.theme(Brightness.dark),
            home: MediaQuery(
              data: MediaQueryData(disableAnimations: reduced),
              child: Scaffold(
                body: StatefulBuilder(
                  builder: (context, update) => Padding(
                    padding: const EdgeInsets.all(20),
                    child: AiModelSelector(
                      provider: provider,
                      onChanged: (p) => update(() {
                        provider = p;
                        selected.add(p);
                      }),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        await t.pumpAndSettle();
        final card = find.byKey(const Key('aiModelCard_gpt'));
        Color color() =>
            (t
                        .widget<DecoratedBox>(
                          find
                              .descendant(
                                of: card,
                                matching: find.byType(DecoratedBox),
                              )
                              .first,
                        )
                        .decoration
                    as BoxDecoration)
                .color!;
        final before = color();
        final target = Theme.of(t.element(card)).colorScheme.primaryContainer;
        expect(
          t
              .widget<ChoiceChip>(find.byKey(const Key('aiModel_gemini')))
              .selected,
          true,
        );
        await t.tap(find.byKey(const Key('aiModel_gpt')));
        await t.pump();
        await t.pump(const Duration(milliseconds: 100));
        final middle = color();
        if (reduced) {
          expect(middle, target);
        } else {
          expect(middle, isNot(before));
          expect(middle, isNot(target));
        }
        await t.pumpAndSettle();
        expect(color(), target);
        expect(selected, ['gpt']);
        expect(
          t.widget<AnimatedContainer>(card).duration,
          reduced ? Duration.zero : const Duration(milliseconds: 240),
        );
        expect(
          t.widget<ChoiceChip>(find.byKey(const Key('aiModel_gpt'))).selected,
          true,
        );
        expect(t.takeException(), isNull);
      },
    );
  }

  for (final dark in [false, true]) {
    for (final scale in [1.0, 2.0]) {
      testWidgets(
        '320dp model cards and long saved answers scale $scale dark $dark',
        (t) async {
          t.view.physicalSize = const Size(320, 800);
          t.view.devicePixelRatio = 1;
          addTearDown(t.view.resetPhysicalSize);
          addTearDown(t.view.resetDevicePixelRatio);
          final text = List.filled(
            30,
            '가상 답변 문단입니다. **중요한 개념**과 학습 내용을 차근차근 살펴봅니다.',
          ).join('\n\n');
          await t.pumpWidget(
            MaterialApp(
              theme: AppDesign.theme(dark ? Brightness.dark : Brightness.light),
              builder: (context, child) => MediaQuery(
                data: MediaQuery.of(context)
                    .copyWith(textScaler: TextScaler.linear(scale)),
                child: child!,
              ),
              home: Scaffold(
                body: SingleChildScrollView(
                  key: const Key('aiLayoutScroll'),
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    children: [
                      const AiModelSelector(provider: 'gemini'),
                      AiSavedAnswerCard(
                        title: '응답 #1',
                        row: {
                          'actualProvider': 'openai',
                          'model': 'gpt-4o-mini',
                          'responseSource': 'PROVIDER',
                          'responseBody': text,
                          'createdAt': '2026-11-02T09:00:00',
                          'questionText': '가상 질문입니다.',
                        },
                      ),
                      const Text('답변 마지막 이후'),
                    ],
                  ),
                ),
              ),
            ),
          );
          await t.pumpAndSettle();
          final g = t.getTopLeft(find.byKey(const Key('aiModel_gemini')));
          final p = t.getTopLeft(find.byKey(const Key('aiModel_gpt')));
          if (scale == 1) {
            expect(g.dy, p.dy);
          } else {
            expect(p.dy, greaterThan(g.dy));
          }
          final scroll = find
              .descendant(
                of: find.byKey(const Key('aiLayoutScroll')),
                matching: find.byType(Scrollable),
              )
              .first;
          await t.scrollUntilVisible(
            find.text('완결 정보 없음'),
            180,
            scrollable: scroll,
          );
          expect(find.text('openai · gpt-4o-mini'), findsOneWidget);
          expect(find.byType(SelectableText), findsOneWidget);
          expect(find.text('저장 질문: 가상 질문입니다.'), findsOneWidget);
          await t.scrollUntilVisible(
            find.text('답변 마지막 이후'),
            500,
            maxScrolls: 80,
            scrollable: scroll,
          );
          await t.pumpAndSettle();
          expect(find.text('답변 마지막 이후').hitTestable(), findsOneWidget);
          expect(t.takeException(), isNull);
        },
      );
    }
  }

  testWidgets(
    'model selection leaves stored answer unchanged and sends only GET',
    (t) async {
      final m = AiMock()
        ..rows.add({
          'responseId': 9,
          'goalId': 1,
          'feature': 'QUESTION',
          'actualProvider': 'gemini',
          'model': 'gemini-history',
          'responseSource': 'PROVIDER',
          'responseBody': '기존 Gemini 저장 답변',
        });
      await open(t, m);
      await press(t, const Key('reloadAiRequestHistory'));
      await t.scrollUntilVisible(
        find.text('기존 Gemini 저장 답변'),
        150,
        scrollable: find.byType(Scrollable).first,
      );
      await t.scrollUntilVisible(
        find.byKey(const Key('aiModel_gpt')),
        -180,
        scrollable: find.byType(Scrollable).first,
      );
      await t.ensureVisible(find.byKey(const Key('aiModel_gpt')));
      await t.pumpAndSettle();
      expect(
        find.byKey(const Key('aiModel_gpt')).hitTestable(),
        findsOneWidget,
      );
      await t.tap(find.byKey(const Key('aiModel_gpt')));
      await t.pumpAndSettle();
      expect(
        t.widget<ChoiceChip>(find.byKey(const Key('aiModel_gpt'))).selected,
        true,
      );
      await t.scrollUntilVisible(
        find.text('기존 Gemini 저장 답변'),
        180,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('gemini · gemini-history'), findsOneWidget);
      expect(m.generated, 0);
      expect(m.calls.every((c) => c.$1 == 'GET'), true);
      expect(t.takeException(), isNull);
    },
  );

  testWidgets('320dp large text question form stays usable above keyboard', (
    t,
  ) async {
    final m = AiMock();
    await open(t, m, scale: 2);
    t.view.physicalSize = const Size(320, 800);
    t.view.viewInsets = const FakeViewPadding(bottom: 280);
    addTearDown(t.view.resetViewInsets);
    await t.pumpAndSettle();
    await t.scrollUntilVisible(
      find.byKey(const Key('aiQuestion')),
      150,
      scrollable: find.byType(Scrollable).first,
    );
    await t.enterText(find.byKey(const Key('aiQuestion')), '가상 질문');
    await t.scrollUntilVisible(
      find.byKey(const Key('sendAiRequest')),
      150,
      scrollable: find.byType(Scrollable).first,
    );
    await t.pumpAndSettle();
    expect(
      find.byKey(const Key('sendAiRequest')).hitTestable(),
      findsOneWidget,
    );
    expect(m.generated, 0);
    expect(t.takeException(), isNull);
  });

  testWidgets('history clears on goal update and ignores late previous goal', (
    t,
  ) async {
    final m = DelayedHistory(), goal = ValueNotifier<int>(1);
    final api = apiFor(m);
    addTearDown(api.close);
    addTearDown(goal.dispose);
    await t.pumpWidget(
      MaterialApp(
        home: ValueListenableBuilder<int>(
          valueListenable: goal,
          builder: (_, id, _) =>
              ServerAiHistory(api: api, goalId: id, goalName: '목표 $id'),
        ),
      ),
    );
    await t.pump();
    goal.value = 2;
    await t.pump();
    expect(m.pending.containsKey(2), true);
    m.complete(2);
    await t.pumpAndSettle();
    m.complete(1);
    await t.pumpAndSettle();
    await t.scrollUntilVisible(find.text('목표 2 전용 가상 답변'), 150);
    expect(find.text('목표 1 전용 가상 답변'), findsNothing);
    goal.value = 3;
    await t.pump();
    expect(find.text('목표 2 전용 가상 답변'), findsNothing);
    m.complete(3);
    await t.pumpAndSettle();
    expect(m.generated, 0);
    expect(t.takeException(), isNull);
  });
  for (final dark in [false, true]) {
    for (final scenario in [
      (360.0, 1.0, 3),
      (360.0, 1.6, 2),
      (360.0, 2.0, 1),
      (240.0, 1.0, 2),
    ]) {
      testWidgets(
        'equal AI cards width ${scenario.$1} scale ${scenario.$2} dark $dark',
        (t) async {
          t.view.physicalSize = Size(scenario.$1, 800);
          t.view.devicePixelRatio = 1;
          addTearDown(t.view.resetPhysicalSize);
          addTearDown(t.view.resetDevicePixelRatio);
          final semantics = t.ensureSemantics();
          try {
            final selected = <AiFeature>[];
            await t.pumpWidget(
              MaterialApp(
                theme: AppDesign.theme(
                  dark ? Brightness.dark : Brightness.light,
                ),
                builder: (context, child) => MediaQuery(
                  data: MediaQuery.of(context)
                      .copyWith(textScaler: TextScaler.linear(scenario.$2)),
                  child: child!,
                ),
                home: Scaffold(
                  body: SingleChildScrollView(
                    padding: const EdgeInsets.all(20),
                    child: AiGenerationActions(onSelected: selected.add),
                  ),
                ),
              ),
            );
            await t.pumpAndSettle();
            final cards = [
              for (final feature in AiFeature.values)
                find.byKey(Key('openAi_${feature.name}')),
            ];
            final sizes = cards.map(t.getSize).toList(),
                positions = cards.map(t.getTopLeft).toList();
            expect(sizes[0], sizes[1]);
            expect(sizes[1], sizes[2]);
            expect(sizes[0].height, greaterThanOrEqualTo(48));
            if (scenario.$3 == 3) {
              expect(positions[0].dy, positions[1].dy);
              expect(positions[1].dy, positions[2].dy);
            }
            if (scenario.$3 == 2) {
              expect(positions[0].dy, positions[1].dy);
              expect(positions[2].dy, greaterThan(positions[0].dy));
            }
            if (scenario.$3 == 1) {
              expect(positions[1].dy, greaterThan(positions[0].dy));
              expect(positions[2].dy, greaterThan(positions[1].dy));
            }
            final node = t.getSemantics(
              find.bySemanticsLabel('AI 질문하기, Gemini 기본 선택, GPT-4o-mini 선택 가능'),
            );
            expect(
              node.getSemanticsData().hasAction(SemanticsAction.tap),
              true,
            );
            for (final card in cards) {
              await t.ensureVisible(card);
              await t.tap(card);
              await t.pumpAndSettle();
            }
            expect(selected, AiFeature.values);
            expect(t.takeException(), isNull);
          } finally {
            semantics.dispose();
          }
        },
      );
    }
  }

  testWidgets('old contract notice is neutral and neither model can generate', (
    t,
  ) async {
    final m = AiMock()..goalScoped = false;
    await open(t, m);
    final noticeFinder = find.byKey(const Key('serverUpdateNotice'));
    await t.scrollUntilVisible(
      noticeFinder,
      160,
      scrollable: find.byType(Scrollable).first,
    );
    final notice = t.widget<StateNotice>(noticeFinder);
    expect(notice.title, '서버 업데이트 필요');
    expect(notice.warning, false);
    expect(notice.icon, Icons.system_update_alt);
    expect(find.textContaining('AI 생성이 차단'), findsOneWidget);
    await t.scrollUntilVisible(
      find.byKey(const Key('sendAiRequest')),
      160,
      scrollable: find.byType(Scrollable).first,
    );
    expect(
      t.widget<FilledButton>(find.byKey(const Key('sendAiRequest'))).onPressed,
      isNull,
    );
    await t.scrollUntilVisible(
      find.byKey(const Key('aiModel_gpt')),
      -160,
      scrollable: find.byType(Scrollable).first,
    );
    await t.ensureVisible(find.byKey(const Key('aiModel_gpt')));
    await t.tap(find.byKey(const Key('aiModel_gpt')));
    await t.pumpAndSettle();
    await t.scrollUntilVisible(
      find.byKey(const Key('sendAiRequest')),
      160,
      scrollable: find.byType(Scrollable).first,
    );
    expect(
      t.widget<FilledButton>(find.byKey(const Key('sendAiRequest'))).onPressed,
      isNull,
    );
    await expectLater(
      apiFor(m).generateAi(
        feature: AiFeature.question,
        goalId: 1,
        question: '가상 질문',
        confirmed: true,
      ),
      throwsA(isA<ApiFailure>()),
    );
    expect(m.generated, 0);
    expect(t.takeException(), isNull);
  });

  testWidgets('network errors retain error style rather than update notice', (
    t,
  ) async {
    final m = BrokenSetup();
    await open(t, m);
    expect(find.byKey(const Key('serverUpdateNotice')), findsNothing);
    final errors = t
        .widgetList<StateNotice>(find.byType(StateNotice))
        .where((n) => n.text.contains('503'));
    expect(errors.single.warning, true);
    expect(m.generated, 0);
  });

  testWidgets('goal A and B cards navigate with the selected goal without AI', (
    t,
  ) async {
    final m = AiMock();
    final api = apiFor(m);
    final goal = ValueNotifier<int>(1);
    addTearDown(goal.dispose);
    await t.pumpWidget(
      MaterialApp(
        theme: AppDesign.theme(Brightness.dark),
        home: ValueListenableBuilder<int>(
          valueListenable: goal,
          builder: (context, id, _) => ServerAiHistory(
            key: ValueKey(id),
            api: api,
            goalId: id,
            goalName: '목표 $id',
          ),
        ),
      ),
    );
    for (final id in [1, 2]) {
      goal.value = id;
      await t.pumpAndSettle();
      await t.tap(find.text('질문·코칭 생성'));
      await t.pumpAndSettle();
      for (final feature in AiFeature.values) {
        await press(t, Key('openAi_${feature.name}'));
        final page = t.widget<ServerAiRequest>(find.byType(ServerAiRequest));
        expect(page.goalId, id);
        expect(page.feature, feature);
        await t.tap(find.byIcon(Icons.arrow_back));
        await t.pumpAndSettle();
      }
    }
    expect(m.generated, 0);
    expect(t.takeException(), isNull);
  });
}
