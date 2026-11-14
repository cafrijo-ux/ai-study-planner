import 'package:flutter/material.dart';

import 'dart:convert';

import 'app_design.dart';
import 'planner_api.dart';

class AiFlowResult {
  const AiFlowResult({required this.feature, this.expired = false});
  final AiFeature feature;
  final bool expired;
}

/// Selection changes presentation only. Generation still needs server checks and consent.
class AiModelSelector extends StatelessWidget {
  const AiModelSelector({super.key, required this.provider, this.onChanged});
  final String provider;
  final ValueChanged<String>? onChanged;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return LayoutBuilder(
      builder: (context, constraints) {
        final scale = MediaQuery.textScalerOf(context).scale(14) / 14;
        final columns = constraints.maxWidth >= 252 * scale ? 2 : 1;
        final width = (constraints.maxWidth - 12 * (columns - 1)) / columns;
        return Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            for (final p in ['gemini', 'gpt'])
              AnimatedContainer(
                key: Key('aiModelCard_$p'),
                duration: AppDesign.motion(context),
                curve: AppDesign.curve,
                width: width,
                decoration: BoxDecoration(
                  color: provider == p
                      ? colors.primaryContainer
                      : colors.surfaceContainerLow,
                  borderRadius: BorderRadius.circular(18),
                  border: Border.all(
                    color: provider == p
                        ? colors.primary
                        : colors.outlineVariant,
                    width: 1.5,
                  ),
                ),
                child: ChoiceChip(
                  key: Key('aiModel_$p'),
                  selected: provider == p,
                  showCheckmark: false,
                  backgroundColor: Colors.transparent,
                  selectedColor: Colors.transparent,
                  side: BorderSide.none,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(18),
                  ),
                  labelPadding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 12,
                  ),
                  materialTapTargetSize: MaterialTapTargetSize.padded,
                  label: SizedBox(
                    width: (width - 36).clamp(1, double.infinity),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Icon(
                              p == 'gemini'
                                  ? Icons.auto_awesome_outlined
                                  : Icons.forum_outlined,
                              size: 24,
                            ),
                            const Spacer(),
                            Icon(
                              provider == p
                                  ? Icons.check_circle
                                  : Icons.radio_button_unchecked,
                              size: 20,
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        Text(
                          p == 'gemini' ? 'Gemini (기본)' : 'GPT-4o-mini',
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          provider == p ? '선택한 질문 모델' : '질문 모델로 선택',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  onSelected: onChanged == null ? null : (_) => onChanged!(p),
                ),
              ),
          ],
        );
      },
    );
  }
}

/// Same server-owned metadata and readable answer surface in both history views.
class AiSavedAnswerCard extends StatelessWidget {
  const AiSavedAnswerCard({super.key, required this.row, required this.title});
  final Map<String, dynamic> row;
  final String title;

  String _value(String name, String fallback) {
    final value = row[name];
    return value == null || value.toString().trim().isEmpty
        ? fallback
        : value.toString();
  }

  @override
  Widget build(BuildContext context) => SectionCard(
    title: title,
    children: [
      Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          PlanBadge(
            label:
                '${switch (row['responseSource']) {
                  'PROVIDER' => '실제 공급자 응답',
                  'RULE' => '규칙 기반 응답',
                  _ => '출처 정보 없음',
                }} · 저장 이력',
          ),
          PlanBadge(
            label: row['responseComplete'] == true
                ? '완결 확인'
                : row['responseComplete'] == false
                ? '미완결'
                : '완결 정보 없음',
          ),
        ],
      ),
      if (row['requestedProvider'] == 'gemini' ||
          row['requestedProvider'] == 'gpt')
        Text(
          '선택 모델: ${row['requestedProvider'] == 'gemini' ? 'Gemini' : 'GPT-4o-mini'}',
        ),
      Text(
        '${_value('actualProvider', '제공업체 정보 없음')} · ${_value('model', '모델 정보 없음')}',
        style: Theme.of(context).textTheme.titleSmall,
      ),
      Text(
        _value('createdAt', '생성 시각 정보 없음'),
        style: Theme.of(context).textTheme.bodySmall,
      ),
      if (row['questionText'] is String)
        Text('저장 질문: ${row['questionText']}')
      else if (row['questionId'] != null)
        Text('관련 질문 #${row['questionId']}'),
      if (isIsolatedAiMock(row))
        const PlanBadge(label: '격리 Mock 저장 이력 · 실제 AI 아님'),
      const Divider(height: 1),
      Text('답변', style: Theme.of(context).textTheme.labelLarge),
      AnswerText(text: row['responseBody'] as String),
    ],
  );
}

