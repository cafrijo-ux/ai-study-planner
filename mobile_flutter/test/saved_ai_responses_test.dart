import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';

class _FakeTransport implements Transport {
  _FakeTransport(this.payload);
  final Object payload;
  final List<String> methods = [];
  final List<Uri> uris = [];

  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    methods.add(method);
    uris.add(uri);
    return Reply(200, jsonEncode(payload));
  }

  @override
  void close() {}
}

Map<String, dynamic> _sample({int goalId = 1, String feature = 'DAILY_COACHING'}) => {
  'responseId': 1,
  'goalId': goalId,
  'feature': feature,
  'actualProvider': 'gemini',
  'responseSource': 'PROVIDER',
  'responseComplete': true,
  'responseBody': '이미 저장된 답변',
};

void main() {
  test('Saved AI history only sends the scoped GET request', () async {
    final transport = _FakeTransport([_sample()]);
    final api = PlannerApi(
      base: Uri.parse('http://localhost:8080'),
      transport: transport,
      debugLoopback: true,
    );
    final results = await api.savedResponses(goalId: 1, feature: 'DAILY_COACHING');
    expect(results.single['responseBody'], '이미 저장된 답변');
    expect(transport.methods, ['GET']);
    expect(transport.uris.single.path, '/ai-responses');
    expect(transport.uris.single.queryParameters, {
      'goalId': '1',
      'feature': 'DAILY_COACHING',
    });
    api.close();
  });

  test('Saved AI history refuses a reply from a different goal', () async {
    final transport = _FakeTransport([_sample(goalId: 3)]);
    final api = PlannerApi(
      base: Uri.parse('http://localhost:8080'),
      transport: transport,
      debugLoopback: true,
    );
    await expectLater(
      api.savedResponses(goalId: 1, feature: 'DAILY_COACHING'),
      throwsA(isA<ApiFailure>()),
    );
    expect(transport.methods, ['GET']);
    api.close();
  });

  test('Unsupported history types are blocked without network access', () async {
    final transport = _FakeTransport([]);
    final api = PlannerApi(
      base: Uri.parse('http://localhost:8080'),
      transport: transport,
      debugLoopback: true,
    );
    await expectLater(
      api.savedResponses(goalId: 1, feature: 'GENERATE'),
      throwsA(isA<ApiFailure>()),
    );
    expect(transport.methods, isEmpty);
    api.close();
  });
}
