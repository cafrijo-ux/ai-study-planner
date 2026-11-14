part of 'planner_api.dart';

enum AiFeature { question, daily, weekly }

// Display-only provenance. This never changes readiness or provider validation.
bool isIsolatedAiMock(Map<String, dynamic> raw) =>
    raw['mockResponse'] == true &&
    (raw['testMode'] == 'AI_MOCK_H2_ONLY' ||
        (raw['testMode'] == 'AI_MOCK_DUAL_LEDGER_ONLY' &&
            raw['storageMode'] == 'MYSQL_DUAL_LEDGER_FAKEWIRE' &&
            raw['realAiCalls'] == 0));

const preparedGeminiMode = 'GEMINI_ONCE_H2_PREPARED';

class PreparedQuestionContract {
  const PreparedQuestionContract(this.raw);
  final Map<String, dynamic> raw;
  int get goalId => raw['approvedGoalId'] as int;
  String get model => raw['model'] as String;
  String value(String field) => raw[field] as String;
  bool get available =>
      raw['generationEnabled'] == true && raw['callUsed'] == false;
  Map<String, String> differences({
    required int goalId,
    required String provider,
    required String question,
    required String subject,
    required String situation,
    required String understanding,
  }) {
    final result = <String, String>{};
    if (goalId != this.goalId) result['goalId'] = '선택 목표가 승인된 목표 A와 다릅니다.';
    final inputs = {
      'provider': provider,
      'question': question,
      'subject': subject,
      'situation': situation,
      'understanding': understanding,
    };
    const labels = {
      'provider': '제공업체',
      'question': '질문(띄어쓰기·마침표 포함)',
      'subject': '과목',
      'situation': '학습 상황',
      'understanding': '이해도',
    };
    for (final entry in inputs.entries) {
      if (entry.value != value(entry.key)) {
        result[entry.key] = '${labels[entry.key]}이 승인값과 다릅니다.';
      }
    }
    if (!available) result['callLimit'] = '서버 모드 또는 호출 한도로 생성이 차단돼 있습니다.';
    return result;
  }
}

class PreparedQuestionCheck {
  const PreparedQuestionCheck(this.valid, this.errors);
  final bool valid;
  final Map<String, String> errors;
  String get message => valid
      ? '입력 사전 검증 통과 · AI 호출 및 비용 확인은 아직 하지 않았습니다.'
      : errors.values.join('\n');
}

class AiGenerationFailure extends ApiFailure {
  const AiGenerationFailure(
    super.message,
    super.status, {
    this.uncertain = false,
  });
  final bool uncertain;
}

class AiSetup {
  const AiSetup(this.feature, this.raw);
  final AiFeature feature;
  final Map<String, dynamic> raw;
  PreparedQuestionContract? get preparedQuestion =>
      raw['_preparedQuestion'] is Map<String, dynamic>
      ? PreparedQuestionContract(
          raw['_preparedQuestion'] as Map<String, dynamic>,
        )
      : null;
  String model(String provider) => feature == AiFeature.question
      ? ((raw[provider] is Map ? raw[provider]['model'] : null)?.toString() ??
            '정보 없음')
      : raw['model']?.toString() ?? '정보 없음';
  bool ready(String provider) {
    if (feature == AiFeature.question) {
      final s = raw[provider];
      return raw['goalScopedResponse'] == true &&
          s is Map &&
          s['enabled'] == true &&
          s['configured'] == true &&
          (provider == 'gpt'
              ? s['model'] == 'gpt-4o-mini'
              : s['model'] is String &&
                    (s['model'] as String).startsWith('gemini-'));
    }
    if (feature == AiFeature.daily) {
      final cost = raw['aiCostProtection'];
      if (cost is Map && cost['enabled'] == true) {
        final policy = cost['features'] is Map
            ? cost['features']['DAILY_COACHING']
            : null;
        if (policy is! Map ||
            policy['configured'] != true ||
            policy['approvedModel'] != model(provider)) {
          return false;
        }
      }
      return raw['provider'] == 'gemini' &&
          raw['_goalSubjectsPresent'] == true &&
          raw['demoMode'] == false &&
          raw['ready'] == true &&
          model(provider).startsWith('gemini-');
    }
    return raw['enabled'] == true &&
        raw['apiKeyConfigured'] == true &&
        raw['dataReady'] == true &&
        raw['model'] == 'gpt-4o-mini';
  }

