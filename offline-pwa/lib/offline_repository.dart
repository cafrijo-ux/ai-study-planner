import 'dart:convert';

import 'offline_store.dart';

import 'planner_api.dart';
import 'web_mock_transport.dart';

class OfflineRepository {
  OfflineRepository(this.store, {this.onChanged});
  final OfflineStore store;
  final void Function()? onChanged;
  OfflineIdentity identity = const OfflineIdentity('https://mock.invalid', 1);
  bool hasData = false;
  DateTime? lastSync;
  Future<void> initialize() async {
    await store.activate(identity);
    await read(0, 'goals', 'all');
  }

  Future<SnapshotRead> read(int goal, String kind, String period) async {
    final result = await store.read(OfflineKey(identity, goal, kind, period));
    if (result.state == 'corrupt') throw const OfflineStoreFailure('CORRUPT');
    if (result.snapshot != null) {
      lastSync = result.snapshot!.syncedAt;
      if (kind == 'goals') {
        hasData = (result.snapshot!.payload as List).isNotEmpty;
      }
    }
    onChanged?.call();
    return result;
  }

  Future<dynamic> require(int goal, String kind, String period) async {
    final result = await read(goal, kind, period);
    if (result.state == 'missing') {
      throw const ApiFailure(
        '내려받은 데이터 없음 · 가상 데이터 저장 또는 해당 월·날짜의 저장 범위를 확인하세요.',
      );
    }
    return result.snapshot!.payload;
  }

  Future<void> seedMock({required bool online}) async {
    if (!online) {
      throw const ApiFailure('오프라인에서는 새 가상 데이터를 준비할 수 없습니다. 저장된 데이터만 조회하세요.');
    }
    final mock = WebMockTransport(), now = DateTime.now().toUtc();
    final today = mock.today, month = today.substring(0, 7);
    final finalDate = '$month-${today.endsWith('-01') ? '02' : '01'}';
    final emptyDate = '$month-${today.endsWith('-03') ? '04' : '03'}';
    final draft = mock.plan(today);
    final finalPlan = {...mock.plan(finalDate), 'planStatus': 'FINAL'};
    Future<dynamic> response(String path) async {
      final r = await mock.send(
        'GET',
        Uri.parse('https://mock.invalid$path?goalId=1'),
        {},
        null,
      );
      return jsonDecode(r.body);
    }

    final goals = await response('/goals') as List;
    goals.add({'goalId': 2, 'goalName': '[Mock] 미다운로드 목표'});
    final subjects = await response('/subjects') as List;
    for (final row in subjects) {
      row['userId'] = identity.userId;
    }
    final logs = await response('/study-logs') as List;
    for (final row in logs) {
      row['userId'] = identity.userId;
    }
    StudySnapshot snap(int goal, String kind, String period, dynamic data) =>
        StudySnapshot(OfflineKey(identity, goal, kind, period), data, now);
    await store.writeBatch([
      snap(0, 'goals', 'all', goals),
      snap(1, 'subjects', 'all', subjects),
      snap(1, 'month', month, {
        'goalId': 1,
        'month': month,
        'plans': [finalPlan, draft],
      }),
      snap(1, 'day', today, draft),
      snap(1, 'day', finalDate, finalPlan),
      snap(1, 'day', emptyDate, {
        'saved': false,
        'goalId': 1,
        'planDate': emptyDate,
      }),
      snap(1, 'logs', today, logs),
      snap(1, 'logIndex', 'all', [today]),
      snap(1, 'summary', 'all', await response('/analysis-summary')),
    ]);
    hasData = true;
    lastSync = now;
    onChanged?.call();
  }

  Future<Map<String, dynamic>> plan(int goal, String date) async {
    final direct = await read(goal, 'day', date);
    if (direct.snapshot != null) {
      return Map<String, dynamic>.from(direct.snapshot!.payload as Map);
    }
    final month = await read(goal, 'month', date.substring(0, 7));
    if (month.snapshot == null) {
      throw const ApiFailure('내려받은 데이터 없음 · 선택 날짜는 미조회 상태입니다.');
    }
    for (final p in month.snapshot!.payload['plans'] as List) {
      if (p['planDate'] == date) return Map<String, dynamic>.from(p as Map);
    }
    return {'saved': false, 'goalId': goal, 'planDate': date};
  }

  Future<List<dynamic>> logs() async {
    final goals = await require(0, 'goals', 'all') as List;
    final result = <dynamic>[];
    for (final goal in goals) {
      final index = await read(goal['goalId'] as int, 'logIndex', 'all');
      if (index.snapshot == null) continue;
      for (final date in index.snapshot!.payload as List) {
        result.addAll(
          await require(goal['goalId'] as int, 'logs', date as String) as List,
        );
      }
    }
    return result;
  }

  Future<void> clear() async {
    await store.clear(identity);
    hasData = false;
    lastSync = null;
    onChanged?.call();
  }

  Future<void> switchMockUser() async {
    await clear();
    identity = OfflineIdentity(
      'https://mock.invalid',
      identity.userId == 1 ? 2 : 1,
    );
    await store.activate(identity);
    hasData = false;
    lastSync = null;
    onChanged?.call();
  }
}

class OfflineMockTransport implements Transport, MockDataTransport {
  OfflineMockTransport(this.repository);
  final OfflineRepository repository;
  final fallback = WebMockTransport();
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    try {
      final goal = int.tryParse(uri.queryParameters['goalId'] ?? '') ?? 1;
      dynamic payload;
      switch (uri.path) {
        case '/auth/logout':
          await repository.clear();
          return await fallback.send(method, uri, headers, body);
        case '/auth/me':
          payload = {
            'authenticated': true,
            'userId': repository.identity.userId,
          };
        case '/goals':
          payload =
              (await repository.read(0, 'goals', 'all')).snapshot?.payload ??
              [];
        case '/subjects':
          payload = await repository.require(goal, 'subjects', 'all');
        case '/study-reschedule-calendar':
          payload = await repository.require(
            goal,
            'month',
            uri.queryParameters['month']!,
          );
        case '/study-reschedule-saved':
          payload = await repository.plan(goal, uri.queryParameters['date']!);
        case '/study-logs':
          payload = await repository.logs();
        case '/analysis-summary':
          payload = await repository.require(goal, 'summary', 'all');
        default:
          return await fallback.send(method, uri, headers, body);
      }
      if (method != 'GET') throw const ApiFailure('오프라인 저장본은 조회 전용입니다.');
      return Reply(200, jsonEncode(payload));
    } on OfflineStoreFailure catch (e) {
      final label = switch (e.code) {
        'QUOTA' => '저장 공간 부족',
        'CORRUPT' => '로컬 데이터 손상',
        'IDENTITY_CHANGED' => '사용자 변경됨',
        _ => '로컬 저장소 사용 불가',
      };
      throw ApiFailure('$label · 실제 서버 요청 없이 작업을 중단했습니다.');
    }
  }

  @override
  void close() {}
}
