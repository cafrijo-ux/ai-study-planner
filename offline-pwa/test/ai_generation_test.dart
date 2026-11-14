import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/app_design.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/server_ai_request.dart';
import 'package:ai_study_planner_mobile/server_ai_history.dart';

class AiMock implements Transport {
  final calls = <(String, Uri, Map<String, String>, String?)>[];
  int generated = 0;
  bool enabled = true,
      goalScoped = true,
      rule = false,
      demo = false,
      lose = false,
      badJson = false,
      saveFail = false,
      wrongGoal = false,
      wrongProvider = false,
      partial = false;
  int? error;
  Completer<void>? hold;
  final rows = <Map<String, dynamic>>[];
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    calls.add((method, uri, Map.of(headers), body));
    final goal = int.tryParse(uri.queryParameters['goalId'] ?? '') ?? 1;
    if (uri.path == '/subjects') {
      return Reply(
        200,
        jsonEncode([
          {
            'subjectId': goal * 10,
            'goalId': goal,
            'userId': 1,
            'subjectName': '가상 과목',
          },
        ]),
      );
    }
    if (uri.path == '/auth/me') {
      return Reply(200, '{"authenticated":true,"userId":1}');
    }
    if (uri.path == '/auth/login') return Reply(200, '{}');
    if (uri.path == '/auth/csrf') {
      return Reply(200, '{"headerName":"X-CSRF-TOKEN","token":"fake-csrf"}', [
        Cookie('JSESSIONID', 'fake-session')..path = '/',
      ]);
    }
    if (uri.path == '/study-question/capabilities') {
      return Reply(
        200,
        jsonEncode({
          'goalScopedResponse': goalScoped,
          'gemini': {
            'enabled': enabled,
            'configured': enabled,
            'model': 'gemini-test',
          },
          'gpt': {
            'enabled': enabled,
            'configured': enabled,
            'model': 'gpt-4o-mini',
          },
        }),
      );
    }
    if (uri.path == '/llm-config-check') {
      return Reply(
        200,
        jsonEncode({
          'provider': 'gemini',
          'model': 'gemini-test',
          'demoMode': demo,
          'ready': enabled && !demo,
        }),
      );
    }
    if (uri.path == '/gpt-weekly-coaching/preview') {
      return Reply(
        200,
        jsonEncode({
          'goalId': goal,
          'model': 'gpt-4o-mini',
          'enabled': enabled,
          'apiKeyConfigured': enabled,
          'dataReady': true,
          'periodStart': '2026-10-22',
          'periodEnd': '2026-10-28',
          'totalStudyMinutes': 40,
          'recordCount': 1,
          'responseSource': 'preview',
        }),
      );
    }
    if (uri.path == '/ai-responses') {
      return Reply(
        200,
        jsonEncode(
          rows
              .where(
                (r) =>
                    r['goalId'] == goal &&
                    r['feature'] == uri.queryParameters['feature'],
              )
              .toList(),
        ),
      );
    }
    if (uri.path == '/study-questions') {
      return Reply(
        200,
        jsonEncode([
          {'questionId': 7, 'userId': 1, 'question': '프로세스와 스레드의 차이'},
          {'questionId': 8, 'userId': 2, 'question': '다른 사용자 비공개'},
        ]),
      );
    }
    expect([
      '/study-question',
      '/llm-coaching',
      '/gpt-weekly-coaching',
    ], contains(uri.path));
    generated++;
    expect(headers['X-CSRF-TOKEN'], 'fake-csrf');
    if (hold != null) await hold!.future;
    if (error != null) return Reply(error!, '{}');
    final question = uri.path == '/study-question';
    final daily = uri.path == '/llm-coaching';
    final payload = body == null
        ? <String, dynamic>{}
        : jsonDecode(body) as Map;
    final provider = question
        ? payload['provider'] as String
        : daily
        ? 'gemini'
        : 'gpt';
    final model = provider == 'gemini' ? 'gemini-test' : 'gpt-4o-mini';
    final feature = question
        ? 'QUESTION'
        : daily
        ? 'DAILY_COACHING'
        : 'WEEKLY_COACHING';
    if (!saveFail) {
      rows.add({
        'responseId': generated,
        'goalId': goal,
        'questionId': question ? 7 : null,
        'feature': feature,
        'actualProvider': provider == 'gpt' ? 'openai' : 'gemini',
        'model': model,
        'responseSource': rule ? 'RULE' : 'PROVIDER',
        'responseBody': '가상 답변',
        'responseComplete': !partial,
        'createdAt': '2026-10-28T12:00:00',
      });
    }
    if (lose) throw const ApiFailure('가상 응답 유실');
    if (badJson) return Reply(200, 'bad-json');
    return Reply(
      200,
      jsonEncode({
        'goalId': wrongGoal ? goal + 1 : goal,
        'model': model,
        'responseModel': model,
        'responseSource': rule ? 'rule' : 'provider',
        'responseComplete': !partial,
        'demoMode': false,
        if (question) ...{
          'requestedProvider': provider,
          'answerSource': rule
              ? 'rule'
              : wrongProvider
              ? (provider == 'gpt' ? 'gemini' : 'gpt')
              : provider,
          'question': payload['question'],
          'aiAdvice': '가상 답변',
          'saved': !saveFail,
          'questionId': 7,
        },
        if (!question) ...{
          'provider': daily ? 'gemini' : 'gpt',
          'llmResponse': '가상 답변',
        },
        'responseSaved': !saveFail,
        'responseId': generated,
        if (saveFail) 'saveWarning': '가상 저장 실패 · 재호출 금지',
      }),
    );
  }

  @override
  void close() {}
}