  String availability(String provider) {
    if (ready(provider)) return '서버 설정 준비됨 · 인증·요금 한도·기존 호출 제한의 실제 통과 여부는 미확인';
    if (feature == AiFeature.daily &&
        raw['aiCostProtection'] is Map &&
        raw['aiCostProtection']['enabled'] == true) {
      return '일반 코칭 비용 정책 미승인 또는 모델 불일치입니다. 관리자 승인 전 새 생성은 하지 않습니다.';
    }
    if (feature == AiFeature.question && raw['goalScopedResponse'] != true) {
      return '서버의 목표별 질문 응답 지원을 확인할 수 없습니다. 최신 서버 반영 후 이용하세요. 생성 요청은 보내지 않습니다.';
    }
    if (feature == AiFeature.daily && raw['demoMode'] == true) {
      return '서버가 데모 모드입니다. 실제 Gemini 코칭 생성은 비활성 상태입니다.';
    }
    return '생성 불가 · 기능 비활성, 키/모델 설정 또는 목표·과목 데이터를 확인하세요. 앱에서 설정을 활성화하지 않습니다.';
  }
}

extension PlannerAi on PlannerApi {
  String _aiScope(int goal) => '${_studyScope(goal)}/ai/DAILY_COACHING/gemini';
  Future<bool> restoreAiPending(AiFeature feature, int goal) async {
    if (feature != AiFeature.daily) return false;
    final exists = await requestStore.pending(_aiScope(goal)) != null;
    if (exists) _uncertainAi.add('$goal/daily/gemini');
    return exists;
  }

  Future<Map<String, dynamic>?> resolveDailyAi(int goal) async {
    final key = await requestStore.pending(_aiScope(goal));
    if (key == null) throw const ApiFailure('보관된 일반 코칭 요청 키가 없습니다.');
    final policy = await _request('GET', '/ai-generation-policy');
    if (policy is! Map || policy['enabled'] != true || policy['version'] != 1) {
      throw const ApiFailure(
        '서버 비용 보호 원장은 OFF/미지원 상태입니다. 저장 이력과 실제 호출 여부를 확인하세요. 새 생성은 하지 않습니다.',
      );
    }
    final features = policy['features'];
    final config = features is Map ? features['DAILY_COACHING'] : null;
    final approved = config is Map ? config['approvedModel'] : null;
    if (approved is! String || !approved.startsWith('gemini-')) {
      throw const ApiFailure('서버의 승인된 일반 코칭 모델 계약을 확인할 수 없습니다. 새 생성하지 않습니다.');
    }
    final raw = await _request(
      'GET',
      '/ai-generation-requests',
      query: {
        'goalId': '$goal',
        'feature': 'DAILY_COACHING',
        'requestKey': key,
      },
    );
    if (raw is! Map ||
        raw['goalId'] != goal ||
        raw['requestKey'] != key ||
        raw['feature'] != 'DAILY_COACHING') {
      throw const ApiFailure('요청 키·목표·기능이 일치하지 않습니다.');
    }
    if (raw['state'] != 'COMPLETED') return null;
    final r = raw['result'];
    if (r is! Map ||
        r['goalId'] != goal ||
        r['provider'] != 'gemini' ||
        r['model'] != approved ||
        r['responseSource'] != 'provider' ||
        r['model'] is! String ||
        !(r['model'] as String).startsWith('gemini-') ||
        r['responseModel'] is! String ||
        !(r['responseModel'] as String).startsWith(r['model'] as String) ||
        r['llmResponse'] is! String ||
        (r['llmResponse'] as String).trim().isEmpty ||
        r['costProtection'] is! Map ||
        r['costProtection']['requestKey'] != key ||
        r['costProtection']['state'] != 'COMPLETED') {
      throw const ApiFailure('저장된 코칭의 모델·출처·목표를 확인할 수 없습니다.');
    }
    await requestStore.complete(_aiScope(goal), key);
    _uncertainAi.remove('$goal/daily/gemini');
    return {
      ...Map<String, dynamic>.from(r),
      '_text': r['llmResponse'],
      '_displayModel': r['responseModel'],
    };
  }

