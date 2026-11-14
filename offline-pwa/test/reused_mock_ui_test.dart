import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/app_design.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/server_ai_request.dart';
import 'package:ai_study_planner_mobile/server_screens.dart';
import 'package:ai_study_planner_mobile/web_mock_transport.dart';

void main() {
  for (final size in [
    const Size(768, 1024),
    const Size(1180, 820),
    const Size(375, 812),
  ]) {
    testWidgets('shared Mock real-screen flow at $size uses no network', (
      t,
    ) async {
      t.view.physicalSize = size;
      t.view.devicePixelRatio = 1;
      addTearDown(t.view.resetPhysicalSize);
      addTearDown(t.view.resetDevicePixelRatio);
      final api = createWebMockApi();
      final transport = api.transport as WebMockTransport;
      addTearDown(api.close);
      await t.pumpWidget(
        MaterialApp(
          theme: AppDesign.theme(Brightness.light),
          home: ServerDashboard(api: api),
        ),
      );
      await t.pumpAndSettle();
      await t.tap(find.byType(DropdownButtonFormField<int>));
      await t.pumpAndSettle();
      await t.tap(find.text('[Mock] 운영체제 학습').last);
      await t.pumpAndSettle();
      for (final label in ['학습 계획', '학습 분석', 'AI 코칭']) {
        await t.tap(
          find.descendant(
            of: find.byType(NavigationBar),
            matching: find.text(label),
          ),
        );
        await t.pumpAndSettle();
        expect(t.takeException(), isNull);
        if (label == '학습 계획') {
          expect(find.byKey(const Key('planMonthCalendar')), findsOneWidget);
          expect(find.textContaining('달력 계획이 다릅니다'), findsNothing);
        }
      }
      await t.tap(find.text('질문·코칭 생성'));
      await t.pumpAndSettle();
      for (final feature in AiFeature.values) {
        final button = find.byKey(Key('openAi_${feature.name}'));
        await t.ensureVisible(button);
        await t.tap(button);
        await t.pumpAndSettle();
        expect(find.byType(ServerAiRequest), findsOneWidget);
        if (feature == AiFeature.question) {
          expect(find.byKey(const Key('aiQuestion')), findsOneWidget);
        }
        await t.scrollUntilVisible(
          find.byKey(const Key('sendAiRequest')),
          200,
          scrollable: find
              .descendant(
                of: find.byType(ServerAiRequest),
                matching: find.byType(Scrollable),
              )
              .first,
        );
        final send = t.widget<FilledButton>(
          find.byKey(const Key('sendAiRequest')),
        );
        expect(send.onPressed, isNull);
        expect(t.takeException(), isNull);
        Navigator.of(t.element(find.byType(ServerAiRequest))).pop();
        await t.pumpAndSettle();
      }
      expect(transport.calls.every((c) => c.$1 == 'GET'), isTrue);
      expect(transport.calls.any((c) => c.$2 == '/study-logs'), isTrue);
      expect(
        transport.calls.any((c) => c.$2 == '/study-reschedule-calendar'),
        isTrue,
      );
    });
  }
}
