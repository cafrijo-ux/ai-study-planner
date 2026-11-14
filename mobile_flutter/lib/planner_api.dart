import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
part 'planner_ai.dart';
part 'study_request_store.dart';

class ApiFailure implements Exception {
  const ApiFailure(this.message, [this.status]);
  final String message;
  final int? status;
  @override
  String toString() => message;
}

class Reply {
  Reply(this.status, this.body, [this.cookies = const []]);
  final int status;
  final String body;
  final List<Cookie> cookies;
}

class StudySaveFailure extends ApiFailure {
  const StudySaveFailure(super.message, super.status, {this.uncertain = false});
  final bool uncertain;
}

class RescheduleSaveFailure extends ApiFailure {
  const RescheduleSaveFailure(
    super.message,
    super.status, {
    this.uncertain = false,
  });
  final bool uncertain;
}

abstract class Transport {
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  );
  void close();
}

class IoTransport implements Transport {
  final HttpClient _client = HttpClient()
    ..connectionTimeout = const Duration(seconds: 10);
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) => _send(method, uri, headers, body, const Duration(seconds: 15));

  Future<Reply> _send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
    Duration deadline,
  ) async {
    try {
      return await (() async {
        final req = await _client.openUrl(method, uri);
        req.followRedirects = false;
        headers.forEach(req.headers.set);
        if (body != null) req.write(body);
        final res = await req.close();
        final content = await utf8.decodeStream(res);
        return Reply(res.statusCode, content, res.cookies);
      })().timeout(deadline);
    } catch (_) {
      throw const ApiFailure(
        '서버에 연결하지 못했습니다. 설정한 주소·포트와 서버 실행 상태를 확인하세요. USB는 adb reverse, Wi-Fi는 같은 네트워크·PC 주소·승인된 방화벽 규칙을 확인하세요. 자동 재시도하지 않습니다.',
      );
    }
  }

  @override
  void close() => _client.close(force: true);
}

class PlannerApi {
  PlannerApi({
    required this.base,
    required this.transport,
    this.debugLoopback = kDebugMode,
    this.allowDebugLanHttp = false,
    StudyRequestStore? requestStore,
    this.integrationRunId = const String.fromEnvironment(
      'MYSQL_INTEGRATION_RUN_ID',
    ),
  }) : requestStore = requestStore ?? MemoryStudyRequestStore() {
    if (integrationRunId.isNotEmpty &&
        (!kDebugMode ||
            base.scheme != 'http' ||
            base.host != 'localhost' ||
            base.port != 8080 ||
            !RegExp(r'^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')
                .hasMatch(integrationRunId))) {
      throw const ApiFailure(
        'MySQL 통합 실행 ID는 디버그 localhost:8080의 승인된 UUID만 허용합니다.',
      );
    }
    if (base.host.isEmpty ||
        base.port < 1 ||
        base.port > 65535 ||
        base.userInfo.isNotEmpty ||
        base.query.isNotEmpty ||
        base.fragment.isNotEmpty ||
        (base.path.isNotEmpty && base.path != '/') ||
        !(base.scheme == 'https' ||
            (kDebugMode &&
                debugLoopback &&
                base.scheme == 'http' &&
                ((base.host == 'localhost' && base.port == 8080) ||
                    (allowDebugLanHttp &&
                        isPrivateLanIpv4(base.host) &&
                        isDebugLanPort(base.port)))))) {
      throw const ApiFailure(
        '서버 주소를 확인하세요. HTTPS 또는 디버그 localhost:8080만 기본 허용합니다. Wi-Fi HTTP는 명시적 개발 설정·사설 IPv4·1024~65535 포트가 필요하며 보호 포트는 사용할 수 없습니다.',
      );
    }
  }
  final Uri base;
  final Transport transport;
  final StudyRequestStore requestStore;
  final bool debugLoopback;
  final bool allowDebugLanHttp;
  // Dedicated test-mode headers only. Empty in normal builds; no automatic POST retry.
  final String integrationRunId;
  static bool isDebugLanPort(int port) =>
      port >= 1024 &&
      port <= 65535 &&
      !const {8083, 8084, 8085, 8086, 8099, 8101, 8102}.contains(port);
  static bool isPrivateLanIpv4(String host) {
    final parts = host.split('.');
    if (parts.length != 4 ||
        parts.any((p) => !RegExp(r'^(0|[1-9][0-9]{0,2})$').hasMatch(p))) {
      return false;
    }
    final n = parts.map(int.parse).toList();
    if (n.any((v) => v > 255)) return false;
    return n[0] == 10 ||
        (n[0] == 172 && n[1] >= 16 && n[1] <= 31) ||
        (n[0] == 192 && n[1] == 168);
  }

