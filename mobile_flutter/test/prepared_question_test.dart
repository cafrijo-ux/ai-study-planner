import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';

import 'ai_generation_test.dart' show AiMock, apiFor, open, press;

const approvedQuestion = '프로세스와 스레드의 차이를 두 문장으로 설명해 주세요.';
const approvedInputs = {
  'provider': 'gemini',
  'question': approvedQuestion,
  'subject': '가상 운영체제',
  'situation': '복습',
  'understanding': '보통',
};

class PreparedMock extends AiMock {
  int checks = 0, posts = 0;
  bool used = false, disabled = false, finalMismatch = false;
  int? preflightStatus;
  Completer<void>? pending;
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    if (uri.path == '/study-question/capabilities') {
      final original = await super.send(method, uri, headers, body);
      final raw = jsonDecode(original.body) as Map;
      return Reply(
        200,
        jsonEncode({
          ...raw,
          'preparedGeminiContract': 1,
          'preparedGeminiMode': preparedGeminiMode,
          'gpt': {...raw['gpt'] as Map, 'enabled': false},
        }),
      );
    }
    if (uri.path == '/prepared-gemini/approval') {
      return Reply(
        200,
        jsonEncode({
          'contractVersion': 1,
          'mode': preparedGeminiMode,
          'model': 'gemini-test',
          'approvedGoalId': 1,
          'selectedGoalId': int.parse(uri.queryParameters['goalId']!),
          ...approvedInputs,
          'generationEnabled': !disabled,
          'callUsed': used,
        }),
      );
    }
    if (uri.path == '/prepared-gemini/preflight') {
      checks++;
      expect(method, 'POST');
      expect(headers['X-CSRF-TOKEN'], 'fake-csrf');
      final b = jsonDecode(body!) as Map;
      expect(b.containsKey('confirmPaidCall'), false);
      if (pending != null) await pending!.future;
      if (preflightStatus != null) return Reply(preflightStatus!, '{}');
      final errors = <Map<String, String>>[];
      void error(String field, String message) => errors.add({
        'field': field,
        'code': 'APPROVED_${field.toUpperCase()}_MISMATCH',
        'message': message,
      });
      if (uri.queryParameters['goalId'] != '1') error('goalId', '승인 목표 불일치');
      if (disabled) error('serverMode', '서버 생성 비활성');
      if (used) error('callLimit', '호출 한도 사용됨 · 재전송 금지');
      for (final entry in approvedInputs.entries) {
        if (b[entry.key] != entry.value) {
          error(entry.key, '승인 ${entry.key} 불일치');
        }
      }
      if (b['model'] != 'gemini-test') error('model', '승인 모델 불일치');
      return Reply(
        200,
        jsonEncode({
          'contractVersion': 1,
          'mode': preparedGeminiMode,
          'model': 'gemini-test',
          'approvedGoalId': 1,
          'valid': errors.isEmpty,
          'errors': errors,
          'providerCalls': 0,
        }),
      );
    }
    if (uri.path == '/study-question') {
      posts++;
      if (finalMismatch) {
        return Reply(
          400,
          jsonEncode({
            'code': 'APPROVED_REQUEST_MISMATCH',
            'errors': [
              {'field': 'question', 'message': '승인 질문 불일치'},
            ],
          }),
        );
      }
    }
    return super.send(method, uri, headers, body);
  }
}

Future<PreparedQuestionCheck> check(
  PlannerApi api,
  AiSetup setup, {
  int goal = 1,
  String provider = 'gemini',
  String question = approvedQuestion,
  String subject = '가상 운영체제',
  String situation = '복습',
  String understanding = '보통',
}) => api.checkPreparedQuestion(
  setup: setup,
  goalId: goal,
  provider: provider,
  question: question,
  subject: subject,
  situation: situation,
  understanding: understanding,
);
Future<Map<String, dynamic>> generate(
  PlannerApi api, {
  bool confirmed = true,
  String question = approvedQuestion,
}) => api.generateAi(
  feature: AiFeature.question,
  goalId: 1,
  provider: 'gemini',
  question: question,
  subject: '가상 운영체제',
  situation: '복습',
  understanding: '보통',
  confirmed: confirmed,
);
Future<void> changeQuestion(WidgetTester t, String text) async {
  final field = find.byKey(const Key('aiQuestion'));
  await t.scrollUntilVisible(
    field,
    160,
    scrollable: find.byType(Scrollable).first,
  );
  await t.ensureVisible(field);
  await t.pumpAndSettle();
  await t.enterText(field, text);
  await t.pumpAndSettle();
}