PlannerApi apiFor(AiMock mock) => PlannerApi(
  base: Uri.parse('http://localhost:8080'),
  transport: mock,
  debugLoopback: true,
);
Future<void> open(
  WidgetTester t,
  AiMock mock, {
  AiFeature feature = AiFeature.question,
  double scale = 1,
}) async {
  t.view.physicalSize = const Size(360, 800);
  t.view.devicePixelRatio = 1;
  addTearDown(t.view.resetPhysicalSize);
  addTearDown(t.view.resetDevicePixelRatio);
  final api = apiFor(mock);
  addTearDown(api.close);
  await t.pumpWidget(
    MaterialApp(
      theme: AppDesign.theme(Brightness.dark),
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context)
            .copyWith(textScaler: TextScaler.linear(scale)),
        child: child!,
      ),
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              final result = await Navigator.of(context).push<AiFlowResult>(
                MaterialPageRoute(
                  builder: (_) => ServerAiRequest(
                    api: api,
                    goalId: 1,
                    goalName: '가상 목표 A',
                    feature: feature,
                  ),
                ),
              );
              if (context.mounted && result?.expired == true) {
                ScaffoldMessenger.of(context)
                    .showSnackBar(const SnackBar(content: Text('재로그인 안내')));
              }
            },
            child: const Text('AI 열기'),
          ),
        ),
      ),
    ),
  );
  await t.tap(find.text('AI 열기'));
  await t.pumpAndSettle();
}

Future<void> press(WidgetTester t, Key key) async {
  await t.scrollUntilVisible(
    find.byKey(key),
    160,
    scrollable: find.byType(Scrollable).first,
  );
  await t.ensureVisible(find.byKey(key));
  await t.pumpAndSettle();
  await t.tap(find.byKey(key));
  await t.pumpAndSettle();
}

Future<void> ask(WidgetTester t) async {
  await t.enterText(find.byKey(const Key('aiQuestion')), '프로세스와 스레드의 차이');
  await press(t, const Key('sendAiRequest'));
}