  bool get debugHttpAllowed =>
      kDebugMode &&
      debugLoopback &&
      base.scheme == 'http' &&
      ((base.host == 'localhost' && base.port == 8080) ||
          (allowDebugLanHttp &&
              isPrivateLanIpv4(base.host) &&
              isDebugLanPort(base.port)));
  Cookie? _session;
  String? _csrf;
  bool _aiInFlight = false;
  final Set<String> _uncertainAi = {};
  void clear() {
    _session = null;
    _csrf = null;
  }

  void close() {
    clear();
    transport.close();
  }

  Future<dynamic> _request(
    String method,
    String path, {
    Map<String, String>? query,
    String? body,
    String contentType = 'application/x-www-form-urlencoded',
    bool requireCsrf = false,
    bool aiCall = false,
    bool preparedValidation = false,
    String? idempotencyKey,
  }) async {
    final uri = base.replace(path: path, queryParameters: query);
    final headers = <String, String>{'Accept': 'application/json'};
    final cookie = _session;
    if (cookie != null &&
        (cookie.expires == null || cookie.expires!.isAfter(DateTime.now()))) {
      // Same-origin only. HTTP exception is explicit debug development only.
      if (!cookie.secure || uri.scheme == 'https' || debugHttpAllowed) {
        headers['Cookie'] = '${cookie.name}=${cookie.value}';
      }
    }
    if (method == 'POST' || requireCsrf) {
      if (_csrf == null) throw const ApiFailure('보안 토큰이 없습니다. 다시 로그인하세요.');
      headers['X-CSRF-TOKEN'] = _csrf!;
      if (method == 'POST') headers['Content-Type'] = contentType;
    }
    if (method == 'POST' &&
        integrationRunId.isNotEmpty &&
        !path.startsWith('/auth/')) {
      headers['X-Integration-Run'] = integrationRunId;
      headers['Idempotency-Key'] = idempotencyKey ?? newStudyRequestKey();
    }
    if (method == 'POST' && idempotencyKey != null) {
      headers['Idempotency-Key'] = idempotencyKey;
    }
    final current = transport;
    final reply = aiCall && current is IoTransport
        ? await current._send(
            method,
            uri,
            headers,
            body,
            const Duration(seconds: 90),
          )
        : await current.send(method, uri, headers, body);
    for (final c in reply.cookies) {
      if (c.name != 'JSESSIONID' ||
          (c.domain != null && c.domain != base.host) ||
          (c.path != null && c.path != '/')) {
        continue;
      }
      _session = c.maxAge == 0 || c.value.isEmpty ? null : c;
    }
    if (reply.status == 401) {
      clear();
      throw const ApiFailure('로그인이 필요하거나 계정 정보가 올바르지 않습니다.', 401);
    }
    if (reply.status == 403) {
      _csrf = null;
      throw const ApiFailure('보안 토큰 또는 접근 권한을 확인하세요. 다시 로그인해 주세요.', 403);
    }
    if (reply.status < 200 || reply.status >= 300) {
      if (preparedValidation && {400, 409}.contains(reply.status)) {
        try {
          final error = jsonDecode(reply.body);
          if (error is Map &&
              error['code'] == 'APPROVED_REQUEST_MISMATCH' &&
              error['errors'] is List) {
            const labels = {
              'question': '승인 질문 불일치(띄어쓰기·마침표 확인)',
              'subject': '승인 과목 불일치',
              'situation': '승인 학습 상황 불일치',
              'understanding': '승인 이해도 불일치',
              'provider': '승인 제공업체 불일치',
              'goalId': '승인 목표 불일치',
              'callLimit': '호출 한도 사용됨 · 재전송 금지',
              'serverMode': '승인 서버 모드 불일치',
              'confirmPaidCall': '실제 호출·비용 확인 필요',
              'request': '승인 입력 필드 불일치',
            };
            final details = <String>[];
            for (final item in error['errors']) {
              if (item is Map && labels.containsKey(item['field'])) {
                details.add(labels[item['field']]!);
              }
            }
            if (details.isNotEmpty) {
              throw ApiFailure(details.join('\n'), reply.status);
            }
          }
        } on ApiFailure {
          rethrow;
        } catch (_) {
          /* Retain generic error for malformed/untrusted error payload. */
        }
      }
      throw ApiFailure('요청 실패 (${reply.status}). 자동 재시도하지 않습니다.', reply.status);
    }
    try {
      return jsonDecode(reply.body);
    } catch (_) {
      throw const ApiFailure('서버 응답 형식이 올바르지 않습니다.');
    }
  }