class ServerAiRequest extends StatefulWidget {
  const ServerAiRequest({
    super.key,
    required this.api,
    required this.goalId,
    required this.goalName,
    required this.feature,
  });
  final PlannerApi api;
  final int goalId;
  final String goalName;
  final AiFeature feature;
  @override
  State<ServerAiRequest> createState() => _ServerAiRequestState();
}

class _ServerAiRequestState extends State<ServerAiRequest> {
  final _form = GlobalKey<FormState>();
  final _question = TextEditingController(),
      _subject = TextEditingController(),
      _situation = TextEditingController();
  String _provider = 'gemini', _understanding = '보통';
  AiSetup? _setup;
  Map<String, dynamic>? _answer;
  List<Map<String, dynamic>>? _history;
  String? _error, _historyError;
  PreparedQuestionCheck? _preflight;
  bool _checking = false;
  int _understandingVersion = 0;
  bool _loading = true,
      _sending = false,
      _confirming = false,
      _attempted = false,
      _leaving = false,
      _reading = false;
  int _version = 0;
  bool get _isQuestion => widget.feature == AiFeature.question;
  String get _selected => widget.feature == AiFeature.weekly
      ? 'gpt'
      : widget.feature == AiFeature.daily
      ? 'gemini'
      : _provider;
  String get _label => switch (widget.feature) {
    AiFeature.question => 'AI에게 질문하기',
    AiFeature.daily => 'Gemini 일반 코칭',
    AiFeature.weekly => 'GPT 주간 심층 코칭',
  };
  bool get _locked => _loading || _sending || _confirming || _checking;
  @override
  void initState() {
    super.initState();
    _question.addListener(_inputChanged);
    _subject.addListener(_inputChanged);
    _situation.addListener(_inputChanged);
    _load();
  }

  @override
  void didUpdateWidget(covariant ServerAiRequest old) {
    super.didUpdateWidget(old);
    if (old.goalId != widget.goalId || old.feature != widget.feature) {
      _version++;
      _answer = null;
      _history = null;
      _attempted = false;
      _sending = false;
      _confirming = false;
      _provider = 'gemini';
      _preflight = null;
      _checking = false;
      _load();
    }
  }

  @override
  void dispose() {
    _question.removeListener(_inputChanged);
    _subject.removeListener(_inputChanged);
    _situation.removeListener(_inputChanged);
    _question.dispose();
    _subject.dispose();
    _situation.dispose();
    super.dispose();
  }

  void _inputChanged() {
    if (mounted && _setup?.preparedQuestion != null) {
      setState(() => _preflight = null);
    }
  }

  String _inputStamp() => jsonEncode([
    widget.goalId,
    _selected,
    _question.text,
    _subject.text,
    _situation.text,
    _understanding,
  ]);
  Map<String, String> _differences(PreparedQuestionContract contract) =>
      contract.differences(
        goalId: widget.goalId,
        provider: _selected,
        question: _question.text,
        subject: _subject.text,
        situation: _situation.text,
        understanding: _understanding,
      );
  void _loadApproved() {
    final contract = _setup?.preparedQuestion;
    if (contract == null || _locked || _attempted) return;
    _question.text = contract.value('question');
    _subject.text = contract.value('subject');
    _situation.text = contract.value('situation');
    setState(() {
      _understanding = contract.value('understanding');
      _provider = contract.value('provider');
      _understandingVersion++;
      _preflight = null;
      _error = null;
    });
  }

  Future<PreparedQuestionCheck?> _runPreflight() async {
    final setup = _setup;
    if (setup?.preparedQuestion == null || _locked || _attempted) return null;
    final version = _version, stamp = _inputStamp();
    setState(() {
      _checking = true;
      _preflight = null;
      _error = null;
    });
    try {
      final check = await widget.api.checkPreparedQuestion(
        setup: setup!,
        goalId: widget.goalId,
        provider: _selected,
        question: _question.text,
        subject: _subject.text,
        situation: _situation.text,
        understanding: _understanding,
      );
      if (!mounted || version != _version || stamp != _inputStamp()) {
        return null;
      }
      setState(() => _preflight = check);
      return check;
    } on ApiFailure catch (e) {
      if (mounted && version == _version) _fail(e);
      return null;
    } finally {
      if (mounted && version == _version) setState(() => _checking = false);
    }
  }

