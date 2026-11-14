import 'package:flutter/material.dart';

import 'app_design.dart';
import 'planner_api.dart';
import 'study_record_entry.dart';
import 'server_reschedule.dart';
import 'plan_calendar.dart';

String _koreanDate(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

String _value(dynamic v) =>
    v == null || v.toString().trim().isEmpty ? '정보 없음' : v.toString();

class ServerStudyPlan extends StatefulWidget {
  const ServerStudyPlan({
    super.key,
    required this.api,
    required this.goalId,
    required this.goalName,
    this.onSessionExpired,
  });
  final PlannerApi api;
  final int goalId;
  final String goalName;
  final VoidCallback? onSessionExpired;

  @override
  State<ServerStudyPlan> createState() => _ServerStudyPlanState();
}

class _ServerStudyPlanState extends State<ServerStudyPlan> {
  DateTime _date = seoulToday();
  late DateTime _month = DateTime(_date.year, _date.month);
  final _monthCache = <String, List<CalendarPlan>>{};
  final _pendingMonths = <String, Future<List<Map<String, dynamic>>>>{};
  List<CalendarPlan> _monthPlans = [];
  bool _monthBusy = false, _monthComplete = false, _monthUnsupported = false;
  String? _monthError;
  int _monthSeq = 0;
  Map<String, dynamic>? _plan;
  bool _busy = false;
  bool _isSaved = true;
  String? _error;
  String? _notice;
  int _seq = 0;

  @override
  void initState() {
    super.initState();
    _load(saved: true);
    _loadMonth();
  }

  @override
  void didUpdateWidget(covariant ServerStudyPlan oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.goalId != widget.goalId) {
      _monthSeq++;
      _monthCache.clear();
      _pendingMonths.clear();
      _monthPlans = [];
      _monthComplete = false;
      _load(saved: true);
      _loadMonth();
    }
  }

  Future<void> _openReschedule() async {
    if (_busy) return;
    final result = await Navigator.of(context).push<RescheduleResult>(
      MaterialPageRoute(
        builder: (_) => ServerReschedule(
          api: widget.api,
          goalId: widget.goalId,
          goalName: widget.goalName,
        ),
      ),
    );
    if (!mounted || result == null) return;
    if (result.expired) {
      widget.api.clear();
      if (widget.onSessionExpired != null) {
        widget.onSessionExpired!();
      } else {
        Navigator.of(context).pop(true);
      }
      return;
    }
    if (result.date != null) {
      setState(() {
        _date = parsePlanDate(result.date!);
        _month = DateTime(_date.year, _date.month);
      });
      _monthCache.remove('${widget.goalId}/${planMonthKey(_month)}');
      await _load(saved: true);
      await _loadMonth(force: true);
    }
  }

  Future<void> _chooseDate() async {
    final date = await showDatePicker(
      context: context,
      initialDate: _date,
      firstDate: DateTime(1970),
      lastDate: DateTime(2100, 12, 31),
      helpText: '학습 계획 날짜 선택',
    );
    if (date != null && mounted) {
      _selectDay(date);
    }
  }

  Future<void> _loadMonth({bool force = false}) async {
    final seq = ++_monthSeq;
    final goal = widget.goalId, month = planMonthKey(_month);
    final key = '$goal/$month';
    if (_monthUnsupported && !force) {
      setState(() {
        _monthPlans = [];
        _monthComplete = false;
        _monthBusy = false;
      });
      return;
    }
    if (!force && _monthCache.containsKey(key)) {
      setState(() {
        _monthPlans = _monthCache[key]!;
        _monthComplete = true;
        _monthBusy = false;
        _monthError = null;
      });
      return;
    }
    setState(() {
      _monthBusy = true;
      _monthPlans = [];
      _monthComplete = false;
      _monthError = null;
    });
    if (force) _pendingMonths.remove(key);
    final future = _pendingMonths.putIfAbsent(
      key,
      () => widget.api.savedPlanMonth(goalId: goal, month: month),
    );
    try {
      final raw = await future;
      final plans = raw
          .map(
            (p) =>
                CalendarPlan(p, goalId: goal, source: CalendarPlanSource.saved),
          )
          .toList();
      if (!mounted || seq != _monthSeq || goal != widget.goalId) return;
      if (_monthCache.length >= 6) _monthCache.remove(_monthCache.keys.first);
      _monthCache[key] = plans;
      setState(() {
        _monthPlans = plans;
        _monthComplete = true;
        _monthUnsupported = false;
      });
    } on ApiFailure catch (e) {
      if (!mounted || seq != _monthSeq) return;
      if (e.status == 401) {
        _sessionExpired();
        return;
      }
      setState(() {
        if (e.status == 404 || e.status == 501) {
          _monthUnsupported = true;
        } else {
          _monthError = e.message;
        }
      });
    } finally {
      if (identical(_pendingMonths[key], future)) _pendingMonths.remove(key);
      if (mounted && seq == _monthSeq) setState(() => _monthBusy = false);
    }
  }

  void _sessionExpired() {
    widget.api.clear();
    if (widget.onSessionExpired != null) {
      widget.onSessionExpired!();
    } else {
      Navigator.of(context).pop(true);
    }
  }

  void _selectDay(DateTime date) {
    final changed = planMonthKey(date) != planMonthKey(_month);
    setState(() {
      _date = DateTime(date.year, date.month, date.day);
      _month = DateTime(date.year, date.month);
    });
    if (changed) _loadMonth();
    final stored = _monthPlans
        .where((p) => p.dateKey == planDateKey(_date))
        .firstOrNull;
    if (_monthComplete && !changed) {
      ++_seq;
      setState(() {
        _busy = false;
        _isSaved = true;
        _error = null;
        _plan = stored?.data;
        _notice = stored == null
            ? '선택 목표(ID ${widget.goalId})의 ${planDateKey(_date)} 저장 계획이 없습니다.'
            : null;
      });
    } else {
      _load(saved: true);
    }
  }

  void _moveMonth(DateTime month) {
    if (planMonthKey(month) == planMonthKey(_month)) return;
    setState(() {
      _month = month;
      _date = month;
      _plan = null;
      _notice = null;
      _error = null;
      _busy = false;
    });
    ++_seq;
    _loadMonth();
  }

  Future<void> _load({required bool saved}) async {
    final seq = ++_seq;
    final date = _koreanDate(_date);
    setState(() {
      _busy = true;
      _error = null;
      _notice = null;
      _plan = null;
      _isSaved = saved;
    });
    try {
      final data = saved
          ? await widget.api.storedPlan(goalId: widget.goalId, date: date)
          : await widget.api.calculatedPlan(goalId: widget.goalId, date: date);
      if (!mounted || seq != _seq) return;
      if (!(saved && data['saved'] == false)) {
        CalendarPlan(
          data,
          goalId: widget.goalId,
          source: saved || data['planSaved'] == true
              ? CalendarPlanSource.saved
              : CalendarPlanSource.calculated,
        );
      }
      setState(() {
        _busy = false;
        if (saved && data['saved'] == false) {
          _notice = '선택 목표(ID ${widget.goalId})의 $date 저장 계획이 없습니다.';
        } else {
          _plan = data;
        }
      });
    } on ApiFailure catch (e) {
      if (!mounted || seq != _seq) return;
      if (e.status == 401 || e.status == 403) {
        widget.api.clear();
        if (widget.onSessionExpired != null) {
          widget.onSessionExpired!();
        } else {
          Navigator.of(context).pop(true);
        }
        return;
      }
      setState(() {
        _busy = false;
        _error = e.message;
      });
    }
  }

  String _planStatus(Map<String, dynamic> plan) {
    if (!_isSaved) return '서버 계획 조회 · 저장본 또는 미저장 계산 결과';
    final status = plan['planStatus']?.toString();
    switch (status) {
      case 'FINAL':
        return '저장된 FINAL 계획';
      case 'DRAFT':
        return '저장된 DRAFT 계획 · 최종 계획 아님';
      default:
        return '저장된 계획 · 상태 미표기';
    }
  }

  @override
  Widget build(BuildContext context) {
    final plan = _plan;
    final calendarPlans = [..._monthPlans];
    if (plan != null) {
      final current = CalendarPlan(
        plan,
        goalId: widget.goalId,
        source: _isSaved || plan['planSaved'] == true
            ? CalendarPlanSource.saved
            : CalendarPlanSource.calculated,
      );
      calendarPlans.removeWhere(
        (p) => p.dateKey == current.dateKey && p.source == current.source,
      );
      calendarPlans.add(current);
    }
    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: widget.onSessionExpired == null,
        title: const Text('학습 계획 · 실제 조회'),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: AppDesign.screen,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              PageHeading(
                compact: true,
                eyebrow: 'STUDY PLAN',
                title: '하루를 만드는 계획',
                subtitle: '목표: ${widget.goalName} (ID ${widget.goalId})',
                icon: Icons.calendar_month_outlined,
              ),
              const Text('날짜별 저장본을 확인하거나 내일 계획을 조정하세요.'),
              const SizedBox(height: 16),
              PlanMonthCalendar(
                key: ValueKey(widget.goalId),
                month: _month,
                selected: _date,
                plans: calendarPlans,
                coverageComplete: _monthComplete,
                onDay: _selectDay,
                onMonth: _moveMonth,
              ),
              if (_monthBusy) const LinearProgressIndicator(),
              if (_monthUnsupported)
                const StateNotice(
                  text: '월간 조회를 지원하지 않는 서버입니다. 선택 날짜의 저장본만 표시합니다. 나머지 날짜는 미조회입니다.',
                ),
              if (_monthError != null)
                StateNotice(text: '월간 조회 실패: $_monthError', warning: true),
              TextButton(
                key: const Key('reloadPlanMonth'),
                onPressed: _monthBusy ? null : () => _loadMonth(force: true),
                child: const Text('이번 달 저장본 새로고침'),
              ),
              if (_monthComplete && _plan == null && _notice == null && !_busy)
                const StateNotice(
                  text: '날짜를 선택해 저장된 시간표를 확인하세요. 일정이 없는 날짜에는 새 계획을 만들지 않습니다.',
                ),
              OutlinedButton.icon(
                onPressed: _busy ? null : _chooseDate,
                icon: const Icon(Icons.date_range),
                label: Text('계획 날짜 선택: ${_koreanDate(_date)}'),
              ),
              const SizedBox(height: 8),
              FilledButton.icon(
                key: const Key('storedPlanButton'),
                onPressed: _busy ? null : () => _load(saved: true),
                icon: const Icon(Icons.storage_outlined),
                label: const Text('해당 날짜의 저장된 계획 조회'),
              ),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                key: const Key('calculatedPlanButton'),
                onPressed: _busy ? null : () => _load(saved: false),
                icon: const Icon(Icons.calculate_outlined),
                label: const Text('서버 계획 조회 (저장본 우선·없으면 계산)'),
              ),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                key: const Key('openReschedulePreview'),
                onPressed: _busy ? null : _openReschedule,
                icon: const Icon(Icons.update),
                label: const Text('내일 자동 재조정 미리보기'),
              ),
              if (_busy)
                const Padding(
                  padding: EdgeInsets.all(18),
                  child: LinearProgressIndicator(),
                ),
              if (_error != null) ...[
                const SizedBox(height: 12),
                StateNotice(text: _error!, warning: true),
              ],
              if (_notice != null) ...[
                const SizedBox(height: 16),
                SectionCard(
                  title: '선택 목표의 저장 계획 없음',
                  children: [
                    Text(_notice!),
                    const Text('새 계획을 생성하거나 기존 저장 계획을 변경하지 않았습니다.'),
                  ],
                ),
              ],
              if (plan != null) ...[
                const SizedBox(height: 16),
                SectionCard(
                  title: _planStatus(plan),
                  children: [
                    PlanBadge(
                      label: _isSaved || plan['planSaved'] == true
                          ? '${plan['planStatus'] ?? '저장본'}'
                          : '미저장 계산',
                    ),
                    Text('대상 날짜: ${_koreanDate(_date)}'),
                    Metric(
                      label: '학습 배정',
                      value: '${_value(plan['studyMinutes'])}분',
                    ),
                    Metric(
                      label: '보충 배정',
                      value: '${_value(plan['carryoverMinutes'])}분',
                    ),
                    Metric(
                      label: '신규 학습',
                      value: '${_value(plan['newStudyMinutes'])}분',
                    ),
                    AllocationBar(
                      catchup: plan['carryoverMinutes'] as num?,
                      newStudy: plan['newStudyMinutes'] as num?,
                      total: plan['studyMinutes'] as num?,
                    ),
                    if (plan['breakMinutes'] is num)
                      Metric(label: '휴식', value: '${plan['breakMinutes']}분'),
                    const Text('보충 배정은 실제 공부 완료를 뜻하지 않습니다.'),
                    if (!_isSaved && plan['planSaved'] != true)
                      const Text('현재 계산된 계획입니다. 저장된 계획이 아닙니다.'),
                    if (plan['savedPlanNotice'] != null)
                      Text(_value(plan['savedPlanNotice'])),
                    if (plan['warning'] != null) Text(_value(plan['warning'])),
                    if (plan['rescheduleNotice'] != null)
                      Text(_value(plan['rescheduleNotice'])),
                  ],
                ),
                SectionCard(
                  title: '선택 날짜 상세 시간표',
                  children: [
                    PlanScheduleDetails(
                      plan: CalendarPlan(
                        plan,
                        goalId: widget.goalId,
                        source: _isSaved || plan['planSaved'] == true
                            ? CalendarPlanSource.saved
                            : CalendarPlanSource.calculated,
                      ),
                      goalName: widget.goalName,
                    ),
                  ],
                ),
                if (plan['reason'] != null)
                  SectionCard(
                    title: '배정 이유',
                    children: [Text(_value(plan['reason']))],
                  ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class ServerStudyAnalysis extends StatefulWidget {
  const ServerStudyAnalysis({
    super.key,
    required this.api,
    required this.goalId,
    required this.goalName,
    this.onSessionExpired,
    this.onRecordsChanged,
  });
  final PlannerApi api;
  final int goalId;
  final String goalName;
  final VoidCallback? onSessionExpired;
  final VoidCallback? onRecordsChanged;
  @override
  State<ServerStudyAnalysis> createState() => _ServerStudyAnalysisState();
}

class _ServerStudyAnalysisState extends State<ServerStudyAnalysis> {
  Map<String, dynamic>? _data;
  List<Map<String, dynamic>>? _records;
  String? _recordsError;
  bool _busy = false;
  String? _error;
  int _request = 0;

  Future<void> _enterStudy() async {
    final result = await Navigator.of(context).push<StudyEntryResult>(
      MaterialPageRoute(
        builder: (_) => StudyRecordEntry(
          api: widget.api,
          goalId: widget.goalId,
          goalName: widget.goalName,
        ),
      ),
    );
    if (!mounted) return;
    if (result == StudyEntryResult.expired) {
      widget.api.clear();
      if (widget.onSessionExpired != null) {
        widget.onSessionExpired!();
      } else {
        Navigator.of(context).pop(true);
      }
      return;
    }
    if (result == StudyEntryResult.saved ||
        result == StudyEntryResult.uncertain) {
      if (result == StudyEntryResult.saved) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('서버에서 공부 기록 저장을 확인했습니다.')));
      }
      widget.onRecordsChanged?.call();
      await _refresh();
    }
  }

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  void didUpdateWidget(covariant ServerStudyAnalysis oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.goalId != widget.goalId || oldWidget.api != widget.api) {
      _refresh();
    }
  }

  Future<void> _refresh() async {
    final request = ++_request, goal = widget.goalId;
    final api = widget.api;
    setState(() {
      _busy = true;
      _error = null;
      _data = null;
      _records = null;
      _recordsError = null;
    });
    try {
      final data = await api.summary(goal);
      if (!mounted || request != _request) return;
      setState(() {
        _data = data;
      });
      try {
        final records = await api.studyRecords(goal);
        if (!mounted || request != _request) return;
        setState(() => _records = records);
      } on ApiFailure catch (e) {
        if (!mounted || request != _request) return;
        if (e.status == 401 || e.status == 403) rethrow;
        setState(() => _recordsError = e.message);
      }
      if (mounted && request == _request) setState(() => _busy = false);
    } on ApiFailure catch (e) {
      if (!mounted || request != _request) return;
      if (e.status == 401 || e.status == 403) {
        widget.api.clear();
        if (widget.onSessionExpired != null) {
          widget.onSessionExpired!();
        } else {
          Navigator.of(context).pop(true);
        }
        return;
      }
      setState(() {
        _error = e.message;
        _busy = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final data = _data;
    final days = <String, List<Map<String, dynamic>>>{};
    for (final record in _records ?? <Map<String, dynamic>>[]) {
      days.putIfAbsent(record['studyDate'] as String, () => []).add(record);
    }
    final correct = data?['totalCorrect'];
    final wrong = data?['totalWrong'];
    final count =
        measuredNumber(correct) != null &&
            measuredNumber(wrong) != null &&
            correct >= 0 &&
            wrong >= 0
        ? correct + wrong
        : 0;
    final accuracy = count > 0
        ? '${(correct / count * 100).toStringAsFixed(1)}%'
        : '정보 없음';
    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: widget.onSessionExpired == null,
        title: const Text('학습 분석 · 실제 조회'),
        actions: [
          IconButton(
            key: const Key('analysisStudyEntry'),
            onPressed: _busy ? null : _enterStudy,
            tooltip: '공부 기록 입력',
            icon: const Icon(Icons.edit_note),
          ),
          IconButton(
            onPressed: _busy ? null : _refresh,
            tooltip: '새로고침',
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          key: const Key('analysisScroll'),
          padding: AppDesign.screen,
          children: [
            PageHeading(
              compact: true,
              eyebrow: 'LEARNING INSIGHTS',
              title: '기록에서 찾는 나의 리듬',
              subtitle: '선택 목표: ${widget.goalName} (ID ${widget.goalId})',
              icon: Icons.insights_outlined,
            ),
            FilledButton.icon(
              key: const Key('analysisAddRecord'),
              onPressed: _busy ? null : _enterStudy,
              icon: const Icon(Icons.add),
              label: const Text('공부 기록 남기기'),
            ),
            const SizedBox(height: 16),
            if (_busy)
              const Padding(
                padding: EdgeInsets.all(18),
                child: Column(
                  children: [
                    LinearProgressIndicator(),
                    SizedBox(height: 10),
                    Text('선택 목표의 학습 데이터를 조회하고 있습니다.'),
                  ],
                ),
              ),
            if (_error != null) ...[
              const SizedBox(height: 16),
              StateNotice(text: _error!, warning: true),
              OutlinedButton(onPressed: _refresh, child: const Text('다시 조회')),
            ],
            if (data != null) ...[
              const SizedBox(height: 18),
              _AnalysisReveal(
                key: ValueKey('analysis-summary/${widget.goalId}/$_request'),
                child: Column(
                  key: const Key('analysisSummary'),
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      '현재 목표 · 실제 학습 누적',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 12),
                    MetricGrid(
                      items: [
                        (
                          '공부 기록',
                          _analysisNumber(data['recordCount'], '건'),
                          Icons.edit_note,
                        ),
                        (
                          '누적 공부시간',
                          _analysisNumber(data['totalStudyMinutes'], '분'),
                          Icons.timer_outlined,
                        ),
                        (
                          '평균 이해도',
                          _analysisScore(data['averageUnderstanding']),
                          Icons.psychology_outlined,
                        ),
                        (
                          '평균 집중도',
                          _analysisScore(data['averageFocus']),
                          Icons.center_focus_strong_outlined,
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Metric(label: '정답률', value: accuracy),
                    if (data['mainMessage'] is String)
                      Text(data['mainMessage'] as String),
                  ],
                ),
              ),
              const SizedBox(height: 20),
              SectionCard(
                title: '기록 범위 안내',
                children: [
                  const Text(
                    '누적 통계는 서버 계산값입니다. 아래 목록은 선택 목표의 과목에 연결된 실제 기록이며, 기록이 있는 날짜만 표시합니다.',
                  ),
                  if (data['message'] != null) Text(_value(data['message'])),
                ],
              ),
              if (_records != null && _records!.isNotEmpty)
                _AnalysisSubjects(records: _records!),
              if (_recordsError != null)
                SectionCard(
                  title: '날짜별 기록 조회 오류',
                  children: [
                    Text(_recordsError!),
                    OutlinedButton(
                      onPressed: _busy ? null : _refresh,
                      child: const Text('기록 다시 조회'),
                    ),
                  ],
                ),
              if (_records != null && _records!.isEmpty)
                const SectionCard(
                  title: '날짜별 공부 기록',
                  children: [Text('공부 기록 없음')],
                ),
              for (final day in days.entries)
                SectionCard(
                  key: ValueKey('analysis-day-${day.key}'),
                  title: day.key,
                  children: [
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        PlanBadge(label: '기록 ${day.value.length}건'),
                        PlanBadge(
                          label: _analysisMinutes(day.value) == null
                              ? '공부시간 정보 일부 없음'
                              : '하루 ${_analysisMinutes(day.value)}분',
                        ),
                      ],
                    ),
                    for (final record in day.value)
                      _AnalysisRecord(
                        key: ValueKey('analysis-record-${record['logId']}'),
                        record: record,
                      ),
                  ],
                ),
            ],
          ],
        ),
      ),
    );
  }
}