  Future<void> _token() async {
    final data = await _request('GET', '/auth/csrf');
    if (data is! Map ||
        data['headerName'] != 'X-CSRF-TOKEN' ||
        data['token'] is! String ||
        (data['token'] as String).isEmpty) {
      throw const ApiFailure('서버 보안 토큰을 확인할 수 없습니다.');
    }
    _csrf = data['token'] as String;
  }

  Future<void> login(String username, String password) async {
    clear();
    try {
      await _token();
      await _request(
        'POST',
        '/auth/login',
        body: Uri(queryParameters: {'username': username, 'password': password})
            .query,
      );
      _csrf = null;
      await _token(); // Login rotates the session and invalidates the old token.
      final me = await _request('GET', '/auth/me');
      if (me is! Map || me['authenticated'] != true) {
        throw const ApiFailure('로그인 상태를 확인할 수 없습니다.');
      }
    } catch (_) {
      clear();
      rethrow;
    }
  }

  Future<List<Map<String, dynamic>>> goals() async {
    final data = await _request('GET', '/goals');
    if (data is! List ||
        data.any(
          (g) => g is! Map || g['goalId'] is! int || g['goalName'] is! String,
        )) {
      throw const ApiFailure('목표 응답 형식이 올바르지 않습니다.');
    }
    return data.map((g) => Map<String, dynamic>.from(g as Map)).toList();
  }

  Future<Map<String, dynamic>> summary(int goalId) async {
    final data = await _request(
      'GET',
      '/analysis-summary',
      query: {'scope': 'current', 'goalId': '$goalId'},
    );
    if (data is! Map ||
        data['goalId'] != goalId ||
        data['scope'] != 'CURRENT_GOAL') {
      throw const ApiFailure('선택한 목표와 응답이 일치하지 않습니다.');
    }
    return Map<String, dynamic>.from(data);
  }

  Future<List<Map<String, dynamic>>> entrySubjects(int goalId) async {
    if (goalId <= 0) throw const ApiFailure('목표를 선택하세요.');
    final data = await _request(
      'GET',
      '/subjects',
      query: {'goalId': '$goalId'},
    );
    if (data is! List) throw const ApiFailure('과목 정보를 확인할 수 없습니다.');
    final rows = <Map<String, dynamic>>[];
    for (final row in data) {
      if (row is! Map ||
          row['goalId'] != goalId ||
          row['subjectId'] is! int ||
          row['userId'] is! int ||
          row['subjectName'] is! String) {
        throw const ApiFailure('선택 목표와 과목 정보가 일치하지 않습니다.');
      }
      rows.add(Map<String, dynamic>.from(row));
    }
    return rows;
  }

