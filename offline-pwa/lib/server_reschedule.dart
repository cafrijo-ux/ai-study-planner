import 'package:flutter/material.dart';

import 'app_design.dart';
import 'planner_api.dart';
import 'plan_calendar.dart';

class RescheduleResult {
  const RescheduleResult({this.date, this.expired = false});
  final String? date;
  final bool expired;
}

/// 기존 서버의 '내일' 미리보기와 명시 저장만 사용한다. 임의 날짜 계산은 하지 않는다.
class ServerReschedule extends StatefulWidget {
  const ServerReschedule({
    super.key,
    required this.api,
    required this.goalId,
    required this.goalName,
  });
  final PlannerApi api;
  final int goalId;
  final String goalName;

  @override
  State<ServerReschedule> createState() => _ServerRescheduleState();
}

class _ServerRescheduleState extends State<ServerReschedule> {
  Map<String, dynamic>? _preview, _stored, _ack;
  bool _busy = true,
      _confirming = false,
      _checked = false,
      _attempted = false,
      _uncertain = false;
  String? _error, _notice, _changedDate;
  DateTime? _calendarMonth, _calendarDay;
  bool _showStored = false;
  String? get _date => _preview?['scheduleDate'] as String?;
  bool get _working => _busy || _confirming;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant ServerReschedule oldWidget) {
    super.didUpdateWidget(oldWidget);
    // 일반적으로 독립 경로지만 키를 재사용해도 다른 목표의 응답을 표시하지 않는다.
    if (oldWidget.goalId != widget.goalId) {
      _generation++;
      _preview = _stored = _ack = null;
      _attempted = _uncertain = _checked = false;
      _confirming = false;
      _changedDate = null;
      _calendarMonth = _calendarDay = null;
      _showStored = false;
      _load();
    }
  }

  int _generation = 0;
  Future<void> _load() async {
    final generation = ++_generation;
    final goal = widget.goalId;
    setState(() {
      _busy = true;
      _error = _notice = null;
      _checked = false;
      _preview = _stored = null;
    });
    try {
      final preview = await widget.api.reschedulePreview(goal);
      if (!mounted || generation != _generation) return;
      final calendar = CalendarPlan(
        preview,
        goalId: goal,
        source: CalendarPlanSource.preview,
      );
      setState(() {
        _preview = preview;
        _calendarDay = calendar.date;
        _calendarMonth = DateTime(calendar.date.year, calendar.date.month);
        _showStored = false;
        _uncertain = widget.api.rescheduleUncertain(
          goal,
          preview['scheduleDate'] as String,
        );
      });
      final stored = await widget.api.storedPlan(
        goalId: goal,
        date: preview['scheduleDate'] as String,
      );
      if (!mounted || generation != _generation) return;
      if (stored['saved'] == true) {
        CalendarPlan(stored, goalId: goal, source: CalendarPlanSource.saved);
      }
      setState(() {
        _stored = stored;
        _checked = true;
      });
    } on ApiFailure catch (e) {
      if (mounted && generation == _generation) _failure(e);
    } finally {
      if (mounted && generation == _generation) setState(() => _busy = false);
    }
  }

  void _failure(ApiFailure e) {
    if (e.status == 401 || e.status == 403) {
      widget.api.clear();
      Navigator.of(context).pop(const RescheduleResult(expired: true));
      return;
    }
    setState(() => _error = e.message);
  }

  Future<void> _save() async {
    if (_working ||
        !_checked ||
        _preview == null ||
        _attempted ||
        _uncertain ||
        _showStored ||
        _calendarDay == null ||
        planDateKey(_calendarDay!) != _date) {
      return;
    }
    final date = _date!;
    final goal = widget.goalId;
    final preview = _preview!;
    final generation = _generation;
    setState(() => _confirming = true);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('재조정 계획 저장 확인'),
        content: SingleChildScrollView(
          child: Text(
            '목표: ${widget.goalName} (ID $goal)\n대상 날짜: $date\n'
            '보충 ${preview['carryoverMinutes']}분 · 신규 ${preview['newStudyMinutes']}분\n\n'
            '${_stored?['saved'] == true ? '기존 ${_stored?['planStatus']} 저장 계획을 덮어쓸 수 있습니다.\n' : '현재 조회된 저장 계획은 없습니다.\n'}'
            '같은 목표·날짜의 저장본은 갱신될 수 있습니다.\n'
            '서버는 저장 시 최신 기록으로 다시 계산하므로 시간 배정이 달라질 수 있습니다. '
            'DRAFT/FINAL 상태도 서버가 결정합니다. 저장할까요?',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          FilledButton(
            key: const Key('confirmRescheduleSave'),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('저장 확인'),
          ),
        ],
      ),
    );
    if (!mounted || generation != _generation) return;
    setState(() => _confirming = false);
    if (confirmed != true) return;
    setState(() {
      _busy = true;
      _attempted = true;
      _error = _notice = null;
    });
    try {
      final ack = await widget.api.saveReschedule(goalId: goal, date: date);
      if (!mounted || generation != _generation) return;
      _ack = ack;
      _changedDate = date;
      await _readStored(date, goal, generation);
    } on RescheduleSaveFailure catch (e) {
      if (!mounted || generation != _generation) return;
      setState(() => _uncertain = e.uncertain);
      _failure(e);
      if (e.uncertain && mounted) await _readStored(date, goal, generation);
    } finally {
      if (mounted && generation == _generation) setState(() => _busy = false);
    }
  }

  Future<void> _readStored(String date, int goal, int generation) async {
    try {
      final stored = await widget.api.storedPlan(goalId: goal, date: date);
      if (!mounted || generation != _generation) return;
      if (stored['saved'] == true) {
        CalendarPlan(stored, goalId: goal, source: CalendarPlanSource.saved);
      }
      setState(() {
        _stored = stored;
        _checked = true;
        if (_ack != null && stored['saved'] == true) {
          final matches = [
            'planStatus',
            'studyMinutes',
            'carryoverMinutes',
            'newStudyMinutes',
          ].every((key) => stored[key] == _ack![key]);
          _notice = matches
              ? '저장 성공 · 서버 저장본을 GET으로 재조회했습니다.'
              : '저장 응답과 재조회 값이 다릅니다. 추가 저장 없이 확인하세요.';
        } else if (_ack != null) {
          _notice = '저장 응답은 받았지만 저장본이 조회되지 않습니다. 추가 저장 없이 확인하세요.';
        } else if (!_attempted && !_uncertain) {
          _notice = stored['saved'] == true
              ? '현재 목표·날짜의 저장본을 조회했습니다.'
              : '현재 저장 계획이 없습니다.';
        } else {
          _notice = stored['saved'] == true
              ? '저장본을 조회했습니다. 이번 요청의 저장 성공 여부는 확정하지 않습니다. 다시 저장하지 마세요.'
              : '현재 저장본이 없습니다. 응답 유실 후에는 다시 저장하지 말고 확인을 요청하세요.';
        }
      });
    } on ApiFailure catch (e) {
      if (!mounted || generation != _generation) return;
      if (_ack != null) _notice = '저장 응답은 받았지만 저장본 재조회에 실패했습니다. 다시 저장하지 마세요.';
      _failure(e);
    }
  }

  Future<void> _checkStored() async {
    if (_working || _date == null) return;
    final generation = _generation;
    setState(() {
      _busy = true;
      _error = null;
    });
    await _readStored(_date!, widget.goalId, generation);
    if (mounted && generation == _generation) setState(() => _busy = false);
  }

  void _leave() {
    if (_working) return;
    Navigator.pop(
      context,
      RescheduleResult(
        date: _changedDate ?? ((_attempted || _uncertain) ? _date : null),
      ),
    );
  }

  Widget _metrics(Map<String, dynamic> p) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      if (p['carryoverTotalPendingMinutes'] is num)
        Metric(
          label: '전체 미완료 학습',
          value: '${p['carryoverTotalPendingMinutes']}분',
        ),
      Metric(label: '실제 보충 배정', value: '${p['carryoverMinutes']}분'),
      Metric(label: '신규 학습 배정', value: '${p['newStudyMinutes']}분'),
      Metric(label: '총 공부 배정', value: '${p['studyMinutes']}분'),
      const SizedBox(height: 16),
      AllocationBar(
        catchup: p['carryoverMinutes'] as num?,
        newStudy: p['newStudyMinutes'] as num?,
        total: p['studyMinutes'] as num?,
      ),
    ],
  );

  @override
  Widget build(BuildContext context) => PopScope<RescheduleResult>(
    canPop: false,
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop) _leave();
    },
    child: Scaffold(
      appBar: AppBar(
        title: const Text('내일 자동 재조정'),
        leading: IconButton(
          key: const Key('leaveReschedule'),
          onPressed: _working ? null : _leave,
          icon: const Icon(Icons.arrow_back),
        ),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: AppDesign.screen,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              PageHeading(
                compact: true,
                eyebrow: 'REBALANCE',
                title: '남은 공부를, 무리 없이',
                subtitle: '목표: ${widget.goalName} (ID ${widget.goalId})',
                icon: Icons.tune_rounded,
              ),
              const StateNotice(
                text: '규칙 기반 미리보기 · 아직 저장되지 않았습니다. 부족한 시간 전체와 실제 보충 배정은 다를 수 있습니다.',
              ),
              if (_busy)
                const Padding(
                  padding: EdgeInsets.all(16),
                  child: LinearProgressIndicator(),
                ),
              if (_error != null) StateNotice(text: _error!, warning: true),
              if (_notice != null)
                StateNotice(
                  text: _notice!,
                  icon: _notice!.startsWith('저장 성공')
                      ? Icons.check_circle_outline
                      : Icons.info_outline,
                ),
              if (_preview case final p?) ...[
                PlanMonthCalendar(
                  key: ValueKey(widget.goalId),
                  month: _calendarMonth!,
                  selected: _calendarDay!,
                  plans: [
                    CalendarPlan(
                      p,
                      goalId: widget.goalId,
                      source: CalendarPlanSource.preview,
                    ),
                    if (_stored?['saved'] == true)
                      CalendarPlan(
                        _stored!,
                        goalId: widget.goalId,
                        source: CalendarPlanSource.saved,
                      ),
                  ],
                  coverageComplete: false,
                  onDay: (day) => setState(() {
                    _calendarDay = day;
                    _calendarMonth = DateTime(day.year, day.month);
                  }),
                  onMonth: (month) => setState(() {
                    _calendarMonth = month;
                    _calendarDay = month;
                  }),
                ),
                if (planDateKey(_calendarDay!) != _date)
                  const StateNotice(
                    text: '이 날짜는 조회한 일정이 없습니다. 재조정 미리보기는 서버가 지정한 내일 날짜만 제공합니다.',
                  ),
                SectionCard(
                  title: '미저장 재조정 미리보기',
                  children: [
                    const PlanBadge(label: '미리보기 · 미저장'),
                    Text('서버 대상 날짜: ${p['scheduleDate']}'),
                    _metrics(p),
                    if (p['previewNotice'] is String)
                      Text(p['previewNotice'] as String),
                    if (p['rescheduleNotice'] is String)
                      Text(p['rescheduleNotice'] as String),
                    if (p['timeWindowNotice'] is String)
                      Text(p['timeWindowNotice'] as String),
                    if ((p['carryoverCalculation'] as List?)?.any(
                          (s) => s is Map && s['provisional'] == true,
                        ) ==
                        true)
                      const Text('공부 종료 전 기록의 잠정 계산이 포함되어 있습니다.'),
                    const Text(
                      '저장하려면 오늘의 시간 설정이 서버에 저장되어 있어야 합니다. 저장 시 최신 기록으로 다시 계산됩니다.',
                    ),
                  ],
                ),
                if (planDateKey(_calendarDay!) == _date) ...[
                  if (_stored?['saved'] == true)
                    Wrap(
                      spacing: 8,
                      children: [
                        ChoiceChip(
                          label: const Text('미리보기 · 미저장'),
                          selected: !_showStored,
                          onSelected: (_) =>
                              setState(() => _showStored = false),
                        ),
                        ChoiceChip(
                          label: Text('${_stored!['planStatus']} · 서버 저장본'),
                          selected: _showStored,
                          onSelected: (_) => setState(() => _showStored = true),
                        ),
                      ],
                    ),
                  SectionCard(
                    title: _showStored ? '저장본 시간표 · 계산 미리보기와 별도' : '미리보기 시간표',
                    children: [
                      PlanScheduleDetails(
                        plan: CalendarPlan(
                          _showStored && _stored?['saved'] == true
                              ? _stored!
                              : p,
                          goalId: widget.goalId,
                          source: _showStored
                              ? CalendarPlanSource.saved
                              : CalendarPlanSource.preview,
                        ),
                        goalName: widget.goalName,
                      ),
                    ],
                  ),
                ],
                Text(
                  _stored?['saved'] == true
                      ? '현재 저장본: ${_stored?['planStatus']} · 저장 시 덮어쓰기 가능'
                      : (_checked
                            ? '현재 저장 계획 없음'
                            : '기존 저장본 확인 전에는 저장할 수 없습니다.'),
                ),
                FilledButton.icon(
                  key: const Key('saveReschedule'),
                  onPressed:
                      _working ||
                          !_checked ||
                          _attempted ||
                          _uncertain ||
                          _showStored ||
                          planDateKey(_calendarDay!) != _date
                      ? null
                      : _save,
                  icon: const Icon(Icons.save_outlined),
                  label: Text(
                    _busy && _attempted ? '저장·재조회 확인 중…' : '미리보기 확인 후 명시적으로 저장',
                  ),
                ),
                if (_attempted || _uncertain)
                  const Text('이 화면에서 저장 요청을 다시 전송하지 않습니다. 저장본만 조회하세요.'),
                OutlinedButton(
                  key: const Key('checkRescheduleStored'),
                  onPressed: _working ? null : _checkStored,
                  child: const Text('저장본 GET으로 확인'),
                ),
                if (_stored?['saved'] == true)
                  SectionCard(
                    title: '서버 저장본 · ${_stored?['planStatus']}',
                    children: [
                      Text(
                        '날짜: ${_stored?['planDate'] ?? _stored?['scheduleDate']}',
                      ),
                      PlanBadge(
                        label:
                            '${_stored?['planStatus'] ?? '상태 정보 없음'} · 서버 저장본',
                      ),
                      _metrics(_stored!),
                      if (_stored?['warning'] is String)
                        Text(_stored!['warning'] as String),
                    ],
                  ),
              ],
              if (!_attempted && !_uncertain)
                TextButton(
                  key: const Key('reloadReschedulePreview'),
                  onPressed: _working ? null : _load,
                  child: const Text('미리보기와 기존 저장본 다시 조회'),
                ),
            ],
          ),
        ),
      ),
    ),
  );
}