  String featureCode(AiFeature feature) => switch (feature) {
    AiFeature.question => 'QUESTION',
    AiFeature.daily => 'DAILY_COACHING',
    AiFeature.weekly => 'WEEKLY_COACHING',
  };
  bool aiUncertain(AiFeature feature, int goal, String provider) =>
      _uncertainAi.contains('$goal/${feature.name}/$provider');
  Future<AiSetup> prepareAi(AiFeature feature, int goalId) async {
    if (goalId <= 0) throw const ApiFailure('목표를 선택하세요.');
    // 소유 목표 검증은 기존 과목 GET과 생성 API의 서버 검사를 함께 사용한다.
    final subjects = await entrySubjects(goalId);
    final path = switch (feature) {
      AiFeature.question => '/study-question/capabilities',
      AiFeature.daily => '/llm-config-check',
      AiFeature.weekly => '/gpt-weekly-coaching/preview',
    };
    final raw = await _request('GET', path, query: {'goalId': '$goalId'});
    if (raw is! Map) throw const ApiFailure('AI 설정 응답이 올바르지 않습니다.');
    if (feature == AiFeature.weekly && raw['goalId'] != goalId) {
      throw const ApiFailure('주간 미리보기의 목표가 일치하지 않습니다.');
    }
    Map<String, dynamic>? contract;
    if (feature == AiFeature.question &&
        raw.containsKey('preparedGeminiContract')) {
      if (raw['preparedGeminiContract'] != 1 ||
          raw['preparedGeminiMode'] != preparedGeminiMode) {
        throw const ApiFailure('승인 전용 서버 계약 버전을 확인할 수 없습니다. 생성하지 않습니다.');
      }
      final approval = await _request(
        'GET',
        '/prepared-gemini/approval',
        query: {'goalId': '$goalId'},
      );
      if (approval is! Map ||
          approval['contractVersion'] != 1 ||
          approval['mode'] != preparedGeminiMode ||
          approval['selectedGoalId'] != goalId ||
          approval['approvedGoalId'] is! int ||
          approval['approvedGoalId'] <= 0 ||
          approval['model'] !=
              AiSetup(
                feature,
                Map<String, dynamic>.from(raw),
              ).model('gemini') ||
          approval['provider'] != 'gemini' ||
          approval['generationEnabled'] is! bool ||
          approval['callUsed'] is! bool ||
          ![
            'question',
            'subject',
            'situation',
            'understanding',
          ].every((f) => approval[f] is String)) {
        throw const ApiFailure('승인된 질문·목표·모델 정보를 검증하지 못했습니다. 생성하지 않습니다.');
      }
      contract = Map<String, dynamic>.from(approval);
    }
    return AiSetup(feature, {
      ...Map<String, dynamic>.from(raw),
      if (feature == AiFeature.daily)
        '_goalSubjectsPresent': subjects.isNotEmpty,
      '_preparedQuestion': ?contract,
    });
  }

  Future<PreparedQuestionCheck> checkPreparedQuestion({
    required AiSetup setup,
    required int goalId,
    required String provider,
    required String question,
    required String subject,
    required String situation,
    required String understanding,
  }) async {
    final contract = setup.preparedQuestion;
    if (contract == null) {
      throw const ApiFailure('현재 서버는 승인 전용 사전 검증 환경이 아닙니다.');
    }
    await _token();
    final result = await _request(
      'POST',
      '/prepared-gemini/preflight',
      query: {'goalId': '$goalId'},
      contentType: 'application/json; charset=UTF-8',
      body: jsonEncode({
        'model': setup.model(provider),
        'serverMode': setup.raw['preparedGeminiMode'],
        'provider': provider,
        'question': question,
        'subject': subject,
        'situation': situation,
        'understanding': understanding,
      }),
    );
    if (result is! Map ||
        result['contractVersion'] != 1 ||
        result['mode'] != preparedGeminiMode ||
        result['model'] != contract.model ||
        result['approvedGoalId'] != contract.goalId ||
        result['valid'] is! bool ||
        result['errors'] is! List ||
        result['providerCalls'] != 0) {
      throw const ApiFailure('사전 검증 응답이 올바르지 않습니다. 생성하지 않습니다.');
    }
    final errors = <String, String>{};
    for (final item in result['errors']) {
      if (item is! Map ||
          item['field'] is! String ||
          item['message'] is! String ||
          !{
            'goalId',
            'serverMode',
            'callLimit',
            'request',
            'provider',
            'question',
            'subject',
            'situation',
            'understanding',
            'model',
          }.contains(item['field'])) {
        throw const ApiFailure('사전 검증 오류 형식을 확인하지 못했습니다.');
      }
      errors[item['field'] as String] = item['message'] as String;
    }
    if (result['valid'] != (errors.isEmpty)) {
      throw const ApiFailure('사전 검증 결과가 일치하지 않습니다.');
    }
    if (result['valid'] == true &&
        contract
            .differences(
              goalId: goalId,
              provider: provider,
              question: question,
              subject: subject,
              situation: situation,
              understanding: understanding,
            )
            .isNotEmpty) {
      throw const ApiFailure('승인값과 서버 사전 검증 결과가 일치하지 않습니다. 생성하지 않습니다.');
    }
    return PreparedQuestionCheck(result['valid'] as bool, errors);
  }