  void _fail(ApiFailure e) {
    if (e.status == 401 || e.status == 403) {
      widget.api.clear();
      Navigator.pop(
        context,
        AiFlowResult(feature: widget.feature, expired: true),
      );
      return;
    }
    setState(() => _error = e.message);
  }

  Future<void> _load() async {
    final version = ++_version;
    setState(() {
      _loading = true;
      _error = null;
      _setup = null;
      _preflight = null;
    });
    try {
      final setup = await widget.api.prepareAi(widget.feature, widget.goalId);
      await widget.api.restoreAiPending(widget.feature, widget.goalId);
      if (mounted && version == _version) setState(() => _setup = setup);
    } on ApiFailure catch (e) {
      if (mounted && version == _version) _fail(e);
    } finally {
      if (mounted && version == _version) setState(() => _loading = false);
    }
  }

  Future<void> _readHistory() async {
    if (_reading) return;
    final version = _version;
    setState(() {
      _reading = true;
      _historyError = null;
    });
    try {
      final rows = _isQuestion
          ? await widget.api.questionHistory(widget.goalId)
          : await widget.api.savedResponses(
              goalId: widget.goalId,
              feature: widget.api.featureCode(widget.feature),
            );
      if (mounted && version == _version) setState(() => _history = rows);
    } on ApiFailure catch (e) {
      if (!mounted || version != _version) return;
      if (e.status == 401 || e.status == 403) {
        _fail(e);
      } else {
        setState(() => _historyError = e.message);
      }
    } finally {
      if (mounted && version == _version) setState(() => _reading = false);
    }
  }

  Future<void> _resolveCost() async {
    if (_locked) return;
    final version = _version;
    setState(() => _reading = true);
    try {
      final answer = await widget.api.resolveDailyAi(widget.goalId);
      if (mounted && version == _version) {
        setState(() {
          if (answer != null) {
            _answer = answer;
            _attempted = true;
            _error = null;
          } else {
            _error = '저장 결과 미확정. 비용 미발생을 의미하지 않으며 새 생성은 하지 않습니다.';
          }
        });
      }
    } on ApiFailure catch (e) {
      if (mounted && version == _version) _fail(e);
    } finally {
      if (mounted && version == _version) setState(() => _reading = false);
    }
  }

