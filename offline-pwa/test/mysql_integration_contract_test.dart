import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';

import 'study_record_entry_test.dart' show SaveTransport, save;

void main() {
  const run = '41224bf6-1296-4097-8451-9d72169f9953';
  test(
    'normal study POST includes selected goal and no test headers',
    () async {
      final t = SaveTransport();
      final api = PlannerApi(
        base: Uri.parse('http://localhost:8080'),
        transport: t,
      );
      await save(api);
      final call = t.calls.singleWhere((c) => c.$1 == 'POST');
      expect(call.$2.queryParameters['goalId'], '1');
      expect(call.$3.containsKey('X-Integration-Run'), false);
      expect(call.$3['X-CSRF-TOKEN'], 'mock-token');
    },
  );
  test('explicit integration mode adds run and one request key; loss never retries', () async {
    final t = SaveTransport()..loseReplyAfterSave = true;
    final api = PlannerApi(
      base: Uri.parse('http://localhost:8080'),
      transport: t,
      integrationRunId: run,
    );
    await expectLater(save(api), throwsA(isA<StudySaveFailure>()));
    expect(t.posts, 1);
    final call = t.calls.singleWhere((c) => c.$1 == 'POST');
    expect(call.$3['X-Integration-Run'], run);
    expect(call.$3['Idempotency-Key'], matches(r'^mobile_[a-f0-9]{32}$'));
    expect(call.$2.queryParameters['goalId'], '1');
  });
  test(
    'integration scope cannot be applied to arbitrary host or malformed ID',
    () {
      for (final url in [
        'https://example.invalid',
        'http://192.168.1.2:8104',
      ]) {
        expect(
          () => PlannerApi(
            base: Uri.parse(url),
            transport: SaveTransport(),
            integrationRunId: run,
            allowDebugLanHttp: true,
          ),
          throwsA(isA<ApiFailure>()),
        );
      }
      expect(
        () => PlannerApi(
          base: Uri.parse('http://localhost:8080'),
          transport: SaveTransport(),
          integrationRunId: 'old-approval',
        ),
        throwsA(isA<ApiFailure>()),
      );
    },
  );
}