  Future<Map<String, dynamic>> generateAi({
    required AiFeature feature,
    required int goalId,
    String provider = 'gemini',
    String question = '',
    String subject = '',
    String situation = '',
    String understanding = '보통',
    required bool confirmed,
  }) async {
    if (!serverWritesEnabled) {
      throw const ApiFailure('웹 2단계에서는 실제 AI 호출을 허용하지 않습니다.');
    }
    final selected = feature == AiFeature.weekly
        ? 'gpt'
        : feature == AiFeature.daily
        ? 'gemini'
        : provider;
    if (goalId <= 0 || !['gemini', 'gpt'].contains(selected)) {
      throw const ApiFailure('목표 또는 모델 선택을 확인하세요.');
    }
    if (!confirmed) throw const ApiFailure('명시적인 API 호출·비용 동의가 필요합니다.');
    if (feature == AiFeature.question &&
        (question.trim().isEmpty ||
            question.trim().length > 3000 ||
            subject.trim().length > 150 ||
            situation.trim().length > 100 ||
            understanding.length > 30)) {
      throw const ApiFailure('질문은 1~3000자, 과목은 150자, 학습 상황은 100자 이내로 입력하세요.');
    }
    final key = '$goalId/${feature.name}/$selected';
    if (feature == AiFeature.daily) await restoreAiPending(feature, goalId);
    if (_uncertainAi.contains(key)) {
      throw const AiGenerationFailure(
        '이 기능의 이전 응답이 불확실합니다. 새 요청 없이 저장 이력과 호출 여부를 확인하세요.',
        null,
        uncertain: true,
      );
    }
    if (_aiInFlight) throw const ApiFailure('이미 AI 요청이 진행 중입니다.');
    _aiInFlight = true;
    bool attempted = false;
    String? costKey;
    bool noSend = false;
    try {
      final setup = await prepareAi(feature, goalId);
      if (!setup.ready(selected)) {
        throw ApiFailure(setup.availability(selected));
      }
      if (setup.preparedQuestion != null) {
        final check = await checkPreparedQuestion(
          setup: setup,
          goalId: goalId,
          provider: selected,
          question: question,
          subject: subject,
          situation: situation,
          understanding: understanding,
        );
        if (!check.valid) throw ApiFailure(check.message);
      } else {
        await _token();
      }
      final path = switch (feature) {
        AiFeature.question => '/study-question',
        AiFeature.daily => '/llm-coaching',
        AiFeature.weekly => '/gpt-weekly-coaching',
      };
      final Map<String, dynamic> payload = feature == AiFeature.question
          ? {
              'provider': selected,
              'confirmPaidCall': true,
              'question': setup.preparedQuestion != null
                  ? question
                  : question.trim(),
              'subject': setup.preparedQuestion != null
                  ? subject
                  : subject.trim(),
              'situation': setup.preparedQuestion != null
                  ? situation
                  : situation.trim(),
              'understanding': understanding,
            }
          : {'confirmPaidCall': true};
      final protectedDaily =
          feature == AiFeature.daily &&
          setup.raw['aiCostProtection'] is Map &&
          setup.raw['aiCostProtection']['enabled'] == true;
      if (feature == AiFeature.daily) {
        final pending = await requestStore.reserve(_aiScope(goalId));
        if (pending.existing) {
          throw const AiGenerationFailure(
            '미확인 코칭 요청이 있습니다. 새 호출 대신 결과를 조회하세요.',
            null,
            uncertain: true,
          );
        }
        costKey = pending.key;
        if (protectedDaily) payload['expectedModel'] = setup.model(selected);
      }
      attempted = true;
      final raw = await _request(
        feature == AiFeature.daily && !protectedDaily ? 'GET' : 'POST',
        path,
        query: {'goalId': '$goalId'},
        body: feature == AiFeature.daily && !protectedDaily
            ? null
            : jsonEncode(payload),
        idempotencyKey: costKey,
        contentType: 'application/json; charset=UTF-8',
        requireCsrf: true,
        aiCall: true,
        preparedValidation: setup.preparedQuestion != null,
      );
      if (raw is! Map || raw['goalId'] != goalId) {
        throw const ApiFailure('AI 응답의 목표가 일치하지 않습니다. 서버 배포 버전과 저장 이력을 확인하세요.');
      }
      final result = Map<String, dynamic>.from(raw);
      if (feature == AiFeature.daily && protectedDaily) {
        final c = raw['costProtection'];
        if (c is! Map || c['requestKey'] != costKey || c['enabled'] != true) {
          throw const ApiFailure('비용 보호 응답 키가 일치하지 않습니다.');
        }
        noSend = c['state'] == 'NO_SEND' && c['dispatchReserved'] == false;
      }
      if (feature == AiFeature.question &&
          (raw['requestedProvider'] != selected ||
              raw['question'] != question.trim())) {
        throw const ApiFailure('요청 질문 또는 선택 모델과 응답이 일치하지 않습니다.');
      }
      final text =
          raw[feature == AiFeature.question ? 'aiAdvice' : 'llmResponse'];
      final source = raw['responseSource'];
      if (text is! String || text.trim().isEmpty) {
        throw ApiFailure(
          raw['message'] is String
              ? raw['message'] as String
              : 'AI 답변을 확인할 수 없습니다.',
        );
      }
      if (source == 'rule') {
        if (selected != 'gemini' ||
            (feature == AiFeature.question && raw['answerSource'] != 'rule')) {
          throw const ApiFailure('선택 모델과 규칙 응답 출처가 일치하지 않습니다.');
        }
      } else {
        if (source != 'provider' ||
            raw['demoMode'] == true ||
            raw['model'] != setup.model(selected)) {
          throw const ApiFailure('AI 응답의 모델 또는 출처가 일치하지 않습니다.');
        }
        if (feature == AiFeature.question &&
            (raw['requestedProvider'] != selected ||
                raw['answerSource'] != selected)) {
          throw const ApiFailure('선택 모델과 응답 제공업체가 일치하지 않습니다.');
        }
        if (feature == AiFeature.daily && raw['provider'] != 'gemini') {
          throw const ApiFailure('일반 코칭은 Gemini 응답만 표시합니다.');
        }
        final actual = raw['responseModel'];
        if (actual != null &&
            (actual is! String ||
                !(selected == 'gemini'
                    ? actual.startsWith('gemini-')
                    : actual.startsWith('gpt-4o-mini')))) {
          throw const ApiFailure('응답 모델을 확인할 수 없습니다.');
        }
      }
      result['_text'] = text;
      result['_displayModel'] = source == 'rule'
          ? 'rule-based'
          : raw['responseModel'] ?? raw['model'];
      if (costKey != null) {
        final committed =
            raw['costProtection'] is Map &&
            raw['costProtection']['state'] == 'COMPLETED' &&
            raw['costProtection']['resultStored'] == true;
        if (raw['responseSaved'] == true || committed || noSend) {
          await requestStore.complete(_aiScope(goalId), costKey);
          _uncertainAi.remove(key);
        } else {
          _uncertainAi.add(key);
        }
      }
      // 저장 실패여도 이미 수신한 답변을 반환한다. 재호출로 저장하지 않는다.
      return result;
    } on ApiFailure catch (e) {
      final uncertain =
          (e is AiGenerationFailure && e.uncertain) ||
          attempted && !noSend && e.status != 401 && e.status != 403;
      if (uncertain) _uncertainAi.add(key);
      if (!uncertain && costKey != null) {
        await requestStore.complete(_aiScope(goalId), costKey);
      }
      throw AiGenerationFailure(
        e.message + (uncertain ? ' 자동 재전송하지 않았습니다. 저장·과금 여부를 확인하세요.' : ''),
        e.status,
        uncertain: uncertain,
      );
    } finally {
      _aiInFlight = false;
    }
  }

  Future<List<Map<String, dynamic>>> questionHistory(int goalId) async {
    final responses = await savedResponses(goalId: goalId, feature: 'QUESTION');
    if (responses.isEmpty) return responses;
    if (!responses.any(
      (r) => r['questionId'] is int && (r['questionId'] as int) > 0,
    )) {
      return responses;
    }
    final me = await _request('GET', '/auth/me');
    final questions = await _request('GET', '/study-questions');
    if (me is! Map ||
        me['authenticated'] != true ||
        me['userId'] is! int ||
        questions is! List) {
      throw const ApiFailure('질문 이력을 확인할 수 없습니다.');
    }
    return responses.map((r) {
      final matches = questions.where(
        (q) =>
            q is Map &&
            r['questionId'] is int &&
            q['questionId'] is int &&
            q['userId'] == me['userId'] &&
            q['questionId'] == r['questionId'],
      );
      return {
        ...r,
        if (matches.length == 1 && matches.single['question'] is String)
          'questionText': matches.single['question'],
      };
    }).toList();
  }
}
