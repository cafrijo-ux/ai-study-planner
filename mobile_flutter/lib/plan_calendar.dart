import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart' show DragStartBehavior;

import 'app_design.dart';
import 'planner_api.dart';

String planDateKey(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
String planMonthKey(DateTime d) => planDateKey(d).substring(0, 7);
DateTime seoulToday([DateTime? instant]) {
  final k = (instant ?? DateTime.now()).toUtc().add(const Duration(hours: 9));
  return DateTime(k.year, k.month, k.day);
}

DateTime parsePlanDate(String text) {
  final d = DateTime.tryParse(text);
  if (!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(text) ||
      d == null ||
      planDateKey(d) != text) {
    throw const ApiFailure('계획 날짜 형식이 올바르지 않습니다.');
  }
  return DateTime(d.year, d.month, d.day);
}

/// Stable ID palette; missing IDs stay neutral rather than guessing by name.
Color subjectPlanColor(int? id) => id == null
    ? const Color(0xFF8B95A5)
    : const [
        Color(0xFF72A7FF),
        Color(0xFFB39AFF),
        Color(0xFF6AD7AA),
        Color(0xFFF2B56B),
        Color(0xFFEB92C3),
        Color(0xFF65CCDC),
        Color(0xFFC6CA71),
        Color(0xFFEE9690),
      ][(id - 1).abs() % 8];

enum CalendarPlanSource { saved, preview, calculated }

class CalendarPlan {
  CalendarPlan(
    Map<String, dynamic> raw, {
    required this.goalId,
    required this.source,
  }) : data = Map.unmodifiable(raw) {
    if (raw['goalId'] != goalId) throw const ApiFailure('선택 목표와 달력 계획이 다릅니다.');
    final text = raw['planDate'] ?? raw['scheduleDate'];
    if (text is! String) throw const ApiFailure('계획 날짜 정보가 없습니다.');
    date = parsePlanDate(text);
    if (raw['scheduleDate'] != null && raw['scheduleDate'] != text) {
      throw const ApiFailure('계획 날짜가 서로 일치하지 않습니다.');
    }
    for (final k in ['studyMinutes', 'carryoverMinutes', 'newStudyMinutes']) {
      if (raw[k] is! num || !(raw[k] as num).isFinite || (raw[k] as num) < 0) {
        throw const ApiFailure('학습 시간 정보가 올바르지 않습니다.');
      }
    }
    if (raw['timeSlots'] != null && raw['timeSlots'] is! List) {
      throw const ApiFailure('시간표 형식이 올바르지 않습니다.');
    }
    if ((raw['timeSlots'] is List &&
            (raw['timeSlots'] as List).any((s) => s is! Map)) ||
        (raw['subjectPlans'] != null && raw['subjectPlans'] is! List)) {
      throw const ApiFailure('시간표 또는 과목 배분 항목 형식이 올바르지 않습니다.');
    }
    if (slots.length > 500) throw const ApiFailure('시간표 항목이 너무 많습니다.');
    for (final s in slots) {
      for (final key in ['startTime', 'endTime']) {
        final t = s[key]?.toString();
        if (t != null &&
            RegExp(r'^\d{4}-\d{2}-\d{2}').hasMatch(t) &&
            !t.startsWith(text)) {
          throw const ApiFailure('선택 날짜와 시간표 날짜가 다릅니다.');
        }
      }
    }
  }
  final Map<String, dynamic> data;
  final int goalId;
  final CalendarPlanSource source;
  late final DateTime date;
  String get dateKey => planDateKey(date);
  String get compactStatus => source != CalendarPlanSource.saved
      ? '미저장'
      : data['planStatus'] == 'FINAL'
      ? '확정'
      : data['planStatus'] == 'DRAFT'
      ? '임시'
      : '미상';
  String get status => source == CalendarPlanSource.preview
      ? '미리보기 · 미저장'
      : source == CalendarPlanSource.calculated
      ? '계산 계획 · 미저장'
      : '${data['planStatus'] == 'FINAL'
            ? '확정 계획 (FINAL)'
            : data['planStatus'] == 'DRAFT'
            ? '임시 계획 (DRAFT)'
            : data['planStatus'] ?? '상태 정보 없음'} · 서버 저장본';
  List<Map<String, dynamic>> get slots => (data['timeSlots'] as List? ?? [])
      .whereType<Map>()
      .map((s) => Map<String, dynamic>.from(s))
      .toList();
  List<Map<String, dynamic>> get subjects {
    final result = <String, Map<String, dynamic>>{};
    for (final s in slots.where((s) => s['type'] != '휴식')) {
      final id = s['subjectId'];
      result.putIfAbsent(
        id is int ? 'id$id' : 'unknown:${s['subjectName']}',
        () => s,
      );
    }
    return result.values.toList();
  }
}