String _analysisNumber(dynamic value, String unit) {
  final n = measuredNumber(value);
  return n != null && n >= 0 ? '$n$unit' : '정보 없음';
}

String _analysisScore(dynamic value) {
  final n = measuredNumber(value);
  return n != null && n > 0 && n <= 5 ? '${n.toStringAsFixed(1)} / 5' : '정보 없음';
}

// Only summarize measured records. A missing duration never becomes a zero.
num? _analysisMinutes(List<Map<String, dynamic>> records) {
  num total = 0;
  for (final record in records) {
    final n = measuredNumber(record['studyMinutes']);
    if (n == null || n < 0) return null;
    total += n;
  }
  return total;
}

class _AnalysisReveal extends StatelessWidget {
  const _AnalysisReveal({super.key, required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => TweenAnimationBuilder<double>(
    tween: Tween(begin: 0, end: 1),
    duration: AppDesign.motion(context),
    curve: AppDesign.curve,
    // Incoming content only; there is no fading snapshot of another goal.
    builder: (_, value, child) => Opacity(
      key: const Key('analysisSummaryOpacity'),
      opacity: value,
      child: Transform.translate(
        offset: Offset(0, 8 * (1 - value)),
        child: child,
      ),
    ),
    child: child,
  );
}

class _AnalysisSubjects extends StatelessWidget {
  const _AnalysisSubjects({required this.records});
  final List<Map<String, dynamic>> records;
  @override
  Widget build(BuildContext context) {
    final groups = <int, List<Map<String, dynamic>>>{};
    for (final row in records) {
      groups.putIfAbsent(row['subjectId'] as int, () => []).add(row);
    }
    final total = _analysisMinutes(records);
    return SectionCard(
      title: '과목별 학습 현황',
      children: [
        const Text('조회된 기록의 공부시간 비중입니다. 목표 달성률이 아니며 기록이 없는 과목은 표시하지 않습니다.'),
        for (final group in groups.entries)
          Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    Icons.menu_book_outlined,
                    key: ValueKey('analysis-subject-color-${group.key}'),
                    color: subjectPlanColor(group.key),
                    size: 22,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      group.value.first['subjectName'] as String,
                      style: Theme.of(context).textTheme.titleSmall,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Text(
                '${group.value.length}건 · ${_analysisMinutes(group.value) == null ? '시간 정보 일부 없음' : '${_analysisMinutes(group.value)}분'}',
              ),
              if (total != null && total > 0)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: LinearProgressIndicator(
                    key: ValueKey('analysis-subject-bar-${group.key}'),
                    value: _analysisMinutes(group.value)!.toDouble() / total,
                    minHeight: 7,
                    borderRadius: BorderRadius.circular(8),
                    color: subjectPlanColor(group.key),
                    semanticsLabel:
                        '${group.value.first['subjectName']} 조회된 공부시간 ${_analysisMinutes(group.value)}분, 전체 $total분',
                  ),
                ),
            ],
          ),
      ],
    );
  }
}

class _AnalysisRecord extends StatelessWidget {
  const _AnalysisRecord({super.key, required this.record});
  final Map<String, dynamic> record;
  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: colors.surfaceContainerLow,
        borderRadius: BorderRadius.circular(16),
        border: Border(
          left: BorderSide(
            color: subjectPlanColor(record['subjectId'] as int?),
            width: 4,
          ),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            record['subjectName'] as String,
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 8),
          Text(
            '공부시간: ${_analysisNumber(record['studyMinutes'], '분')}',
            style: Theme.of(context).textTheme.titleSmall,
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 12,
            runSpacing: 8,
            children: [
              Text('이해도: ${_analysisScore(record['understandingScore'])}'),
              Text('집중도: ${_analysisScore(record['focusScore'])}'),
              Text('피로도: ${_analysisScore(record['fatigueScore'])}'),
            ],
          ),
          for (final field in [
            ('studyContent', '학습 내용'),
            ('studyMethod', '학습 방법'),
            ('memo', '메모'),
          ])
            if (record[field.$1] is String &&
                (record[field.$1] as String).trim().isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text('${field.$2}: ${record[field.$1]}'),
              ),
        ],
      ),
    );
  }
}