  bool _savingStudy = false;
  String _studyScope(int goalId, [int userId = 1]) =>
      '${base.origin}/run/${integrationRunId.isEmpty ? 'normal' : integrationRunId}/user/$userId/goal/$goalId';
  Future<String?> pendingStudyRequest(int goalId) =>
      requestStore.pending(_studyScope(goalId));
  Future<Map<String, dynamic>?> resolveStudyRequest(int goalId) async {
    final key = await pendingStudyRequest(goalId);
    if (key == null) throw const ApiFailure('보관된 요청 키가 없습니다. 기록 목록을 확인하세요.');
    final config = await _request('GET', '/study-log-idempotency');
    if (config is! Map || config['enabled'] != true) {
      throw const ApiFailure('서버 영속 멱등성이 비활성 또는 미지원 상태입니다. 기존 기록 목록을 확인하세요.');
    }
    final raw = await _request(
      'GET',
      '/study-log-requests',
      query: {'goalId': '$goalId', 'requestKey': key},
    );
    if (raw is! Map || raw['goalId'] != goalId || raw['requestKey'] != key) {
      throw const ApiFailure('결과 조회의 목표·요청 키가 일치하지 않습니다.');
    }
    if (raw['state'] == 'NOT_CONFIRMED') return null;
    final record = raw['result'];
    if (raw['state'] != 'COMPLETED' ||
        record is! Map ||
        record['userId'] != 1 ||
        record['logId'] is! int ||
        record['logId'] <= 0 ||
        record['idempotency'] is! Map ||
        record['idempotency']['requestKey'] != key) {
      throw const ApiFailure('완료 결과를 확인할 수 없습니다.');
    }
    final subjects = await entrySubjects(goalId);
    if (!subjects.any((s) => s['subjectId'] == record['subjectId'])) {
      throw const ApiFailure('결과 기록의 과목이 선택 목표와 다릅니다.');
    }
    await requestStore.complete(_studyScope(goalId), key);
    return Map<String, dynamic>.from(record);
  }

  Future<Map<String, dynamic>> saveStudyRecord({
    required int goalId,
    required int subjectId,
    required String date,
    required int minutes,
    required int understanding,
    required int focus,
    required int fatigue,
    required String content,
    String? method,
    String? memo,
  }) async {
    _validatePlanRequest(goalId, date);
    if (subjectId <= 0 ||
        minutes <= 0 ||
        minutes > 2147483647 ||
        content.trim().isEmpty ||
        [understanding, focus, fatigue].any((v) => v < 1 || v > 5)) {
      throw const ApiFailure('과목·공부시간·내용·점수를 확인하세요.');
    }
    if (_savingStudy) throw const ApiFailure('이미 저장 중입니다.');
    _savingStudy = true;
    bool posted = false;
    String? requestKey;
    int userId = 1;
    try {
      final me = await _request('GET', '/auth/me');
      if (me is! Map || me['authenticated'] != true || me['userId'] is! int) {
        throw const ApiFailure('로그인 정보를 확인할 수 없습니다.', 401);
      }
      final subjects = await entrySubjects(goalId);
      if (!subjects.any(
        (s) => s['subjectId'] == subjectId && s['userId'] == me['userId'],
      )) {
        throw const ApiFailure('선택 목표의 과목만 저장할 수 있습니다.');
      }
      await _token();
      userId = me['userId'] as int;
      final reserved = await requestStore.reserve(_studyScope(goalId, userId));
      if (reserved.existing) {
        throw const StudySaveFailure(
          '이 목표에 미확인 요청 키가 있습니다. 새 POST 대신 결과를 조회하세요.',
          null,
          uncertain: true,
        );
      }
      requestKey = reserved.key;
      final payload = <String, dynamic>{
        'userId': me['userId'],
        'subjectId': subjectId,
        'studyDate': date,
        'studyMinutes': minutes,
        'studyContent': content.trim(),
        'understandingScore': understanding,
        'focusScore': focus,
        'fatigueScore': fatigue,
        if (method != null && method.isNotEmpty) 'studyMethod': method,
        if (memo != null && memo.trim().isNotEmpty) 'memo': memo.trim(),
      };
      posted = true;
      final data = await _request(
        'POST',
        '/study-logs',
        query: {'goalId': '$goalId'},
        body: jsonEncode(payload),
        contentType: 'application/json; charset=UTF-8',
        idempotencyKey: requestKey,
      );
      if (data is! Map ||
          data['logId'] is! int ||
          data['logId'] <= 0 ||
          payload.entries.any((e) => data[e.key] != e.value)) {
        throw const ApiFailure('저장 응답을 확인할 수 없습니다.');
      }
      await requestStore.complete(_studyScope(goalId, userId), requestKey);
      return Map<String, dynamic>.from(data);
    } on ApiFailure catch (e) {
      final uncertain =
          (e is StudySaveFailure && e.uncertain) ||
          posted &&
              (e.status == null ||
                  e.status! >= 500 ||
                  const [408, 409, 429].contains(e.status));
      if (!uncertain && requestKey != null) {
        await requestStore.complete(_studyScope(goalId, userId), requestKey);
      }
      throw StudySaveFailure(
        uncertain
            ? '저장 여부를 확인할 수 없습니다. 중복 저장 방지를 위해 다시 전송하지 말고 기록 목록을 확인하세요.'
            : e.message,
        e.status,
        uncertain: uncertain,
      );
    } finally {
      _savingStudy = false;
    }
  }