  Future<void> _send() async {
    if (_locked || _attempted || _setup?.ready(_selected) != true) return;
    if (_isQuestion && !_form.currentState!.validate()) return;
    if (_setup?.preparedQuestion != null) {
      final check = await _runPreflight();
      if (!mounted || check?.valid != true) return;
    }
    final version = _version;
    final provider = _selected;
    final setup = _setup!;
    setState(() => _confirming = true);
    final confirm = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('AI 요청 및 비용 확인'),
        content: SingleChildScrollView(
          child: Text(
            '기능: $_label\n목표: ${widget.goalName} (ID ${widget.goalId})\n모델: ${setup.model(provider)}\n\n'
            '${_isQuestion ? '입력한 질문·과목·상황·이해도를 선택한 모델 하나에 전송합니다.' : '서버의 선택 목표 학습 기록과 저장 계획 근거가 전송됩니다.'}\n'
            '${widget.feature == AiFeature.weekly ? '최근 7일과 날짜가 명시된 FINAL을 분석하며, 과거 8084 승인이나 한도를 재사용하지 않습니다.\n' : ''}'
            '외부 API 비용이 발생할 수 있습니다. 서버의 호출 제한을 유지하며 오류나 응답 유실 시 자동 재전송하지 않습니다. 생성한 답변은 기존 저장 구조를 사용하고 FINAL 계획은 변경하지 않습니다. 요청할까요?',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          FilledButton(
            key: const Key('confirmAiRequest'),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('비용 확인 후 요청'),
          ),
        ],
      ),
    );
    if (!mounted || version != _version) return;
    setState(() => _confirming = false);
    if (confirm != true) return;
    setState(() {
      _sending = true;
      _attempted = true;
      _error = null;
    });
    try {
      final answer = await widget.api.generateAi(
        feature: widget.feature,
        goalId: widget.goalId,
        provider: provider,
        question: _question.text,
        subject: _subject.text,
        situation: _situation.text,
        understanding: _understanding,
        confirmed: true,
      );
      if (!mounted || version != _version) return;
      setState(() => _answer = answer);
      await _readHistory();
    } on ApiFailure catch (e) {
      if (mounted && version == _version) _fail(e);
    } finally {
      if (mounted && version == _version) setState(() => _sending = false);
    }
  }

  Future<void> _leave() async {
    if (_leaving || _confirming) return;
    if (_sending) {
      _leaving = true;
      final leave = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('응답을 기다리는 중입니다'),
          content: const Text(
            '화면을 나가도 서버의 AI 요청은 취소되지 않을 수 있습니다. 다시 전송하지 말고 저장 이력에서 확인하세요.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('계속 기다리기'),
            ),
            TextButton(
              key: const Key('leaveAiInFlight'),
              onPressed: () => Navigator.pop(context, true),
              child: const Text('이력에서 확인'),
            ),
          ],
        ),
      );
      _leaving = false;
      if (!mounted || leave != true) return;
    }
    if (mounted) Navigator.pop(context, AiFlowResult(feature: widget.feature));
  }

  @override
  Widget build(BuildContext context) {
    final setup = _setup, answer = _answer;
    final blocked = widget.api.aiUncertain(
      widget.feature,
      widget.goalId,
      _selected,
    );
    return PopScope<AiFlowResult>(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) _leave();
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(_label),
          leading: IconButton(
            onPressed: _confirming ? null : _leave,
            icon: const Icon(Icons.arrow_back),
          ),
        ),
        body: SafeArea(
          child: Form(
            key: _form,
            child: ListView(
              key: const Key('aiRequestScroll'),
              padding: AppDesign.screen,
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              children: [
                PageHeading(
                  compact: true,
                  eyebrow: 'AI LEARNING',
                  title: _label,
                  subtitle: '목표: ${widget.goalName} (ID ${widget.goalId})',
                  icon: Icons.auto_awesome_outlined,
                ),
                const StateNotice(
                  text: 'AI 조언은 학습 권고입니다. 규칙 기반 재조정과 FINAL 계획을 자동으로 변경하지 않습니다.',
                ),
                if (_loading || _sending || _reading)
                  const LinearProgressIndicator(),
                if (_error != null) StateNotice(text: _error!, warning: true),
                if (setup?.preparedQuestion != null)
                  SectionCard(
                    title: '승인 전용 Gemini 입력 확인',
                    children: [
                      Text(
                        '승인 목표 ID ${setup!.preparedQuestion!.goalId} · 모델 ${setup.preparedQuestion!.model}',
                      ),
                      const Text(
                        '승인값 불러오기와 사전 검증은 AI를 호출하거나 저장하지 않습니다. 검증 통과 후에도 별도 비용 확인이 필요합니다.',
                      ),
                      OutlinedButton.icon(
                        key: const Key('loadApprovedQuestion'),
                        onPressed: _locked || _attempted ? null : _loadApproved,
                        icon: const Icon(Icons.download_outlined),
                        label: const Text('승인값 불러오기'),
                      ),
                      for (final difference in _differences(
                        setup.preparedQuestion!,
                      ).values)
                        Text(difference),
                      OutlinedButton.icon(
                        key: const Key('checkApprovedQuestion'),
                        onPressed: _locked || _attempted ? null : _runPreflight,
                        icon: const Icon(Icons.fact_check_outlined),
                        label: Text(
                          _checking ? '입력 검증 중…' : '입력 사전 검증 (AI 호출 없음)',
                        ),
                      ),
                      if (_preflight != null)
                        StateNotice(
                          key: const Key('preparedQuestionResult'),
                          title: _preflight!.valid ? '사전 검증 통과' : '승인 입력 확인 필요',
                          text: _preflight!.message,
                          warning: !_preflight!.valid,
                        ),
                    ],
                  ),
                if (setup != null && isIsolatedAiMock(setup.raw))
                  const StateNotice(
                    title: '격리 Mock 테스트 · 실제 AI 아님',
                    text: '가상 테스트 데이터와 모의 응답만 사용합니다. Gemini/OpenAI에 연결하거나 비용을 발생시키지 않습니다.',
                    icon: Icons.science_outlined,
                  ),
                if (_isQuestion) ...[
                  Text(
                    '질문에 사용할 모델',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 10),
                  AiModelSelector(
                    provider: _provider,
                    onChanged: _locked || _attempted
                        ? null
                        : (p) => setState(() {
                            _provider = p;
                            _preflight = null;
                          }),
                  ),
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 12),
                    child: Text(
                      '선택한 모델 하나에만 질문합니다. 모델 선택만으로 요청하거나 기존 답변을 변경하지 않습니다.',
                    ),
                  ),
                  const SizedBox(height: 16),
                  SectionCard(
                    title: '질문 작성',
                    children: [
                      TextFormField(
                        key: const Key('aiQuestion'),
                        controller: _question,
                        enabled: !_sending && !_attempted,
                        maxLines: 5,
                        minLines: 3,
                        maxLength: 3000,
                        decoration: const InputDecoration(
                          labelText: '질문 (필수)',
                          hintText: '공부하며 궁금했던 내용을 적어 주세요.',
                          alignLabelWithHint: true,
                        ),
                        validator: (v) => v == null || v.trim().isEmpty
                            ? '질문을 입력하세요.'
                            : v.trim().length > 3000
                            ? '질문은 3000자 이내로 입력하세요.'
                            : null,
                      ),
                      const SizedBox(height: 12),
                      TextFormField(
                        key: const Key('aiQuestionSubject'),
                        controller: _subject,
                        enabled: !_sending && !_attempted,
                        maxLength: 150,
                        decoration: const InputDecoration(
                          labelText: '과목 (선택)',
                          prefixIcon: Icon(Icons.menu_book_outlined),
                        ),
                      ),
                      TextFormField(
                        key: const Key('aiQuestionSituation'),
                        controller: _situation,
                        enabled: !_sending && !_attempted,
                        maxLength: 100,
                        decoration: const InputDecoration(
                          labelText: '학습 상황 (선택)',
                          prefixIcon: Icon(Icons.edit_note_outlined),
                        ),
                      ),
                      DropdownButtonFormField<String>(
                        key: ValueKey('understanding_$_understandingVersion'),
                        initialValue: _understanding,
                        decoration: const InputDecoration(labelText: '현재 이해도'),
                        items: [
                          for (final s in ['낮음', '보통', '높음'])
                            DropdownMenuItem(value: s, child: Text(s)),
                        ],
                        onChanged: _sending || _attempted
                            ? null
                            : (v) => setState(() {
                                _understanding = v ?? '보통';
                                _preflight = null;
                              }),
                      ),
                    ],
                  ),
                ],
                if (setup != null) ...[
                  const SizedBox(height: 16),
                  PlanBadge(label: '서버 설정 모델 · ${setup.model(_selected)}'),
                  if (_isQuestion && setup.raw['goalScopedResponse'] != true)
                    const StateNotice(
                      key: Key('serverUpdateNotice'),
                      title: '서버 업데이트 필요',
                      text: '현재 서버가 최신 목표별 질문 응답 계약을 지원하지 않아 AI 생성이 차단되어 있습니다. 최신 서버 반영 후 이용하세요.',
                      icon: Icons.system_update_alt,
                    )
                  else
                    StateNotice(
                      text: setup.availability(_selected),
                      warning: !setup.ready(_selected),
                    ),
                  if (widget.feature == AiFeature.weekly)
                    SectionCard(
                      title: '최근 7일 · API 미호출 미리보기',
                      children: [
                        Text(
                          '${setup.raw['periodStart'] ?? '정보 없음'} ~ ${setup.raw['periodEnd'] ?? '정보 없음'}',
                        ),
                        Metric(
                          label: '실제 공부시간',
                          value: setup.raw['totalStudyMinutes'] is num
                              ? '${setup.raw['totalStudyMinutes']}분'
                              : '정보 없음',
                        ),
                        Metric(
                          label: '기록 수',
                          value: setup.raw['recordCount'] is num
                              ? '${setup.raw['recordCount']}건'
                              : '정보 없음',
                        ),
                        Text(
                          setup.raw['savedPlanEvidenceNotice']?.toString() ??
                              '날짜가 명시된 서버 계획 근거를 사용합니다. 미기록은 미공부가 아닙니다.',
                        ),
                      ],
                    ),
                ],
                const SizedBox(height: 16),
                if (blocked)
                  const StateNotice(
                    text: '이전 응답이 불확실하여 새 요청을 차단했습니다. 저장 이력과 호출 여부를 확인하세요.',
                    warning: true,
                  ),
                if (widget.feature == AiFeature.daily && blocked)
                  TextButton.icon(
                    key: const Key('resolveDailyAi'),
                    onPressed: _reading || _locked ? null : _resolveCost,
                    icon: const Icon(Icons.manage_search),
                    label: const Text('요청 결과 GET 확인 · 새 생성 없음'),
                  ),
                if (widget.feature == AiFeature.daily)
                  const Text(
                    '영속 비용 보호는 별도 서버 정책 승인 후 적용됩니다. OFF/미지원 서버의 응답 유실은 저장 이력과 호출 여부를 확인해야 합니다.',
                  ),
                FilledButton.icon(
                  key: const Key('sendAiRequest'),
                  onPressed:
                      _locked ||
                          _attempted ||
                          blocked ||
                          setup?.ready(_selected) != true
                      ? null
                      : _send,
                  icon: const Icon(Icons.auto_awesome_outlined),
                  label: Text(
                    _sending
                        ? '응답 생성 중…'
                        : _isQuestion
                        ? '선택한 AI에 질문하기'
                        : '코칭 생성 확인',
                  ),
                ),
                if (_attempted)
                  const Text(
                    '이 화면에서는 요청을 다시 보내지 않습니다. 응답 유실·저장 실패는 이력 조회로 확인하세요.',
                  ),
                if (!_attempted)
                  TextButton(
                    onPressed: _locked ? null : _load,
                    child: const Text('서버 설정 다시 확인'),
                  ),
                if (answer != null)
                  SectionCard(
                    title: '받은 답변',
                    children: [
                      Text(
                        '선택 목표 · ${widget.goalName}',
                        style: Theme.of(context).textTheme.labelLarge,
                      ),
                      PlanBadge(
                        label: isIsolatedAiMock(answer)
                            ? '격리 Mock 응답 · 실제 AI 아님'
                            : answer['responseSource'] == 'rule'
                            ? '규칙 기반 · 실제 AI 응답 아님'
                            : '서버가 보고한 제공업체 응답',
                      ),
                      Text('모델: ${answer['_displayModel']}'),
                      if (answer['responseCreatedAt'] != null)
                        Text(
                          '생성: ${answer['responseCreatedAt']}',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      Text(
                        answer['responseComplete'] == true
                            ? '완결 확인'
                            : answer['responseComplete'] == false
                            ? '일부 응답 · 미완결'
                            : '완결 정보 없음',
                      ),
                      if (_isQuestion)
                        Text(
                          '질문: ${answer['question']}',
                          style: Theme.of(context).textTheme.titleSmall,
                        ),
                      const Divider(height: 1),
                      AnswerText(text: answer['_text'] as String),
                      Text(
                        answer['responseSaved'] == true
                            ? '서버 저장 응답 확인 · ID ${answer['responseId']}'
                            : '영구 저장 성공 미확인',
                      ),
                      if (answer['saveWarning'] is String)
                        StateNotice(
                          text: answer['saveWarning'] as String,
                          warning: true,
                        ),
                    ],
                  ),
                const SizedBox(height: 18),
                Text(
                  '이전 AI 응답 기록',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const SizedBox(height: 8),
                OutlinedButton.icon(
                  key: const Key('reloadAiRequestHistory'),
                  onPressed: _reading ? null : _readHistory,
                  icon: const Icon(Icons.history),
                  label: const Text('저장 질문·코칭 이력 조회 (API 미호출)'),
                ),
                if (_historyError != null)
                  StateNotice(text: _historyError!, warning: true),
                if (_history != null && _history!.isEmpty)
                  const StateNotice(
                    text: '이 목표의 저장 응답이 없습니다. 생성·저장 실패 여부를 임의로 단정하지 않습니다.',
                  ),
                if (_history != null)
                  for (final row in _history!)
                    AiSavedAnswerCard(
                      row: row,
                      title: '저장 응답 #${row['responseId']}',
                    ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
