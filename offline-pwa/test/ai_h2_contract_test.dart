import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/app_design.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/server_ai_request.dart';

// Captured from actual MockMvc controllers + memory H2, not a provider quality test.
class ContractTransport implements Transport {
  ContractTransport()
    : data = jsonDecode(
        File('test/fixtures/ai_h2_contract.json').readAsStringSync(),
      ) as Map<String, dynamic> {
    expect(data['syntheticOnly'], true);
  }
  final Map<String, dynamic> data;
  bool wrongGoal = false, wrongModel = false, oldServer = false;
  int generated = 0;
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    final goal = data['goalA'];
    Object? result;
    switch (uri.path) {
      case '/subjects':
        result = data['subjects'];
        break;
      case '/study-question/capabilities':
        result = {
          ...data['capabilities'] as Map,
          if (oldServer) 'goalScopedResponse': false,
        };
        break;
      case '/llm-config-check':
        result = data['dailyConfig'];
        break;
      case '/gpt-weekly-coaching/preview':
        result = data['weeklyPreview'];
        break;
      case '/auth/csrf':
        return Reply(
          200,
          '{"headerName":"X-CSRF-TOKEN","token":"synthetic-csrf"}',
        );
      case '/auth/me':
        return Reply(200, '{"authenticated":true,"userId":1}');
      case '/study-questions':
        result = [
          for (final p in ['gemini', 'gpt'])
            {
              'questionId': data[p]['questionId'],
              'userId': 1,
              'question': data[p]['question'],
            },
        ];
        break;
      case '/ai-responses':
        result = uri.queryParameters['goalId'] == '$goal'
            ? data['history${uri.queryParameters['feature']}']
            : [];
        break;
      case '/study-question':
      case '/llm-coaching':
      case '/gpt-weekly-coaching':
        expect(uri.queryParameters['goalId'], '$goal');
        expect(headers['X-CSRF-TOKEN'], 'synthetic-csrf');
        generated++;
        final feature = uri.path == '/llm-coaching'
            ? 'daily'
            : uri.path == '/gpt-weekly-coaching'
            ? 'weekly'
            : (jsonDecode(body!) as Map)['provider'] as String;
        result = {
          ...data[feature] as Map,
          if (wrongGoal) 'goalId': data['goalB'],
          if (wrongModel) 'responseModel': 'unrelated-model',
        };
        break;
      default:
        fail('Unexpected API route: ${uri.path}');
    }
    return Reply(200, jsonEncode(result));
  }

  @override
  void close() {}
  PlannerApi api() => PlannerApi(
    base: Uri.parse('http://localhost:8080'),
    transport: this,
    debugLoopback: true,
  );
}

void main() {
  for (final feature in ['gemini', 'gpt', 'daily', 'weekly']) {
    test(
      'actual H2 $feature contract accepted with explicit Mock provenance',
      () async {
        final transport = ContractTransport();
        final api = transport.api();
        final goal = transport.data['goalA'] as int;
        final type = feature == 'daily'
            ? AiFeature.daily
            : feature == 'weekly'
            ? AiFeature.weekly
            : AiFeature.question;
        final result = await api.generateAi(
          feature: type,
          goalId: goal,
          provider: feature == 'gpt' ? 'gpt' : 'gemini',
          question: '가상 프로세스 질문',
          confirmed: true,
        );
        expect(result['goalId'], goal);
        expect(result['responseSaved'], true);
        expect(isIsolatedAiMock(result), true);
        if (type == AiFeature.question) {
          expect(result['responseComplete'], isNull);
          expect(result['questionId'], isA<int>());
        }
        final history = await api.savedResponses(
          goalId: goal,
          feature: api.featureCode(type),
        );
        expect(history, isNotEmpty);
        expect(
          await api.savedResponses(
            goalId: transport.data['goalB'] as int,
            feature: api.featureCode(type),
          ),
          isEmpty,
        );
        expect(transport.generated, 1);
      },
    );
  }
  for (final mismatch in ['goal', 'model']) {
    test('Mock label cannot bypass $mismatch validation', () async {
      final transport = ContractTransport()
        ..wrongGoal = mismatch == 'goal'
        ..wrongModel = mismatch == 'model';
      final api = transport.api();
      await expectLater(
        api.generateAi(
          feature: AiFeature.question,
          goalId: transport.data['goalA'] as int,
          question: '가상 프로세스 질문',
          confirmed: true,
        ),
        throwsA(isA<AiGenerationFailure>()),
      );
      expect(transport.generated, 1);
      await expectLater(
        api.generateAi(
          feature: AiFeature.question,
          goalId: transport.data['goalA'] as int,
          question: '가상 프로세스 질문',
          confirmed: true,
        ),
        throwsA(isA<AiGenerationFailure>()),
      );
      expect(transport.generated, 1);
    });
  }
  test('Mock label cannot bypass missing goal support marker', () async {
    final transport = ContractTransport()..oldServer = true;
    await expectLater(
      transport.api().generateAi(
        feature: AiFeature.question,
        goalId: transport.data['goalA'] as int,
        question: '가상 프로세스 질문',
        confirmed: true,
      ),
      throwsA(isA<ApiFailure>()),
    );
    expect(transport.generated, 0);
  });
  testWidgets(
    'H2 generated and saved answers clearly show Mock rather than real AI',
    (t) async {
      final transport = ContractTransport();
      final api = transport.api();
      t.view.physicalSize = const Size(360, 900);
      t.view.devicePixelRatio = 1;
      addTearDown(t.view.resetPhysicalSize);
      addTearDown(t.view.resetDevicePixelRatio);
      await t.pumpWidget(
        MaterialApp(
          theme: AppDesign.theme(Brightness.dark),
          home: ServerAiRequest(
            api: api,
            goalId: transport.data['goalA'] as int,
            goalName: '가상 목표 A',
            feature: AiFeature.question,
          ),
        ),
      );
      await t.pumpAndSettle();
      expect(find.text('격리 Mock 테스트 · 실제 AI 아님'), findsOneWidget);
      expect(transport.generated, 0);
      await t.enterText(find.byKey(const Key('aiQuestion')), '가상 프로세스 질문');
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
      expect(find.text('서버가 보고한 제공업체 응답'), findsNothing);
      expect(find.text('격리 Mock 응답 · 실제 AI 아님'), findsOneWidget);
      await t.scrollUntilVisible(
        find.text(
          '저장 응답 #${transport.data['historyQUESTION'][0]['responseId']}',
        ),
        160,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('격리 Mock 저장 이력 · 실제 AI 아님'), findsWidgets);
      expect(transport.generated, 1);
      expect(t.takeException(), isNull);
    },
  );
}
