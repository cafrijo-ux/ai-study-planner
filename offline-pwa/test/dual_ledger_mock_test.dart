import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';

void main() {
  const marker = {
    'testMode': 'AI_MOCK_DUAL_LEDGER_ONLY',
    'storageMode': 'MYSQL_DUAL_LEDGER_FAKEWIRE',
    'mockResponse': true,
    'realAiCalls': 0,
  };
  test('dual ledger provenance is explicitly Mock without claiming H2', () {
    expect(isIsolatedAiMock(marker), isTrue);
    expect(marker['testMode'], isNot(contains('H2')));
  });
  test('dual marker requires exact dedicated mode and zero real calls', () {
    for (final change in [
      {'storageMode': 'production'},
      {'realAiCalls': 1},
      {'mockResponse': false},
      {'testMode': 'PROVIDER'},
    ]) {
      expect(isIsolatedAiMock({...marker, ...change}), isFalse);
    }
  });
  test('old H2 Mock contract remains unchanged', () {
    expect(isIsolatedAiMock({'testMode': 'AI_MOCK_H2_ONLY', 'mockResponse': true}),
        isTrue);
    expect(isIsolatedAiMock({'responseSource': 'provider'}), isFalse);
  });
}
