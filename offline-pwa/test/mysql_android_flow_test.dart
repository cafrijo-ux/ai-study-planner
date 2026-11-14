import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';

const run = '41224bf6-1296-4097-8451-9d72169f9953';
const studyDay = '2026-10-29', planDay = '2026-10-31';

class AndroidMysqlMock implements Transport {
  final calls = <(String, Uri, Map<String, String>, String?)>[];
  bool authenticated = false, losePlanReply = false;
  Map<String, dynamic>? log, saved;
  Map<String, dynamic> plan(int goal) => {
    'goalId': goal,
    'scheduleDate': planDay,
    'studyMinutes': 200,
    'carryoverMinutes': 9,
    'newStudyMinutes': 191,
    'carryoverTotalPendingMinutes': 60,
    'previewMode': true,
    'isScheduleValid': true,
    'hasConflict': false,
    'subjectPlans': [],
    'timeSlots': [],
  };
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    calls.add((method, uri, {...headers}, body));
    Object? data;
    final goal = int.tryParse(uri.queryParameters['goalId'] ?? '');
    if (uri.path == '/auth/csrf') {
      return Reply(
        200,
        jsonEncode({'headerName': 'X-CSRF-TOKEN', 'token': 'synthetic-token'}),
      );
    }
    if (uri.path == '/auth/login') {
      authenticated = true;
      return Reply(200, '{}', [
        Cookie('JSESSIONID', 'synthetic-session')..path = '/',
      ]);
    }
    if (uri.path == '/auth/logout') {
      authenticated = false;
      return Reply(200, '{}');
    }
    if (!authenticated) return Reply(401, '{}');
    if (method == 'POST') {
      expect(headers['X-CSRF-TOKEN'], 'synthetic-token');
      expect(headers['X-Integration-Run'], run);
      expect(headers['Idempotency-Key'], matches(r'^mobile_[a-f0-9]{32}$'));
    }
    switch (uri.path) {
      case '/auth/me':
        data = {'authenticated': true, 'userId': 1};
      case '/goals':
        data = [
          {'goalId': 2, 'goalName': '가상 목표 A'},
          {'goalId': 3, 'goalName': '가상 목표 B'},
        ];
      case '/subjects':
        data = [
          {
            'subjectId': goal == 2 ? 7 : 9,
            'goalId': goal,
            'userId': 1,
            'subjectName': goal == 2 ? '가상 운영체제' : '가상 자료구조',
          },
        ];
      case '/study-logs':
        if (method == 'POST') {
          if (log != null) return Reply(409, '{}');
          log = {...jsonDecode(body!) as Map<String, dynamic>, 'logId': 5};
          data = log;
        } else {
          data = log == null ? [] : [log];
        }
      case '/analysis-summary':
        data = {
          'goalId': goal,
          'scope': 'CURRENT_GOAL',
          'recordCount': goal == 2 && log != null ? 1 : 0,
          'totalStudyMinutes': goal == 2 && log != null ? 40 : 0,
        };
      case '/study-reschedule-preview':
        data = plan(goal!);
      case '/study-reschedule-save':
        expect(goal, 2);
        expect(body, '');
        saved = {
          ...plan(2),
          'saved': true,
          'planDate': planDay,
          'planStatus': 'FINAL',
        };
        if (losePlanReply) return Reply(500, '{}');
        data = saved;
      case '/study-reschedule-saved':
        data = goal == 2 && saved != null ? saved : {'saved': false};
      default:
        throw StateError('Unexpected mock path ${uri.path}');
    }
    return Reply(200, jsonEncode(data));
  }

  @override
  void close() {}
}

PlannerApi api(AndroidMysqlMock t) => PlannerApi(
  base: Uri.parse('http://localhost:8080'),
  transport: t,
  integrationRunId: run,
);
Future<void> storeLog(PlannerApi p) => p
    .saveStudyRecord(
      goalId: 2,
      subjectId: 7,
      date: studyDay,
      minutes: 40,
      understanding: 3,
      focus: 3,
      fatigue: 2,
      content: '가상 복습',
    )
    .then((_) {});
void main() {
  test('Android contract: login A/B,40 minutes,200/9/191 FINAL and GET after re-login', () async {
    final t = AndroidMysqlMock();
    final client = api(t);
    await client.login('integration-owner', 'synthetic-login');
    expect((await client.goals()).length, 2);
    await storeLog(client);
    expect((await client.studyRecords(2)).single['studyMinutes'], 40);
    expect(await client.studyRecords(3), isEmpty);
    expect((await client.summary(2))['totalStudyMinutes'], 40);
    expect((await client.summary(3))['totalStudyMinutes'], 0);
    final preview = await client.reschedulePreview(2);
    expect(preview['carryoverTotalPendingMinutes'], 60);
    expect(preview['newStudyMinutes'], 191);
    await client.saveReschedule(goalId: 2, date: planDay);
    expect(
      (await client.storedPlan(goalId: 2, date: planDay))['planStatus'],
      'FINAL',
    );
    await client.logout();
    await client.login('integration-owner', 'synthetic-login');
    expect((await client.studyRecords(2)).length, 1);
    expect(
      (await client.storedPlan(goalId: 2, date: planDay))['studyMinutes'],
      200,
    );
    expect(t.log!['fatigueScore'], 2);
    expect(t.log!.containsKey('completed'), false);
  });
  test('each logical save has a distinct key; login/logout have no integration header', () async {
    final t = AndroidMysqlMock();
    final p = api(t);
    await p.login('integration-owner', 'synthetic-login');
    await storeLog(p);
    await p.saveReschedule(goalId: 2, date: planDay);
    await p.logout();
    final writes = t.calls
        .where((c) => c.$1 == 'POST' && !c.$2.path.startsWith('/auth/'))
        .toList();
    expect(writes.length, 2);
    expect(
      writes[0].$3['Idempotency-Key'],
      isNot(writes[1].$3['Idempotency-Key']),
    );
    for (final c in t.calls.where((c) => c.$2.path.startsWith('/auth/'))) {
      expect(c.$3.containsKey('X-Integration-Run'), false);
    }
  });
  test('lost plan response never retries; new client re-login reads saved snapshot', () async {
    final t = AndroidMysqlMock()..losePlanReply = true;
    final p = api(t);
    await p.login('integration-owner', 'synthetic-login');
    await storeLog(p);
    await expectLater(
      p.saveReschedule(goalId: 2, date: planDay),
      throwsA(isA<RescheduleSaveFailure>()),
    );
    await expectLater(
      p.saveReschedule(goalId: 2, date: planDay),
      throwsA(isA<RescheduleSaveFailure>()),
    );
    final next = api(t);
    await next.login('integration-owner', 'synthetic-login');
    expect((await next.storedPlan(goalId: 2, date: planDay))['saved'], true);
    expect(
      t.calls
          .where((c) => c.$1 == 'POST' && c.$2.path == '/study-reschedule-save')
          .length,
      1,
    );
  });
}
