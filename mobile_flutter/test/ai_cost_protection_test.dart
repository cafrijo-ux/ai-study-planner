import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/server_ai_request.dart';

import 'ai_generation_test.dart' show AiMock;

class CostMock extends AiMock {
  bool protection = true, configured = true;
  String state = 'COMPLETED';
  int lookups = 0;
  final ledger = <String, Map<String, dynamic>>{};
  Map<String, dynamic> get policy => {
    'enabled': protection,
    'version': 1,
    'features': {
      'DAILY_COACHING': {
        'configured': configured,
        'approvedModel': 'gemini-test',
        'maxAttempts': 2,
        'maxOutputTokens': 100,
      },
    },
  };
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    if (uri.path == '/ai-generation-policy') {
      return Reply(200, jsonEncode(policy));
    }
    if (uri.path == '/ai-generation-requests') {
      lookups++;
      final k = uri.queryParameters['requestKey']!;
      final r = ledger[k];
      return Reply(
        200,
        jsonEncode({
          'enabled': true,
          'feature': 'DAILY_COACHING',
          'goalId': int.parse(uri.queryParameters['goalId']!),
          'requestKey': k,
          'state': r == null ? 'NOT_CONFIRMED' : state,
          'result': ?r,
        }),
      );
    }
    if (uri.path == '/llm-config-check') {
      final r = await super.send(method, uri, headers, body);
      return Reply(
        r.status,
        jsonEncode({
          ...jsonDecode(r.body) as Map<String, dynamic>,
          'aiCostProtection': policy,
        }),
      );
    }
    if (uri.path == '/llm-coaching') {
      final lost = lose;
      lose = false;
      try {
        final r = await super.send(method, uri, headers, body);
        if (r.status != 200) return r;
        final data = jsonDecode(r.body) as Map<String, dynamic>;
        if (protection) {
          final key = headers['Idempotency-Key']!;
          data['costProtection'] = {
            'enabled': true,
            'requestKey': key,
            'state': 'COMPLETED',
            'dispatchReserved': true,
            'resultStored': true,
          };
          ledger[key] = data;
        }
        if (lost) throw const ApiFailure('합성 응답 유실');
        return Reply(200, jsonEncode(data));
      } finally {
        lose = lost;
      }
    }
    return super.send(method, uri, headers, body);
  }
}

PlannerApi api(CostMock m, StudyRequestStore store) => PlannerApi(
  base: Uri.parse('http://localhost:8080'),
  transport: m,
  requestStore: store,
);
Future<Map<String, dynamic>> generate(PlannerApi a, int goal) =>
    a.generateAi(feature: AiFeature.daily, goalId: goal, confirmed: true);