  /// /study-logs is user-scoped, NOT goal-scoped. Join only the validated
  /// subjects of the selected goal; never infer a goal from names or dates.
  Future<List<Map<String, dynamic>>> studyRecords(int goalId) async {
    if (goalId <= 0) throw const ApiFailure('목표 ID가 올바르지 않습니다.');
    final subjects = await _request(
      'GET',
      '/subjects',
      query: {'goalId': '$goalId'},
    );
    if (subjects is! List) throw const ApiFailure('과목 응답 형식이 올바르지 않습니다.');
    final byId = <int, Map<String, dynamic>>{};
    for (final subject in subjects) {
      if (subject is! Map ||
          subject['goalId'] != goalId ||
          subject['subjectId'] is! int ||
          subject['userId'] is! int ||
          subject['subjectName'] is! String ||
          byId.containsKey(subject['subjectId'])) {
        throw const ApiFailure('선택 목표의 과목 정보를 확인할 수 없습니다.');
      }
      byId[subject['subjectId'] as int] = Map<String, dynamic>.from(subject);
    }
    if (byId.isEmpty) return [];
    final logs = await _request('GET', '/study-logs');
    if (logs is! List) throw const ApiFailure('공부 기록 응답 형식이 올바르지 않습니다.');
    final result = <Map<String, dynamic>>[];
    for (final log in logs) {
      if (log is! Map || log['subjectId'] is! int) {
        throw const ApiFailure('공부 기록의 과목을 확인할 수 없습니다.');
      }
      final subject = byId[log['subjectId']];
      if (subject == null) continue;
      final date = log['studyDate'];
      final parsed = date is String ? DateTime.tryParse(date) : null;
      if (log['userId'] != subject['userId'] ||
          log['logId'] is! int ||
          date is! String ||
          !RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(date) ||
          parsed == null ||
          parsed.toIso8601String().substring(0, 10) != date ||
          (log['studyMinutes'] != null &&
              (log['studyMinutes'] is! int || log['studyMinutes'] < 0))) {
        throw const ApiFailure('공부 기록의 사용자·날짜·시간 정보를 확인할 수 없습니다.');
      }
      result.add({
        ...Map<String, dynamic>.from(log),
        'subjectName': subject['subjectName'],
      });
    }
    result.sort((a, b) {
      final order = (b['studyDate'] as String).compareTo(
        a['studyDate'] as String,
      );
      return order != 0
          ? order
          : (a['logId'] as int).compareTo(b['logId'] as int);
    });
    return result;
  }

  /// GET only: reads a feature's stored AI responses for one selected goal.
  /// This must never be replaced by /llm-coaching or /gpt-weekly-coaching:
  /// those routes may generate paid answers.
  Future<List<Map<String, dynamic>>> savedResponses({
    required int goalId,
    required String feature,
  }) async {
    if (goalId <= 0 ||
        !const {
          'DAILY_COACHING',
          'WEEKLY_COACHING',
          'QUESTION',
        }.contains(feature)) {
      throw const ApiFailure('잘못된 목표 또는 AI 응답 종류입니다.');
    }
    final data = await _request(
      'GET',
      '/ai-responses',
      query: {'goalId': '$goalId', 'feature': feature},
    );
    if (data is! List) {
      throw const ApiFailure('저장 응답 형식이 올바르지 않습니다.');
    }
    final result = <Map<String, dynamic>>[];
    for (final item in data) {
      if (item is! Map ||
          item['responseId'] is! int ||
          item['goalId'] != goalId ||
          item['feature'] != feature ||
          item['responseBody'] is! String) {
        throw const ApiFailure('요청한 목표와 저장 응답이 일치하지 않습니다.');
      }
      result.add(Map<String, dynamic>.from(item));
    }
    return result;
  }