/// Pure calendar: selecting a month/day never creates, calculates or saves plans.
class PlanMonthCalendar extends StatelessWidget {
  const PlanMonthCalendar({
    super.key,
    required this.month,
    required this.selected,
    required this.plans,
    required this.onDay,
    required this.onMonth,
    this.today,
    this.coverageComplete = true,
  });
  final DateTime month, selected;
  final DateTime? today;
  final List<CalendarPlan> plans;
  final ValueChanged<DateTime> onDay, onMonth;
  final bool coverageComplete;
  @override
  Widget build(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    final now = today ?? seoulToday();
    final first = DateTime(month.year, month.month);
    final start = first.subtract(Duration(days: first.weekday % 7));
    // Six fixed weeks keep the viewport stable across four/five/six-row months.
    const days = 42;
    final scale = MediaQuery.textScalerOf(context).scale(12) / 12;
    final cellHeight = 90.0 + (scale > 1 ? (scale - 1) * 80 : 0);
    final legend = <String, Map<String, dynamic>>{};
    for (final p in plans.where(
      (p) => planMonthKey(p.date) == planMonthKey(month),
    )) {
      for (final s in p.subjects) {
        legend.putIfAbsent(
          s['subjectId'] is int
              ? 'id:${s['subjectId']}'
              : 'name:${s['subjectName']}',
          () => s,
        );
      }
    }
    return Container(
      key: const Key('planMonthCalendar'),
      margin: const EdgeInsets.symmetric(vertical: 14),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: c.surfaceContainerLow,
        borderRadius: BorderRadius.circular(AppDesign.radius),
        border: Border.all(color: c.outlineVariant.withValues(alpha: .6)),
      ),
      child: Column(
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(9),
                decoration: BoxDecoration(
                  color: c.primary.withValues(alpha: .12),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  Icons.calendar_month_rounded,
                  color: c.primary,
                  size: 20,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  '월간 학습 일정',
                  style: Theme.of(context).textTheme.titleSmall,
                ),
              ),
              TextButton(
                key: const Key('calendarToday'),
                onPressed: () {
                  onMonth(DateTime(now.year, now.month));
                  onDay(now);
                },
                child: const Text('오늘'),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              IconButton.filledTonal(
                key: const Key('calendarPrevious'),
                tooltip: '이전 달',
                onPressed: month.year <= 1970 && month.month == 1
                    ? null
                    : () => onMonth(DateTime(month.year, month.month - 1)),
                icon: const Icon(Icons.chevron_left),
              ),
              Expanded(
                child: Text(
                  '${month.year}년 ${month.month}월',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
              IconButton.filledTonal(
                key: const Key('calendarNext'),
                tooltip: '다음 달',
                onPressed: month.year >= 2100 && month.month == 12
                    ? null
                    : () => onMonth(DateTime(month.year, month.month + 1)),
                icon: const Icon(Icons.chevron_right),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              for (final d in const ['일', '월', '화', '수', '목', '금', '토'])
                Expanded(
                  child: Text(
                    d,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      color: d == '일' ? c.error : c.onSurfaceVariant,
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 8),
          _MonthSlide(
            key: const Key('calendar-month-transition'),
            month: month,
            onMonth: onMonth,
            height: cellHeight * 6 + 4 * 5,
            reduceMotion: MediaQuery.disableAnimationsOf(context),
            child: LayoutBuilder(
              key: ValueKey(planMonthKey(month)),
              builder: (context, size) => Wrap(
                spacing: 2,
                runSpacing: 4,
                children: List.generate(days, (index) {
                  final day = DateTime(
                    start.year,
                    start.month,
                    start.day + index,
                  );
                  final key = planDateKey(day);
                  final matches = plans.where((p) => p.dateKey == key).toList();
                  final p =
                      matches
                          .where((p) => p.source == CalendarPlanSource.saved)
                          .firstOrNull ??
                      matches.firstOrNull;
                  final isToday = key == planDateKey(now),
                      chosen = key == planDateKey(selected),
                      outside = day.month != month.month;
                  final subjects = p?.subjects ?? [];
                  final allowed = day.year >= 1970 && day.year <= 2100;
                  final both =
                      p?.source == CalendarPlanSource.saved &&
                      matches.any((p) => p.source != CalendarPlanSource.saved);
                  return SizedBox(
                    width: (size.maxWidth - 12) / 7,
                    height: cellHeight,
                    child: Semantics(
                      label:
                          '$key${isToday ? ' 오늘' : ''}${chosen ? ' 선택됨' : ''}${p == null ? (coverageComplete && !outside ? ' 일정 없음' : ' 미조회') : ' ${p.status}, ${p.data['studyMinutes']}분, ${subjects.map((s) => s['subjectName'] ?? '과목 정보 없음').join(', ')}'}${both ? ', 미저장 미리보기도 있음' : ''}',
                      selected: chosen,
                      button: true,
                      enabled: allowed,
                      child: Material(
                        color: Colors.transparent,
                        child: InkWell(
                          key: Key('calendar-day-$key'),
                          borderRadius: BorderRadius.circular(12),
                          onTap: allowed ? () => onDay(day) : null,
                          child: AnimatedContainer(
                            duration: AppDesign.motion(context),
                            curve: AppDesign.curve,
                            padding: const EdgeInsets.symmetric(
                              vertical: 5,
                              horizontal: 2,
                            ),
                            decoration: BoxDecoration(
                              color: chosen
                                  ? c.primary.withValues(alpha: .16)
                                  : p != null
                                  ? c.surfaceContainerHighest.withValues(
                                      alpha: .4,
                                    )
                                  : Colors.transparent,
                              borderRadius: BorderRadius.circular(12),
                              border: Border.all(
                                color: chosen ? c.primary : Colors.transparent,
                                width: 1.5,
                              ),
                            ),
                            child: Opacity(
                              opacity: outside ? .45 : 1,
                              child: Column(
                                children: [
                                  Container(
                                    key: isToday
                                        ? Key('calendar-today-ring-$key')
                                        : null,
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 4,
                                      vertical: 1,
                                    ),
                                    decoration: BoxDecoration(
                                      border: Border.all(
                                        color: isToday
                                            ? c.tertiary
                                            : Colors.transparent,
                                      ),
                                      borderRadius: BorderRadius.circular(20),
                                    ),
                                    child: Text(
                                      '${day.day}',
                                      style: TextStyle(
                                        fontSize: 12,
                                        fontWeight: isToday || chosen
                                            ? FontWeight.w800
                                            : FontWeight.w500,
                                        color: chosen ? c.primary : c.onSurface,
                                      ),
                                    ),
                                  ),
                                  if (p != null) ...[
                                    Text(
                                      '${p.data['studyMinutes']}분',
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: TextStyle(
                                        fontSize: 9,
                                        fontWeight: FontWeight.w700,
                                        color: c.onSurface,
                                      ),
                                    ),
                                    Container(
                                      margin: const EdgeInsets.symmetric(
                                        vertical: 2,
                                      ),
                                      padding: EdgeInsets.symmetric(
                                        horizontal: scale > 1.3 ? 0 : 3,
                                        vertical: 1,
                                      ),
                                      decoration: BoxDecoration(
                                        color:
                                            (p.source !=
                                                        CalendarPlanSource.saved
                                                    ? c.secondary
                                                    : p.data['planStatus'] ==
                                                          'FINAL'
                                                    ? c.primary
                                                    : c.tertiary)
                                                .withValues(alpha: .12),
                                        borderRadius: BorderRadius.circular(4),
                                      ),
                                      child: Text(
                                        p.compactStatus,
                                        style: TextStyle(
                                          fontSize: 8,
                                          fontWeight: FontWeight.w700,
                                          color:
                                              p.source !=
                                                  CalendarPlanSource.saved
                                              ? c.secondary
                                              : p.data['planStatus'] == 'FINAL'
                                              ? c.primary
                                              : c.tertiary,
                                        ),
                                      ),
                                    ),
                                    if (both)
                                      const Tooltip(
                                        message: '미저장 미리보기도 있음',
                                        child: Icon(
                                          Icons.visibility_outlined,
                                          size: 9,
                                        ),
                                      ),
                                    Wrap(
                                      spacing: 3,
                                      alignment: WrapAlignment.center,
                                      children: [
                                        for (final s in subjects.take(2))
                                          Container(
                                            key: Key(
                                              'calendar-subject-$key-${s['subjectId'] ?? s['subjectName']}',
                                            ),
                                            width: 11,
                                            height: 5,
                                            decoration: BoxDecoration(
                                              color: subjectPlanColor(
                                                s['subjectId'] is int
                                                    ? s['subjectId']
                                                    : null,
                                              ),
                                              borderRadius:
                                                  BorderRadius.circular(3),
                                            ),
                                          ),
                                      ],
                                    ),
                                    if (subjects.length > 2)
                                      Text(
                                        '+${subjects.length - 2}개',
                                        style: const TextStyle(fontSize: 8),
                                      ),
                                  ] else
                                    Padding(
                                      padding: const EdgeInsets.only(top: 12),
                                      child: Text(
                                        '·',
                                        style: TextStyle(
                                          color: c.outlineVariant,
                                        ),
                                      ),
                                    ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  );
                }),
              ),
            ),
          ),
          SizedBox(
            height: 48 * scale,
            child: ListView(
              primary: false,
              padding: EdgeInsets.zero,
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  child: Wrap(
                    spacing: 12,
                    runSpacing: 8,
                    children: [
                      if (legend.isEmpty)
                        Text(
                          '표시할 과목 일정 없음',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      for (final entry in legend.values)
                        _SubjectLegend(
                          color: subjectPlanColor(
                            entry['subjectId'] is int
                                ? entry['subjectId']
                                : null,
                          ),
                          text: '${entry['subjectName'] ?? '과목 정보 없음'}',
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const Divider(height: 20),
          Text(
            coverageComplete
                ? '월간 저장본 · 미저장 계산은 명시 조회 시에만 표시'
                : '조회한 날짜만 표시 · 나머지 날짜는 미조회',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          Text(
            '확정 · 임시 · 미저장 계획을 구분해 표시합니다.',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
    );
  }
}

/// Both pages move inside a fixed, clipped viewport. Callers key the calendar
/// by goal ID so a goal switch disposes every outgoing month immediately.
class _MonthSlide extends StatefulWidget {
  const _MonthSlide({
    super.key,
    required this.month,
    required this.onMonth,
    required this.height,
    required this.reduceMotion,
    required this.child,
  });
  final DateTime month;
  final ValueChanged<DateTime> onMonth;
  final double height;
  final bool reduceMotion;
  final Widget child;
  @override
  State<_MonthSlide> createState() => _MonthSlideState();
}

class _MonthSlideState extends State<_MonthSlide>
    with SingleTickerProviderStateMixin {
  late final AnimationController _motion =
      AnimationController(
        vsync: this,
        value: 1,
        duration: widget.reduceMotion
            ? Duration.zero
            : const Duration(milliseconds: 270),
      )..addStatusListener((status) {
        if (status == AnimationStatus.completed &&
            (_previous != null || _returning)) {
          setState(() {
            _previous = null;
            _returning = false;
          });
        }
      });
  Widget? _previous;
  DateTime? _previousMonth;
  double _direction = 1;
  double _dragFraction = 0, _returnStart = 0, _outgoingStart = 0;
  bool _dragging = false, _pendingSwipe = false, _returning = false;

  bool _allowed(int direction) {
    final next = DateTime(widget.month.year, widget.month.month + direction);
    return next.year >= 1970 && next.year <= 2100;
  }

  void _startDrag(DragStartDetails details) {
    if (_motion.isAnimating || _pendingSwipe) return;
    setState(() {
      _dragging = true;
      _dragFraction = 0;
    });
  }

  void _updateDrag(DragUpdateDetails details, double width) {
    if (!_dragging || width <= 0) return;
    setState(() {
      final delta = details.delta.dx / width;
      final next = (_dragFraction + delta).clamp(-.85, .85).toDouble();
      _dragFraction = _allowed(next < 0 ? 1 : -1)
          ? next
          : next.clamp(-.06, .06).toDouble();
    });
  }

  void _snapBack() {
    setState(() {
      _returnStart = _dragFraction;
      _dragFraction = 0;
      _dragging = false;
      _pendingSwipe = false;
      _returning = !widget.reduceMotion && _returnStart != 0;
      if (_returning) {
        _motion.forward(from: 0);
      } else {
        _motion.value = 1;
      }
    });
  }

  void _endDrag(double width) {
    if (!_dragging) return;
    final direction = _dragFraction < 0 ? 1 : -1;
    final threshold = (width * .22).clamp(48.0, 96.0);
    if (_dragFraction.abs() * width < threshold || !_allowed(direction)) {
      _snapBack();
      return;
    }
    setState(() {
      _dragging = false;
      _pendingSwipe = true;
    });
    widget.onMonth(DateTime(widget.month.year, widget.month.month + direction));
    // A parent that declines the callback must not leave the gesture locked.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _pendingSwipe) _snapBack();
    });
  }

  @override
  void didUpdateWidget(covariant _MonthSlide oldWidget) {
    super.didUpdateWidget(oldWidget);
    _motion.duration = widget.reduceMotion
        ? Duration.zero
        : const Duration(milliseconds: 270);
    final monthChanged =
        planMonthKey(oldWidget.month) != planMonthKey(widget.month);
    if (widget.reduceMotion && (!oldWidget.reduceMotion || monthChanged)) {
      _previous = null;
      _returning = false;
      _pendingSwipe = false;
      _dragging = false;
      _dragFraction = 0;
      _motion.stop();
      _motion.value = 1;
    } else if (monthChanged) {
      _direction = widget.month.isAfter(oldWidget.month) ? 1 : -1;
      _outgoingStart = _pendingSwipe || _dragging ? _dragFraction : 0;
      _dragFraction = 0;
      _pendingSwipe = false;
      _dragging = false;
      _returning = false;
      _previous = oldWidget.child;
      _previousMonth = oldWidget.month;
      _motion.forward(from: 0);
    }
  }

  @override
  void dispose() {
    _motion.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, size) => Listener(
      onPointerCancel: (_) {
        if (_dragging) _snapBack();
      },
      child: GestureDetector(
        key: const Key('calendar-swipe-surface'),
        behavior: HitTestBehavior.opaque,
        dragStartBehavior: DragStartBehavior.down,
        onHorizontalDragStart: _startDrag,
        onHorizontalDragUpdate: (details) =>
            _updateDrag(details, size.maxWidth),
        onHorizontalDragEnd: (_) => _endDrag(size.maxWidth),
        onHorizontalDragCancel: () {
          if (_dragging) _snapBack();
        },
        child: ClipRect(
          child: SizedBox(
            height: widget.height,
            child: AnimatedBuilder(
              animation: _motion,
              builder: (context, _) {
                final progress = Curves.easeInOutCubic.transform(_motion.value);
                Widget page(
                  Widget child,
                  DateTime month,
                  double offset,
                  double opacity,
                ) => Positioned.fill(
                  child: FractionalTranslation(
                    translation: Offset(offset, 0),
                    child: Opacity(
                      opacity: opacity,
                      child: SizedBox.expand(
                        key: Key('calendar-page-${planMonthKey(month)}'),
                        child: child,
                      ),
                    ),
                  ),
                );
                return Stack(
                  clipBehavior: Clip.none,
                  children: [
                    if (_previous != null)
                      Positioned.fill(
                        child: IgnorePointer(
                          child: ExcludeFocus(
                            child: ExcludeSemantics(
                              child: Stack(
                                children: [
                                  page(
                                    _previous!,
                                    _previousMonth!,
                                    _outgoingStart +
                                        (-_direction - _outgoingStart) *
                                            progress,
                                    1 - .15 * progress,
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      ),
                    page(
                      widget.child,
                      widget.month,
                      _dragging || _pendingSwipe
                          ? _dragFraction
                          : _returning
                          ? _returnStart * (1 - progress)
                          : (_direction + _outgoingStart) * (1 - progress),
                      _dragging || _pendingSwipe || _returning
                          ? 1
                          : .85 + .15 * progress,
                    ),
                  ],
                );
              },
            ),
          ),
        ),
      ),
    ),
  );
}

// Do not leave another goal/day visible or tappable during a detail transition.
Widget _currentPlanOnly(Widget? current, List<Widget> previous) =>
    current ?? const SizedBox.shrink();

class _SubjectLegend extends StatelessWidget {
  const _SubjectLegend({required this.color, required this.text});
  final Color color;
  final String text;
  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Icon(Icons.circle, color: color, size: 8),
      const SizedBox(width: 5),
      Flexible(child: Text(text, style: Theme.of(context).textTheme.bodySmall)),
    ],
  );
}

class _CalendarStatusBadge extends StatelessWidget {
  const _CalendarStatusBadge({required this.plan});
  final CalendarPlan plan;
  @override
  Widget build(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    final preview = plan.source != CalendarPlanSource.saved;
    final finalPlan = !preview && plan.data['planStatus'] == 'FINAL';
    final color = preview
        ? c.secondary
        : finalPlan
        ? c.primary
        : c.tertiary;
    return Container(
      key: const Key('calendar-plan-status'),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withValues(alpha: .1),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: .4)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            preview
                ? Icons.visibility_outlined
                : finalPlan
                ? Icons.verified_outlined
                : Icons.edit_note_rounded,
            size: 20,
            color: color,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              plan.status,
              style: TextStyle(color: color, fontWeight: FontWeight.w700),
            ),
          ),
        ],
      ),
    );
  }
}

class _DayPlanMetrics extends StatelessWidget {
  const _DayPlanMetrics({required this.plan});
  final CalendarPlan plan;
  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, size) {
      final c = Theme.of(context).colorScheme;
      final columns =
          size.maxWidth >= 320 &&
              MediaQuery.textScalerOf(context).scale(12) <= 16
          ? 3
          : size.maxWidth >= 240
          ? 2
          : 1;
      return Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          for (final metric in [
            ('전체 공부', 'studyMinutes', Icons.schedule_rounded),
            ('보충 배정', 'carryoverMinutes', Icons.replay_rounded),
            ('신규 배정', 'newStudyMinutes', Icons.auto_stories_outlined),
          ])
            SizedBox(
              width: (size.maxWidth - (columns - 1) * 8) / columns,
              child: Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: c.surfaceContainerLow,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(
                      metric.$3,
                      size: 18,
                      color: metric.$2 == 'carryoverMinutes'
                          ? c.tertiary
                          : c.primary,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      metric.$1,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    const SizedBox(height: 4),
                    Semantics(
                      label: '${metric.$1} ${plan.data[metric.$2]}분',
                      excludeSemantics: true,
                      child: Wrap(
                        spacing: 3,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          Text(
                            '${plan.data[metric.$2]}',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          Text(
                            '분',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
        ],
      );
    },
  );
}

class PlanScheduleDetails extends StatefulWidget {
  const PlanScheduleDetails({
    super.key,
    required this.plan,
    required this.goalName,
  });
  final CalendarPlan plan;
  final String goalName;
  @override
  State<PlanScheduleDetails> createState() => _PlanScheduleDetailsState();
}

class _PlanScheduleDetailsState extends State<PlanScheduleDetails> {
  int? _subject;
  Map<String, dynamic>? _activeSlot;
  @override
  void didUpdateWidget(covariant PlanScheduleDetails oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.plan.goalId != widget.plan.goalId ||
        oldWidget.plan.dateKey != widget.plan.dateKey ||
        oldWidget.plan.source != widget.plan.source) {
      _subject = null;
      _activeSlot = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final plan = widget.plan, goalName = widget.goalName;
    final c = Theme.of(context).colorScheme;
    return AnimatedSwitcher(
      key: const Key('calendar-details-transition'),
      duration: AppDesign.motion(context),
      switchInCurve: AppDesign.curve,
      layoutBuilder: _currentPlanOnly,
      child: Column(
        key: ValueKey(
          '${plan.goalId}/${plan.dateKey}/${plan.source}/${plan.status}',
        ),
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(plan.dateKey, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 4),
          Text(
            goalName,
            style: Theme.of(context).textTheme.bodyMedium
                ?.copyWith(color: c.onSurfaceVariant),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: _CalendarStatusBadge(plan: plan),
          ),
          _DayPlanMetrics(plan: plan),
          const SizedBox(height: 20),
          Text('과목별 일정', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 4,
            children: [
              ChoiceChip(
                label: const Text('전체 구간'),
                selected: _subject == null,
                onSelected: (_) => setState(() {
                  _subject = null;
                  _activeSlot = null;
                }),
              ),
              for (final s in plan.subjects)
                ChoiceChip(
                  avatar: Icon(
                    Icons.circle,
                    size: 12,
                    color: subjectPlanColor(
                      s['subjectId'] is int ? s['subjectId'] : null,
                    ),
                  ),
                  label: Text('${s['subjectName'] ?? '과목 정보 없음'}'),
                  selected: s['subjectId'] is int && _subject == s['subjectId'],
                  onSelected: s['subjectId'] is! int
                      ? null
                      : (_) => setState(() {
                          _subject = s['subjectId'];
                          _activeSlot = null;
                        }),
                ),
            ],
          ),
          if (_subject != null) const Text('선택 과목 구간 · 휴식 포함'),
          if (plan.data['subjectPlans'] is List)
            for (final s in plan.data['subjectPlans'] as List)
              if (s is Map)
                Padding(
                  padding: const EdgeInsets.only(top: 10),
                  child: Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: c.surfaceContainerLow,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        _SubjectLegend(
                          color: subjectPlanColor(
                            s['subjectId'] is int ? s['subjectId'] : null,
                          ),
                          text:
                              '${s['subjectName'] ?? '과목 정보 없음'} · 총 ${s['studyMinutes'] ?? '정보 없음'}분',
                        ),
                        const SizedBox(height: 5),
                        Text(
                          '보충 ${s['carryoverMinutes'] ?? '정보 없음'}분 · 신규 ${s['newStudyMinutes'] ?? '정보 없음'}분',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                ),
          const SizedBox(height: 10),
          Text('상세 시간표', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 10),
          if (plan.slots.isEmpty) const Text('이 계획에는 표시할 시간표 세부 항목이 없습니다.'),
          for (final slot in plan.slots.where(
            (s) =>
                _subject == null ||
                s['subjectId'] == _subject ||
                s['type'] == '휴식',
          ))
            Builder(
              builder: (context) {
                final rest = slot['type'] == '휴식';
                final color = rest
                    ? c.outline
                    : subjectPlanColor(
                        slot['subjectId'] is int ? slot['subjectId'] : null,
                      );
                final allocation = rest
                    ? '휴식'
                    : slot['allocationType'] == 'CATCHUP'
                    ? '보충 학습'
                    : slot['allocationType'] == 'NEW'
                    ? '신규 학습'
                    : '배정 구분 정보 없음';
                final active =
                    _activeSlot != null &&
                    _activeSlot!['startTime'] == slot['startTime'] &&
                    _activeSlot!['endTime'] == slot['endTime'] &&
                    _activeSlot!['subjectId'] == slot['subjectId'] &&
                    _activeSlot!['activity'] == slot['activity'];
                return Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Semantics(
                    button: true,
                    selected: active,
                    child: Material(
                      color: Colors.transparent,
                      child: InkWell(
                        borderRadius: BorderRadius.circular(16),
                        onTap: () =>
                            setState(() => _activeSlot = active ? null : slot),
                        child: AnimatedContainer(
                          duration: AppDesign.motion(context),
                          curve: AppDesign.curve,
                          padding: const EdgeInsets.all(14),
                          decoration: BoxDecoration(
                            color: active
                                ? color.withValues(alpha: .16)
                                : rest
                                ? c.surfaceContainerLow
                                : c.surfaceContainerHighest.withValues(
                                    alpha: .35,
                                  ),
                            borderRadius: BorderRadius.circular(16),
                            border: Border.all(
                              color: active
                                  ? color
                                  : c.outlineVariant.withValues(alpha: .6),
                            ),
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                '${ScheduleTimeline.clock(slot['startTime'])}–${ScheduleTimeline.clock(slot['endTime'])}',
                                style: Theme.of(context).textTheme.titleSmall
                                    ?.copyWith(color: c.onSurfaceVariant),
                              ),
                              const SizedBox(height: 6),
                              Wrap(
                                spacing: 8,
                                runSpacing: 6,
                                crossAxisAlignment: WrapCrossAlignment.center,
                                children: [
                                  Icon(
                                    rest
                                        ? Icons.free_breakfast_outlined
                                        : slot['allocationType'] == 'CATCHUP'
                                        ? Icons.replay
                                        : Icons.menu_book_outlined,
                                    color: color,
                                    size: 18,
                                  ),
                                  Text(
                                    rest
                                        ? '휴식'
                                        : '${slot['subjectName'] ?? '과목명 정보 없음'}',
                                    style: const TextStyle(
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                  Text('${slot['minutes'] ?? '정보 없음'}분'),
                                  Container(
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 9,
                                      vertical: 5,
                                    ),
                                    decoration: BoxDecoration(
                                      color: color.withValues(alpha: .12),
                                      borderRadius: BorderRadius.circular(7),
                                    ),
                                    child: Text(
                                      allocation,
                                      style: TextStyle(
                                        color: color,
                                        fontSize: 12,
                                        fontWeight: FontWeight.w600,
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                              if (slot['activity'] != null)
                                Padding(
                                  padding: const EdgeInsets.only(top: 6),
                                  child: Text('${slot['activity']}'),
                                ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                );
              },
            ),
        ],
      ),
    );
  }
}