void main() {
  test(
    'approved protection uses one explicit POST, exact model, key and CSRF',
    () async {
      final m = CostMock(), s = MemoryStudyRequestStore();
      final r = await generate(api(m, s), 1);
      final c = m.calls.where((c) => c.$2.path == '/llm-coaching').single;
      expect(c.$1, 'POST');
      expect(jsonDecode(c.$4!), {
        'confirmPaidCall': true,
        'expectedModel': 'gemini-test',
      });
      expect(c.$3['Idempotency-Key'], isNotEmpty);
      expect(c.$3['X-CSRF-TOKEN'], 'fake-csrf');
      expect(m.generated, 1);
      expect(r['responseSaved'], true);
      expect(s.entries, isEmpty);
    },
  );
  test('OFF keeps legacy generating GET with CSRF; never claims persistent protection', () async {
    final m = CostMock()..protection = false;
    final r = await generate(api(m, MemoryStudyRequestStore()), 1);
    final c = m.calls.where((c) => c.$2.path == '/llm-coaching').single;
    expect(c.$1, 'GET');
    expect(c.$3['X-CSRF-TOKEN'], 'fake-csrf');
    expect(r.containsKey('costProtection'), false);
    expect(m.generated, 1);
  });
  test(
    'lost response and API reconstruction never resend; explicit GET recovers',
    () async {
      final m = CostMock()..lose = true;
      final s = MemoryStudyRequestStore();
      await expectLater(
        generate(api(m, s), 1),
        throwsA(isA<AiGenerationFailure>()),
      );
      final pending = Map.of(s.entries);
      final fresh = api(m, s);
      await expectLater(
        generate(fresh, 1),
        throwsA(isA<AiGenerationFailure>()),
      );
      expect(m.generated, 1);
      expect(s.entries, pending);
      expect(s.entries.toString(), isNot(contains('fake-session')));
      expect(s.entries.toString(), isNot(contains('llmResponse')));
      final r = await fresh.resolveDailyAi(1);
      expect(r!['_text'], isNotEmpty);
      expect(s.entries, isEmpty);
      expect(m.generated, 1);
      expect(m.lookups, 1);
    },
  );
  test('unknown and missing ledger result preserve journal without concluding no charge', () async {
    final m = CostMock()..lose = true;
    final s = MemoryStudyRequestStore();
    final a = api(m, s);
    await expectLater(generate(a, 1), throwsA(isA<AiGenerationFailure>()));
    m.state = 'UNKNOWN';
    expect(await a.resolveDailyAi(1), isNull);
    expect(s.entries, isNotEmpty);
    m.ledger.clear();
    expect(await a.resolveDailyAi(1), isNull);
    expect(m.generated, 1);
  });
  test('unapproved policy and cancelled cost confirmation make no generation/reservation', () async {
    final m = CostMock()..configured = false;
    final s = MemoryStudyRequestStore();
    await expectLater(generate(api(m, s), 1), throwsA(isA<ApiFailure>()));
    m.configured = true;
    await expectLater(
      api(
        m,
        s,
      ).generateAi(feature: AiFeature.daily, goalId: 1, confirmed: false),
      throwsA(isA<ApiFailure>()),
    );
    expect(m.generated, 0);
    expect(s.entries, isEmpty);
  });
  test(
    'concurrent taps dispatch once and goal B cannot clear goal A pending key',
    () async {
      final m = CostMock()..hold = Completer<void>();
      final s = MemoryStudyRequestStore();
      final a = api(m, s);
      final first = generate(a, 1);
      while (m.generated == 0) {
        await Future<void>.delayed(const Duration(milliseconds: 1));
      }
      await expectLater(generate(a, 1), throwsA(isA<ApiFailure>()));
      m.hold!.complete();
      await first;
      expect(m.generated, 1);
      m.hold = null;
      m.lose = true;
      await expectLater(generate(a, 1), throwsA(isA<ApiFailure>()));
      final one = Map.of(s.entries);
      m.lose = false;
      await generate(a, 3);
      expect(s.entries, one);
      expect(m.generated, 3);
    },
  );
  test('provider answer versus AiResponse save failure remains explicit; OFF uncertainty persists', () async {
    final m = CostMock()..saveFail = true;
    final s = MemoryStudyRequestStore();
    final r = await generate(api(m, s), 1);
    expect(r['responseSaved'], false);
    expect(r['costProtection']['resultStored'], true);
    expect(s.entries, isEmpty);
    m.protection = false;
    await generate(api(m, s), 1);
    expect(s.entries, isNotEmpty);
    final fresh = api(m, s);
    await expectLater(generate(fresh, 1), throwsA(isA<AiGenerationFailure>()));
    await expectLater(fresh.resolveDailyAi(1), throwsA(isA<ApiFailure>()));
    expect(m.generated, 2);
  });
  test(
    'recovery rejects another goal/provider/model without releasing key',
    () async {
      final m = CostMock()..lose = true;
      final s = MemoryStudyRequestStore();
      final a = api(m, s);
      await expectLater(generate(a, 1), throwsA(isA<ApiFailure>()));
      m.ledger.values.single['responseModel'] = 'gpt-4o-mini';
      await expectLater(a.resolveDailyAi(1), throwsA(isA<ApiFailure>()));
      expect(s.entries, isNotEmpty);
      expect(m.generated, 1);
    },
  );
  testWidgets(
    'reenter daily UI restores pending lock and GET button without auto generation',
    (t) async {
      final m = CostMock()..lose = true;
      final s = MemoryStudyRequestStore();
      await expectLater(generate(api(m, s), 1), throwsA(isA<ApiFailure>()));
      await t.pumpWidget(
        MaterialApp(
          home: ServerAiRequest(
            api: api(m, s),
            goalId: 1,
            goalName: 'A',
            feature: AiFeature.daily,
          ),
        ),
      );
      await t.pumpAndSettle();
      await t.scrollUntilVisible(
        find.byKey(const Key('resolveDailyAi')),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(
        t
            .widget<FilledButton>(find.byKey(const Key('sendAiRequest')))
            .onPressed,
        isNull,
      );
      await t.tap(find.byKey(const Key('resolveDailyAi')));
      await t.pumpAndSettle();
      expect(m.lookups, 1);
      expect(m.generated, 1);
      expect(s.entries, isEmpty);
    },
  );
}