  /// Read-only plan for the explicitly selected goal and date. Never trust
  /// the request alone: an exact goalId match in the JSON is mandatory.
  /// GET can return a saved snapshot or a freshly calculated, unsaved plan.
  Future<Map<String, dynamic>> calculatedPlan({
    required int goalId,
    required String date,
  }) async {
    _validatePlanRequest(goalId, date);
    final raw = await _request(
      'GET',
      '/study-plan',
      query: {'date': date, 'goalId': '$goalId'},
    );
    final plan = _planReply(raw, goalId, date, isSavedQuery: false);
    if (plan['isScheduleValid'] != true || plan['hasConflict'] != false) {
      throw const ApiFailure('서버에서 유효한 시간표를 확인하지 못했습니다. 실제 계획으로 표시하지 않습니다.');
    }
    return plan;
  }

  /// GoalSelection validates ownership of the explicit goalId on the server.
  /// A returned saved snapshot must also match the requested goal and date.
  /// This GET is not POST /study-reschedule-save.
  Future<Map<String, dynamic>> storedPlan({
    required int goalId,
    required String date,
  }) async {
    _validatePlanRequest(goalId, date);
    final raw = await _request(
      'GET',
      '/study-reschedule-saved',
      query: {'date': date, 'goalId': '$goalId'},
    );
    return _planReply(raw, goalId, date, isSavedQuery: true);
  }

  /// Stored snapshots only. One GET/month; never calls plan calculation or save.
  Future<List<Map<String, dynamic>>> savedPlanMonth({
    required int goalId,
    required String month,
  }) async {
    if (!RegExp(r'^(19[7-9][0-9]|20[0-9]{2}|2100)-(0[1-9]|1[0-2])$')
            .hasMatch(month) ||
        goalId <= 0) {
      throw const ApiFailure('목표 또는 조회 월을 확인하세요.');
    }
    final raw = await _request(
      'GET',
      '/study-reschedule-calendar',
      query: {'goalId': '$goalId', 'month': month},
    );
    if (raw is! Map ||
        raw['goalId'] != goalId ||
        raw['month'] != month ||
        raw['plans'] is! List ||
        (raw['plans'] as List).length > 31) {
      throw const ApiFailure('월간 저장 계획의 목표·월·응답 형식이 일치하지 않습니다.');
    }
    final seen = <String>{};
    return (raw['plans'] as List).map((p) {
      final date = p is Map ? p['planDate'] : null;
      if (date is! String || !date.startsWith('$month-') || !seen.add(date)) {
        throw const ApiFailure('월간 계획의 날짜 또는 중복 자료를 확인하세요.');
      }
      _validatePlanRequest(goalId, date);
      final plan = _planReply(p, goalId, date, isSavedQuery: true);
      if (!['DRAFT', 'FINAL'].contains(plan['planStatus'])) {
        throw const ApiFailure('서버 저장 상태를 확인할 수 없습니다.');
      }
      return plan;
    }).toList();
  }

  Future<Map<String, dynamic>> reschedulePreview(int goalId) async {
    if (goalId <= 0) throw const ApiFailure('목표를 선택하세요.');
    final raw = await _request(
      'GET',
      '/study-reschedule-preview',
      query: {'goalId': '$goalId'},
    );
    if (raw is Map && raw['message'] is String) {
      throw ApiFailure(raw['message'] as String);
    }
    final date = raw is Map ? raw['scheduleDate'] : null;
    if (date is! String) throw const ApiFailure('미리보기 대상 날짜를 확인할 수 없습니다.');
    _validatePlanRequest(goalId, date);
    final plan = _planReply(raw, goalId, date, isSavedQuery: false);
    if (plan['previewMode'] != true ||
        plan['isScheduleValid'] != true ||
        plan['hasConflict'] != false) {
      throw const ApiFailure('유효한 재조정 미리보기를 확인하지 못했습니다.');
    }
    _rescheduleMinutes(plan);
    for (final key in ['subjectPlans', 'timeSlots', 'carryoverCalculation']) {
      if (plan[key] != null && plan[key] is! List) {
        throw const ApiFailure('미리보기 세부 응답 형식이 올바르지 않습니다.');
      }
    }
    return plan;
  }

  bool _savingReschedule = false;
  final Set<String> _uncertainReschedules = {};
  bool rescheduleUncertain(int goalId, String date) =>
      _uncertainReschedules.contains('$goalId/$date');

