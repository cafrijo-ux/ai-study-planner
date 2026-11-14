import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/app_design.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/server_ai_request.dart';

import 'ai_h2_contract_test.dart' show ContractTransport;

// Snapshot from FakeGeminiShutdownH2Tests (MockMvc, no HTTP/provider network).
class FakeShutdownTransport extends ContractTransport {
  FakeShutdownTransport() {
    final contract = jsonDecode(
      File('test/fixtures/gemini_fake_shutdown_contract.json')
          .readAsStringSync(),
    ) as Map<String, dynamic>;
    expect(contract['syntheticOnly'], true);
    expect(contract['executionMode'], 'GEMINI_SHUTDOWN_FAKE_H2');
    data
      ..clear()
      ..addAll(contract);
  }

  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) {
    if (uri.path == '/study-questions') {
      return Future.value(
        Reply(
          200,
          jsonEncode([
            {
              'questionId': data['gemini']['questionId'],
              'userId': 1,
              'question': data['gemini']['question'],
            },
          ]),
        ),
      );
    }
    if (uri.path == '/study-question') {
      final input = jsonDecode(body!) as Map;
      expect(input['provider'], 'gemini');
      expect(input['question'], data['gemini']['question']);
      expect(input['subject'], '가상 운영체제');
      expect(input['situation'], '복습');
      expect(input['understanding'], '보통');
      expect(input['confirmPaidCall'], true);
    }
    return super.send(method, uri, headers, body);
  }
}

void main() {
  test(
    'Fake capability is visibly Mock, Gemini only, without paid preflight',
    () async {
      final transport = FakeShutdownTransport();
      final setup = await transport.api().prepareAi(
        AiFeature.question,
        transport.data['goalA'] as int,
      );
      expect(isIsolatedAiMock(setup.raw), true);
      expect(setup.ready('gemini'), true);
      expect(setup.ready('gpt'), false);
      expect(setup.model('gemini'), 'gemini-shutdown-fake');
      expect(setup.preparedQuestion, isNull);
      expect(transport.generated, 0);
    },
  );

  test(
    'Fake reply and saved history retain provenance and goal separation',
    () async {
      final transport = FakeShutdownTransport();
      final api = transport.api();
      final goal = transport.data['goalA'] as int;
      final result = await api.generateAi(
        feature: AiFeature.question,
        goalId: goal,
        question: transport.data['gemini']['question'] as String,
        subject: '가상 운영체제',
        situation: '복습',
        confirmed: true,
      );
      expect(isIsolatedAiMock(result), true);
      expect(result['responseSaved'], true);
      expect(result['responseComplete'], isNull);
      expect(result['_text'], contains('격리 Mock · 실제 AI 아님'));
      expect(await api.questionHistory(goal), hasLength(1));
      expect(
        await api.questionHistory(transport.data['goalB'] as int),
        isEmpty,
      );
      expect(transport.generated, 1);
    },
  );

  for (final mismatch in ['goal', 'model']) {
    test('Fake marker never bypasses $mismatch validation', () async {
      final transport = FakeShutdownTransport()
        ..wrongGoal = mismatch == 'goal'
        ..wrongModel = mismatch == 'model';
      await expectLater(
        transport.api().generateAi(
          feature: AiFeature.question,
          goalId: transport.data['goalA'] as int,
          question: transport.data['gemini']['question'] as String,
          subject: '가상 운영체제',
          situation: '복습',
          confirmed: true,
        ),
        throwsA(isA<ApiFailure>()),
      );
      expect(transport.generated, 1);
    });
  }

  testWidgets('Fake-only screen and saved reply explicitly say not real AI', (
    t,
  ) async {
    final transport = FakeShutdownTransport();
    t.view.physicalSize = const Size(360, 900);
    t.view.devicePixelRatio = 1;
    addTearDown(t.view.resetPhysicalSize);
    addTearDown(t.view.resetDevicePixelRatio);
    await t.pumpWidget(
      MaterialApp(
        theme: AppDesign.theme(Brightness.dark),
        home: ServerAiRequest(
          api: transport.api(),
          goalId: transport.data['goalA'] as int,
          goalName: '가상 목표 A',
          feature: AiFeature.question,
        ),
      ),
    );
    await t.pumpAndSettle();
    expect(find.text('격리 Mock 테스트 · 실제 AI 아님'), findsOneWidget);
    expect(transport.generated, 0);
    await t.enterText(
      find.byKey(const Key('aiQuestion')),
      transport.data['gemini']['question'] as String,
    );
    await t.enterText(find.byKey(const Key('aiQuestionSubject')), '가상 운영체제');
    await t.enterText(find.byKey(const Key('aiQuestionSituation')), '복습');
    await t.scrollUntilVisible(
      find.byKey(const Key('sendAiRequest')),
      160,
      scrollable: find.byType(Scrollable).first,
    );
    await t.ensureVisible(find.byKey(const Key('sendAiRequest')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const Key('sendAiRequest')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const Key('confirmAiRequest')));
    await t.pumpAndSettle();
    await t.scrollUntilVisible(
      find.text('격리 Mock 응답 · 실제 AI 아님'),
      160,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('격리 Mock 응답 · 실제 AI 아님'), findsOneWidget);
    expect(find.text('서버가 보고한 제공업체 응답'), findsNothing);
    expect(transport.generated, 1);
    expect(t.takeException(), isNull);
  });
}
