import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';

class _Transport implements Transport {
  _Transport(this.answer);
  final Object answer;
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
    return Reply(200, jsonEncode(answer));
  }

  @override
  void close() {}
}

PlannerApi _api(_Transport transport) => PlannerApi(
  base: Uri.parse('http://localhost:8080'),
  transport: transport,
  debugLoopback: true,
);

void main() {
  test('Read saved FINAL plan with date and goal only by GET', () async {
    final transport = _Transport({
      'saved': true,
      'planStatus': 'FINAL',
      'goalId': 1,
      'scheduleDate': '2026-10-20',
      'studyMinutes': 100,
      'carryoverMinutes': 9,
      'newStudyMinutes': 91,
    });
    final api = _api(transport);
    final plan = await api.storedPlan(goalId: 1, date: '2026-10-20');
    expect(plan['newStudyMinutes'], 91);
    expect(transport.methods, ['GET']);
    expect(transport.uris.single.path, '/study-reschedule-saved');
    expect(transport.uris.single.queryParameters, {
      'date': '2026-10-20',
      'goalId': '1',
    });
    api.close();
  });

  test(
    'Explicit selected goal with no stored plan has an empty result',
    () async {
      final transport = _Transport({'saved': false, 'message': '저장 없음'});
      final api = _api(transport);
      final plan = await api.storedPlan(goalId: 1, date: '2026-10-25');
      expect(plan['saved'], false);
      expect(transport.methods, ['GET']);
      api.close();
    },
  );

  test('Wrong goal plan is rejected to avoid cross-goal mix', () async {
    final transport = _Transport({
      'saved': true,
      'goalId': 3,
      'scheduleDate': '2026-10-20',
      'studyMinutes': 100,
      'carryoverMinutes': 9,
      'newStudyMinutes': 91,
    });
    final api = _api(transport);
    await expectLater(
      api.storedPlan(goalId: 1, date: '2026-10-20'),
      throwsA(isA<ApiFailure>()),
    );
    api.close();
  });

  test('Calculated plan is GET and may be unsaved', () async {
    final transport = _Transport({
      'goalId': 1,
      'scheduleDate': '2026-10-25',
      'isScheduleValid': true,
      'hasConflict': false,
      'studyMinutes': 100,
      'carryoverMinutes': 9,
      'newStudyMinutes': 91,
    });
    final api = _api(transport);
    final plan = await api.calculatedPlan(goalId: 1, date: '2026-10-25');
    expect(plan['studyMinutes'], 100);
    expect(transport.methods, ['GET']);
    expect(transport.uris.single.path, '/study-plan');
    expect(transport.uris.single.queryParameters, {
      'date': '2026-10-25',
      'goalId': '1',
    });
    api.close();
  });

  test('Date must be valid; no network request for invalid date', () async {
    final transport = _Transport({});
    final api = _api(transport);
    await expectLater(
      api.storedPlan(goalId: 1, date: '2026-02-31'),
      throwsA(isA<ApiFailure>()),
    );
    expect(transport.methods, isEmpty);
    api.close();
  });
}