  // 서버가 내일 계획을 다시 계산한다. 날짜나 계획 JSON을 POST 본문으로 보내지 않는다.
  Future<Map<String, dynamic>> saveReschedule({
    required int goalId,
    required String date,
  }) async {
    _validatePlanRequest(goalId, date);
    if (_savingReschedule) {
      throw const RescheduleSaveFailure('이미 계획 저장 중입니다.', null);
    }
    if (rescheduleUncertain(goalId, date)) {
      throw const RescheduleSaveFailure(
        '이 계획은 저장 여부가 불확실합니다. 재전송하지 말고 저장본을 조회하세요.',
        null,
        uncertain: true,
      );
    }
    _savingReschedule = true;
    bool posted = false;
    try {
      await _token();
      posted = true;
      final raw = await _request(
        'POST',
        '/study-reschedule-save',
        query: {'goalId': '$goalId'},
        body: '',
      );
      if (raw is! Map ||
          raw['saved'] != true ||
          raw['planDate'] != date ||
          !['DRAFT', 'FINAL'].contains(raw['planStatus'])) {
        throw const ApiFailure('계획 저장 응답을 확인할 수 없습니다.');
      }
      final result = Map<String, dynamic>.from(raw);
      _rescheduleMinutes(result);
      return result;
    } on ApiFailure catch (e) {
      final uncertain = posted && (e.status == null || e.status! >= 500);
      if (uncertain) _uncertainReschedules.add('$goalId/$date');
      throw RescheduleSaveFailure(
        uncertain ? '저장 여부가 불확실합니다. 다시 저장하지 말고 저장본 GET 조회로 확인하세요.' : e.message,
        e.status,
        uncertain: uncertain,
      );
    } finally {
      _savingReschedule = false;
    }
  }

  void _rescheduleMinutes(Map<String, dynamic> p) {
    for (final key in ['studyMinutes', 'carryoverMinutes', 'newStudyMinutes']) {
      if (p[key] is! int || (p[key] as int) < 0) {
        throw const ApiFailure('재조정 시간 응답이 올바르지 않습니다.');
      }
    }
    if (p['studyMinutes'] !=
        (p['carryoverMinutes'] as int) + (p['newStudyMinutes'] as int)) {
      throw const ApiFailure('재조정 시간 합계가 일치하지 않습니다.');
    }
  }

  void _validatePlanRequest(int goalId, String date) {
    final match = RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(date);
    final parsed = match ? DateTime.tryParse(date) : null;
    if (goalId <= 0 ||
        parsed == null ||
        '${parsed.year.toString().padLeft(4, '0')}-${parsed.month.toString().padLeft(2, '0')}-${parsed.day.toString().padLeft(2, '0')}' !=
            date) {
      throw const ApiFailure('목표 ID 또는 날짜 형식이 올바르지 않습니다.');
    }
  }

  Map<String, dynamic> _planReply(
    dynamic raw,
    int goalId,
    String date, {
    required bool isSavedQuery,
  }) {
    if (raw is! Map) throw const ApiFailure('학습 계획 응답 형식이 올바르지 않습니다.');
    final plan = Map<String, dynamic>.from(raw);
    // A missing saved plan is a valid empty state, not a new plan.
    if (isSavedQuery && plan['saved'] == false) {
      if ((plan['goalId'] != null && plan['goalId'] != goalId) ||
          (plan['planDate'] != null && plan['planDate'] != date)) {
        throw const ApiFailure('저장 계획 조회 대상이 요청과 일치하지 않습니다.');
      }
      return plan;
    }
    if (plan['goalId'] != goalId) {
      throw const ApiFailure('서버 계획의 목표 ID가 선택한 목표와 일치하지 않아 표시하지 않습니다.');
    }
    final rawDate = (plan['planDate'] ?? plan['scheduleDate'])?.toString();
    if (rawDate != date) {
      throw const ApiFailure('서버 계획 날짜가 요청한 날짜와 일치하지 않습니다.');
    }
    if (isSavedQuery && plan['saved'] != true) {
      throw const ApiFailure('저장된 계획 여부를 확인할 수 없습니다.');
    }
    if (plan['studyMinutes'] is! num ||
        plan['carryoverMinutes'] is! num ||
        plan['newStudyMinutes'] is! num) {
      throw const ApiFailure('계획의 공부·보충·신규 시간이 누락됐습니다.');
    }
    return plan;
  }

  Future<void> logout() async {
    await _token();
    await _request('POST', '/auth/logout', body: '');
    clear();
  }
}