void main() {
  testWidgets(
    'AI tab opens three existing generation contracts without auto call',
    (t) async {
      final m = AiMock();
      final api = apiFor(m);
      await t.pumpWidget(
        MaterialApp(
          theme: AppDesign.theme(Brightness.light),
          home: ServerAiHistory(api: api, goalId: 1, goalName: '가상 목표 A'),
        ),
      );
      await t.pumpAndSettle();
      await t.tap(find.text('질문·코칭 생성'));
      await t.pumpAndSettle();
      for (final feature in AiFeature.values) {
        await press(t, Key('openAi_${feature.name}'));
        expect(find.byType(ServerAiRequest), findsOneWidget);
        expect(m.generated, 0);
        await t.tap(find.byIcon(Icons.arrow_back));
        await t.pumpAndSettle();
      }
      expect(m.generated, 0);
      expect(t.takeException(), isNull);
    },
  );
  test('goal B history never includes goal A generated response', () async {
    final m = AiMock();
    final api = apiFor(m);
    await api.generateAi(
      feature: AiFeature.question,
      goalId: 1,
      question: '가상 질문',
      confirmed: true,
    );
    expect(await api.questionHistory(2), isEmpty);
    expect((await api.questionHistory(1)).single['goalId'], 1);
    expect(m.generated, 1);
  });
  test('foreign legacy question is not joined by guessed ownership', () async {
    final m = AiMock();
    final api = apiFor(m);
    await api.generateAi(
      feature: AiFeature.question,
      goalId: 1,
      question: '가상 질문',
      confirmed: true,
    );
    m.rows.single['questionId'] = 8;
    expect(
      (await api.questionHistory(1)).single.containsKey('questionText'),
      false,
    );
    expect(m.generated, 1);
  });
  testWidgets('rule fallback is never labeled actual Gemini or GPT answer', (
    t,
  ) async {
    final m = AiMock()..rule = true;
    await open(t, m);
    await ask(t);
    await t.tap(find.byKey(const Key('confirmAiRequest')));
    await t.pumpAndSettle();
    await t.scrollUntilVisible(
      find.text('규칙 기반 · 실제 AI 응답 아님'),
      160,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('규칙 기반 · 실제 AI 응답 아님'), findsOneWidget);
    expect(find.text('모델: rule-based'), findsOneWidget);
    expect(m.generated, 1);
  });
  testWidgets('late goal A result cannot overwrite new goal B screen', (
    t,
  ) async {
    final m = AiMock()..hold = Completer<void>();
    final api = apiFor(m);
    final goal = ValueNotifier<int>(1);
    addTearDown(goal.dispose);
    await t.pumpWidget(
      MaterialApp(
        theme: AppDesign.theme(Brightness.light),
        home: ValueListenableBuilder<int>(
          valueListenable: goal,
          builder: (context, id, _) => ServerAiRequest(
            api: api,
            goalId: id,
            goalName: '가상 목표 $id',
            feature: AiFeature.question,
          ),
        ),
      ),
    );
    await t.pumpAndSettle();
    await ask(t);
    await t.tap(find.byKey(const Key('confirmAiRequest')));
    await t.pump();
    await t.pump(const Duration(milliseconds: 300));
    expect(m.generated, 1);
    goal.value = 2;
    await t.pumpAndSettle();
    m.hold!.complete();
    await t.pumpAndSettle();
    expect(find.text('받은 답변'), findsNothing);
    expect(find.text('가상 답변'), findsNothing);
    expect(m.generated, 1);
    expect(t.takeException(), isNull);
  });
  test('old server contract blocks before any paid question', () async {
    final m = AiMock()..goalScoped = false;
    final api = apiFor(m);
    await expectLater(
      api.generateAi(
        feature: AiFeature.question,
        goalId: 1,
        question: '가상 질문',
        confirmed: true,
      ),
      throwsA(isA<ApiFailure>()),
    );
    expect(m.generated, 0);
  });
  for (final provider in ['gemini', 'gpt']) {
    test(
      '$provider selection sends one request with exact DTO and cookies',
      () async {
        final m = AiMock();
        final api = apiFor(m);
        addTearDown(api.close);
        await api.login('virtual', 'fake');
        final answer = await api.generateAi(
          feature: AiFeature.question,
          goalId: 1,
          provider: provider,
          question: '프로세스와 스레드의 차이',
          confirmed: true,
        );
        final write = m.calls.singleWhere(
          (c) => c.$2.path == '/study-question',
        );
        expect(jsonDecode(write.$4!)['provider'], provider);
        expect(jsonDecode(write.$4!)['confirmPaidCall'], true);
        expect(write.$2.queryParameters, {'goalId': '1'});
        expect(write.$3['Cookie'], 'JSESSIONID=fake-session');
        expect(answer['_text'], '가상 답변');
        expect(m.generated, 1);
        final history = await api.questionHistory(1);
        expect(history.single['questionText'], '프로세스와 스레드의 차이');
        expect(m.generated, 1);
        expect(await api.questionHistory(3), isEmpty);
      },
    );
  }
  test(
    'empty question, invalid provider and missing consent send nothing',
    () async {
      final m = AiMock();
      final api = apiFor(m);
      addTearDown(api.close);
      for (final options in [
        (true, 'gemini', ''),
        (true, 'other', '질문'),
        (false, 'gemini', '질문'),
      ]) {
        await expectLater(
          api.generateAi(
            feature: AiFeature.question,
            goalId: 1,
            provider: options.$2,
            question: options.$3,
            confirmed: options.$1,
          ),
          throwsA(isA<ApiFailure>()),
        );
      }
      expect(m.calls, isEmpty);
    },
  );
  test('concurrent requests cannot spend twice', () async {
    final m = AiMock()..hold = Completer<void>();
    final api = apiFor(m);
    addTearDown(api.close);
    final one = api.generateAi(
      feature: AiFeature.question,
      goalId: 1,
      question: '질문',
      confirmed: true,
    );
    await Future<void>.delayed(Duration.zero);
    await expectLater(
      api.generateAi(
        feature: AiFeature.question,
        goalId: 1,
        provider: 'gpt',
        question: '질문',
        confirmed: true,
      ),
      throwsA(isA<ApiFailure>()),
    );
    m.hold!.complete();
    await one;
    expect(m.generated, 1);
  });
  for (final feature in [AiFeature.daily, AiFeature.weekly]) {
    test('$feature reuses separate route; daily GET includes CSRF', () async {
      final m = AiMock();
      final api = apiFor(m);
      addTearDown(api.close);
      await api.generateAi(feature: feature, goalId: 1, confirmed: true);
      final call = m.calls.singleWhere(
        (c) =>
            c.$2.path ==
            (feature == AiFeature.daily
                ? '/llm-coaching'
                : '/gpt-weekly-coaching'),
      );
      expect(call.$1, feature == AiFeature.daily ? 'GET' : 'POST');
      expect(call.$3['X-CSRF-TOKEN'], 'fake-csrf');
      expect(
        call.$4,
        feature == AiFeature.daily
            ? null
            : jsonEncode({'confirmPaidCall': true}),
      );
      expect(
        m.calls.any((c) => c.$2.path.contains('study-reschedule-save')),
        false,
      );
      expect(m.generated, 1);
    });
  }
  for (final kind in ['network', 'json', 'goal', 'provider', '500']) {
    test('$kind failure never retries and remains uncertain', () async {
      final m = AiMock()
        ..lose = kind == 'network'
        ..badJson = kind == 'json'
        ..wrongGoal = kind == 'goal'
        ..wrongProvider = kind == 'provider'
        ..error = kind == '500' ? 500 : null;
      final api = apiFor(m);
      addTearDown(api.close);
      await expectLater(
        api.generateAi(
          feature: AiFeature.question,
          goalId: 1,
          question: '질문',
          confirmed: true,
        ),
        throwsA(
          isA<AiGenerationFailure>().having(
            (e) => e.uncertain,
            'uncertain',
            true,
          ),
        ),
      );
      await expectLater(
        api.generateAi(
          feature: AiFeature.question,
          goalId: 1,
          question: '질문',
          confirmed: true,
        ),
        throwsA(isA<AiGenerationFailure>()),
      );
      expect(m.generated, 1);
    });
  }
  for (final feature in AiFeature.values) {
    test(
      'disabled $feature blocks generation and does not enable server',
      () async {
        final m = AiMock()..enabled = false;
        final api = apiFor(m);
        addTearDown(api.close);
        await expectLater(
          api.generateAi(
            feature: feature,
            goalId: 1,
            question: '질문',
            confirmed: true,
          ),
          throwsA(isA<ApiFailure>()),
        );
        expect(m.generated, 0);
      },
    );
  }
  testWidgets('Gemini default and GPT selection cancel sends no generation', (
    t,
  ) async {
    final m = AiMock();
    await open(t, m);
    expect(
      t.widget<ChoiceChip>(find.byKey(const Key('aiModel_gemini'))).selected,
      true,
    );
    await t.tap(find.byKey(const Key('aiModel_gpt')));
    await t.pumpAndSettle();
    expect(
      t.widget<ChoiceChip>(find.byKey(const Key('aiModel_gpt'))).selected,
      true,
    );
    await ask(t);
    expect(find.textContaining('모델: gpt-4o-mini'), findsOneWidget);
    await t.tap(find.text('취소'));
    await t.pumpAndSettle();
    expect(m.generated, 0);
  });
  testWidgets('empty UI input blocks dialog', (t) async {
    final m = AiMock();
    await open(t, m);
    await press(t, const Key('sendAiRequest'));
    expect(find.text('질문을 입력하세요.'), findsOneWidget);
    expect(find.byKey(const Key('confirmAiRequest')), findsNothing);
    expect(m.generated, 0);
  });
  testWidgets(
    'answer and saved question are separate; history refresh never generates',
    (t) async {
      final m = AiMock();
      await open(t, m);
      await ask(t);
      await t.tap(find.byKey(const Key('confirmAiRequest')));
      await t.pumpAndSettle();
      await t.scrollUntilVisible(
        find.text('받은 답변'),
        180,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('질문: 프로세스와 스레드의 차이'), findsOneWidget);
      await press(t, const Key('reloadAiRequestHistory'));
      await t.scrollUntilVisible(
        find.text('저장 질문: 프로세스와 스레드의 차이'),
        180,
        scrollable: find.byType(Scrollable).first,
      );
      expect(m.generated, 1);
    },
  );
  testWidgets('save failure preserves answer and never retries', (t) async {
    final m = AiMock()..saveFail = true;
    await open(t, m);
    await ask(t);
    await t.tap(find.byKey(const Key('confirmAiRequest')));
    await t.pumpAndSettle();
    await t.scrollUntilVisible(
      find.text('가상 저장 실패 · 재호출 금지'),
      180,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('가상 답변'), findsOneWidget);
    expect(m.generated, 1);
  });
  for (final code in [401, 403]) {
    testWidgets('$code authentication or CSRF failure returns to login', (
      t,
    ) async {
      final m = AiMock()..error = code;
      await open(t, m);
      await ask(t);
      await t.tap(find.byKey(const Key('confirmAiRequest')));
      await t.pumpAndSettle();
      expect(find.text('재로그인 안내'), findsOneWidget);
      expect(m.generated, 1);
    });
  }
  testWidgets('in-flight leave warns and never resends', (t) async {
    final m = AiMock()..hold = Completer<void>();
    await open(t, m);
    await ask(t);
    await t.tap(find.byKey(const Key('confirmAiRequest')));
    await t.pump();
    await t.pump(const Duration(milliseconds: 400));
    await t.binding.handlePopRoute();
    await t.pump(const Duration(milliseconds: 400));
    await t.tap(find.byKey(const Key('leaveAiInFlight')));
    await t.pump(const Duration(milliseconds: 400));
    m.hold!.complete();
    await t.pumpAndSettle();
    expect(m.generated, 1);
    expect(find.text('AI 열기'), findsOneWidget);
  });
  testWidgets('disabled demo daily is not a real Gemini success', (t) async {
    final m = AiMock()..demo = true;
    await open(t, m, feature: AiFeature.daily);
    await t.scrollUntilVisible(
      find.byKey(const Key('sendAiRequest')),
      160,
      scrollable: find.byType(Scrollable).first,
    );
    expect(
      t.widget<FilledButton>(find.byKey(const Key('sendAiRequest'))).onPressed,
      isNull,
    );
    expect(find.textContaining('데모 모드'), findsOneWidget);
    expect(m.generated, 0);
  });
  testWidgets('weekly preview and partial answer are distinct', (t) async {
    final m = AiMock()..partial = true;
    await open(t, m, feature: AiFeature.weekly);
    await press(t, const Key('sendAiRequest'));
    await t.tap(find.byKey(const Key('confirmAiRequest')));
    await t.pumpAndSettle();
    await t.scrollUntilVisible(
      find.text('일부 응답 · 미완결'),
      160,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('일부 응답 · 미완결'), findsOneWidget);
    expect(m.generated, 1);
  });
  testWidgets('small dark screen, large text and keyboard remain scrollable', (
    t,
  ) async {
    final m = AiMock();
    await open(t, m, scale: 1.8);
    t.view.viewInsets = const FakeViewPadding(bottom: 280);
    addTearDown(t.view.resetViewInsets);
    await t.pumpAndSettle();
    await t.scrollUntilVisible(
      find.byKey(const Key('aiQuestion')),
      140,
      scrollable: find.byType(Scrollable).first,
    );
    await t.enterText(find.byKey(const Key('aiQuestion')), '가상 질문');
    await t.scrollUntilVisible(
      find.byKey(const Key('sendAiRequest')),
      140,
      scrollable: find.byType(Scrollable).first,
    );
    await t.pumpAndSettle();
    expect(
      find.byKey(const Key('sendAiRequest')).hitTestable(),
      findsOneWidget,
    );
    expect(t.takeException(), isNull);
    expect(m.generated, 0);
  });
}