void main() {
  test('approved metadata only on advertised environment; repeated preflight is non-generating', () async {
    final m = PreparedMock();
    final api = apiFor(m);
    final setup = await api.prepareAi(AiFeature.question, 1);
    expect(setup.preparedQuestion!.value('question'), approvedQuestion);
    expect(setup.preparedQuestion!.goalId, 1);
    for (int i = 0; i < 3; i++) {
      expect((await check(api, setup)).valid, true);
    }
    expect(m.checks, 3);
    expect(m.posts, 0);
    expect(m.generated, 0);
    final ordinary = AiMock();
    expect(
      (await apiFor(
        ordinary,
      ).prepareAi(AiFeature.question, 1)).preparedQuestion,
      isNull,
    );
    expect(
      ordinary.calls.any((c) => c.$2.path.startsWith('/prepared-gemini')),
      false,
    );
  });
  for (final variant in [
    'spaces',
    'dot',
    'subject',
    'situation',
    'understanding',
    'goal',
    'gpt',
  ]) {
    test('$variant mismatch reports field and sends no generation', () async {
      final m = PreparedMock();
      final api = apiFor(m);
      final setup = await api.prepareAi(AiFeature.question, 1);
      final result = await check(
        api,
        setup,
        question: variant == 'spaces'
            ? '프로세스와 스레드의 차이를 두문장으로 설명해주세요'
            : variant == 'dot'
            ? approvedQuestion.substring(0, approvedQuestion.length - 1)
            : approvedQuestion,
        subject: variant == 'subject' ? '다른 과목' : '가상 운영체제',
        situation: variant == 'situation' ? '다른 상황' : '복습',
        understanding: variant == 'understanding' ? '낮음' : '보통',
        goal: variant == 'goal' ? 2 : 1,
        provider: variant == 'gpt' ? 'gpt' : 'gemini',
      );
      expect(result.valid, false);
      expect(
        result.errors.containsKey(
          {
                'spaces': 'question',
                'dot': 'question',
                'gpt': 'provider',
                'goal': 'goalId',
              }[variant] ??
              variant,
        ),
        true,
      );
      expect(m.posts, 0);
      expect(m.generated, 0);
    });
  }
  test('generation rechecks inputs, requires cost consent, and keeps final guard message', () async {
    final m = PreparedMock();
    final api = apiFor(m);
    await expectLater(
      generate(api, confirmed: false),
      throwsA(isA<ApiFailure>()),
    );
    expect(m.checks, 0);
    await expectLater(
      generate(api, question: '다른 질문'),
      throwsA(isA<AiGenerationFailure>()),
    );
    expect(m.posts, 0);
    expect(m.generated, 0);
    m.finalMismatch = true;
    await expectLater(
      generate(api),
      throwsA(
        isA<AiGenerationFailure>().having(
          (e) => e.message,
          'message',
          contains('승인 질문 불일치'),
        ),
      ),
    );
    expect(m.posts, 1);
    expect(m.generated, 0);
    await expectLater(generate(api), throwsA(isA<AiGenerationFailure>()));
    expect(m.posts, 1);
  });
  test('correct generation performs preflight then exactly one selected model request', () async {
    final m = PreparedMock();
    final api = apiFor(m);
    final result = await generate(api);
    expect(result['question'], approvedQuestion);
    expect(m.checks, 1);
    expect(m.posts, 1);
    expect(m.generated, 1);
  });
  test('used and disabled approvals fail free preflight', () async {
    for (final used in [true, false]) {
      final m = PreparedMock()
        ..used = used
        ..disabled = !used;
      final api = apiFor(m);
      final setup = await api.prepareAi(AiFeature.question, 1);
      final result = await check(api, setup);
      expect(result.valid, false);
      expect(
        result.errors.containsKey(used ? 'callLimit' : 'serverMode'),
        true,
      );
      expect(m.generated, 0);
    }
  });
  testWidgets(
    'explicit loading, successful free check and cost cancellation never generate',
    (t) async {
      final m = PreparedMock();
      await open(t, m);
      expect(m.posts, 0);
      await press(t, const Key('loadApprovedQuestion'));
      await t.scrollUntilVisible(
        find.byKey(const Key('aiQuestion')),
        160,
        scrollable: find.byType(Scrollable).first,
      );
      expect(
        t
            .widget<TextFormField>(find.byKey(const Key('aiQuestion')))
            .controller!
            .text,
        approvedQuestion,
      );
      await press(t, const Key('checkApprovedQuestion'));
      expect(find.text('사전 검증 통과'), findsOneWidget);
      expect(find.text('AI 요청 및 비용 확인'), findsNothing);
      expect(m.generated, 0);
      await press(t, const Key('sendAiRequest'));
      expect(find.text('AI 요청 및 비용 확인'), findsOneWidget);
      expect(m.generated, 0);
      await t.tap(find.text('취소'));
      await t.pumpAndSettle();
      expect(m.posts, 0);
      await changeQuestion(t, '프로세스와 스레드의 차이를 두문장으로 설명해주세요');
      expect(find.text('사전 검증 통과'), findsNothing);
      await press(t, const Key('sendAiRequest'));
      await t.scrollUntilVisible(
        find.byKey(const Key('preparedQuestionResult')),
        -160,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.textContaining('승인 question 불일치'), findsOneWidget);
      expect(find.text('AI 요청 및 비용 확인'), findsNothing);
      expect(m.posts, 0);
      expect(m.generated, 0);
    },
  );
  testWidgets('edited input while check waits invalidates late result', (
    t,
  ) async {
    final m = PreparedMock();
    await open(t, m);
    await press(t, const Key('loadApprovedQuestion'));
    m.pending = Completer<void>();
    await t.scrollUntilVisible(
      find.byKey(const Key('checkApprovedQuestion')),
      -160,
      scrollable: find.byType(Scrollable).first,
    );
    await t.ensureVisible(find.byKey(const Key('checkApprovedQuestion')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const Key('checkApprovedQuestion')));
    await t.pump();
    await changeQuestion(t, '검증 중 바뀐 질문');
    m.pending!.complete();
    await t.pumpAndSettle();
    expect(find.text('사전 검증 통과'), findsNothing);
    expect(m.posts, 0);
    expect(m.generated, 0);
  });
  for (final status in [401, 403]) {
    testWidgets(
      'preflight $status follows existing authentication/CSRF recovery without generation',
      (t) async {
        final m = PreparedMock()..preflightStatus = status;
        await open(t, m);
        await press(t, const Key('loadApprovedQuestion'));
        await press(t, const Key('checkApprovedQuestion'));
        expect(find.text('재로그인 안내'), findsOneWidget);
        expect(m.posts, 0);
        expect(m.generated, 0);
      },
    );
  }
}
