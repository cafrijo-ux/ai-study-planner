import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/app_design.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/server_ai_history.dart';

class HistoryOnlyTransport implements Transport {
  final calls = <(String, Uri)>[];
  int status = 200;
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    calls.add((method, uri));
    if (status != 200) return Reply(status, '{}');
    final rows = uri.queryParameters['feature'] == 'QUESTION'
        ? [
            for (final provider in ['gemini', 'gpt'])
              {
                'responseId': provider == 'gemini' ? 4 : 5,
                'questionId': provider == 'gemini' ? 8 : 9,
                'userId': 1,
                'goalId': int.parse(uri.queryParameters['goalId']!),
                'feature': 'QUESTION',
                'requestedProvider': provider,
                'actualProvider': provider == 'gpt' ? 'openai' : 'gemini',
                'model': provider == 'gpt'
                    ? 'gpt-4o-mini-2024-07-18'
                    : 'gemini-3.5-flash-lite',
                'responseSource': 'PROVIDER',
                'responseComplete': true,
                'createdAt': provider == 'gpt'
                    ? '2026-11-03T19:07:00'
                    : '2026-11-03T19:06:00',
                'responseBody': '[합성] $provider 저장 답변',
              },
          ]
        : [];
    return Reply(200, jsonEncode(rows));
  }

  @override
  void close() {}
}

void main() {
  for (final dark in [false, true]) {
    for (final scale in [1.0, 2.0]) {
      testWidgets(
        'saved model filter keeps GET only at 320dp scale $scale dark $dark',
        (t) async {
          t.view.physicalSize = const Size(320, 850);
          t.view.devicePixelRatio = 1;
          addTearDown(t.view.resetPhysicalSize);
          addTearDown(t.view.resetDevicePixelRatio);
          final transport = HistoryOnlyTransport();
          final api = PlannerApi(
            base: Uri.parse('http://localhost:8080'),
            transport: transport,
            debugLoopback: true,
          );
          addTearDown(api.close);
          await t.pumpWidget(
            MaterialApp(
              theme: AppDesign.theme(dark ? Brightness.dark : Brightness.light),
              builder: (context, child) => MediaQuery(
                data: MediaQuery.of(context)
                    .copyWith(textScaler: TextScaler.linear(scale)),
                child: child!,
              ),
              home: ServerAiHistory(api: api, goalId: 1, goalName: '운영체제 목표'),
            ),
          );
          await t.pumpAndSettle();
          final scroll = find
              .descendant(
                of: find.byKey(const Key('aiHistoryScroll')),
                matching: find.byType(Scrollable),
              )
              .first;
          final questionChip = find.ancestor(
            of: find.text('질문'),
            matching: find.byType(ChoiceChip),
          );
          await t.scrollUntilVisible(questionChip, 240, scrollable: scroll);
          await t.pumpAndSettle();
          await t.tap(questionChip);
          await t.pumpAndSettle();
          await t.scrollUntilVisible(
            find.byKey(const ValueKey('savedAnswer_5')),
            240,
            scrollable: scroll,
          );
          expect(find.text('openai · gpt-4o-mini-2024-07-18'), findsOneWidget);
          final requests = transport.calls.length;
          await t.scrollUntilVisible(
            find.byKey(const Key('savedModel_gemini')),
            -240,
            scrollable: scroll,
          );
          await t.tap(find.byKey(const Key('savedModel_gemini')));
          await t.pumpAndSettle();
          await t.scrollUntilVisible(
            find.byKey(const ValueKey('savedAnswer_4')),
            240,
            scrollable: scroll,
          );
          expect(find.text('[합성] gemini 저장 답변'), findsOneWidget);
          expect(find.byKey(const ValueKey('savedAnswer_5')), findsNothing);
          expect(transport.calls.length, requests);
          expect(
            transport.calls.every(
              (c) => c.$1 == 'GET' && c.$2.path == '/ai-responses',
            ),
            isTrue,
          );
          expect(t.takeException(), isNull);
        },
      );
    }
  }
  for (final status in [401, 403]) {
    testWidgets(
      'saved history $status clears previous data and expires the session',
      (t) async {
        final transport = HistoryOnlyTransport();
        final api = PlannerApi(
          base: Uri.parse('http://localhost:8080'),
          transport: transport,
          debugLoopback: true,
        );
        addTearDown(api.close);
        var expired = 0;
        await t.pumpWidget(
          MaterialApp(
            home: ServerAiHistory(
              api: api,
              goalId: 1,
              goalName: '목표 A',
              onSessionExpired: () => expired++,
            ),
          ),
        );
        await t.pumpAndSettle();
        transport.status = status;
        await t.ensureVisible(find.byTooltip('저장된 답변 새로고침 (GET)'));
        await t.tap(find.byTooltip('저장된 답변 새로고침 (GET)'));
        await t.pumpAndSettle();
        expect(expired, 1);
        expect(transport.calls.every((c) => c.$1 == 'GET'), isTrue);
        expect(t.takeException(), isNull);
      },
    );
  }
}
