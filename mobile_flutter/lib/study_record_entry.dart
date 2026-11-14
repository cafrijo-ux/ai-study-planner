import 'package:flutter/material.dart';

import 'planner_api.dart';
import 'app_design.dart';

enum StudyEntryResult { saved, expired, uncertain }

class StudyRecordEntry extends StatefulWidget {
  const StudyRecordEntry({
    super.key,
    required this.api,
    required this.goalId,
    required this.goalName,
  });
  final PlannerApi api;
  final int goalId;
  final String goalName;
  @override
  State<StudyRecordEntry> createState() => _StudyRecordEntryState();
}

class _StudyRecordEntryState extends State<StudyRecordEntry> {
  final form = GlobalKey<FormState>();
  final minutes = TextEditingController(),
      content = TextEditingController(),
      memo = TextEditingController();
  final minutesFocus = FocusNode(), contentFocus = FocusNode();
  int _loadVersion = 0, _goalVersion = 0;
  List<Map<String, dynamic>> subjects = [];
  int? subject, focus, understanding, fatigue;
  String? method, error;
  DateTime date = DateTime.now();
  bool loading = true, saving = false, uncertain = false, expired = false;
  String get dateText =>
      '${date.year.toString().padLeft(4, '0')}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';
  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  void didUpdateWidget(covariant StudyRecordEntry oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.goalId != widget.goalId || oldWidget.api != widget.api) {
      _goalVersion++;
      subject = focus = understanding = fatigue = null;
      method = null;
      uncertain = expired = false;
      minutes.clear();
      content.clear();
      memo.clear();
      load();
    }
  }

  @override
  void dispose() {
    minutes.dispose();
    content.dispose();
    memo.dispose();
    minutesFocus.dispose();
    contentFocus.dispose();
    super.dispose();
  }

  Future<void> load() async {
    final version = ++_loadVersion, goal = widget.goalId;
    final api = widget.api;
    setState(() {
      loading = true;
      error = null;
      subjects = [];
      subject = null;
    });
    try {
      final pending = await api.pendingStudyRequest(goal);
      if (!mounted || version != _loadVersion) return;
      if (pending != null) {
        setState(() {
          uncertain = true;
          error = '이 목표에 미확인 요청 키가 보관돼 있습니다. 새 POST 대신 저장 결과를 확인하세요.';
        });
      }
      final rows = await api.entrySubjects(goal);
      if (mounted && version == _loadVersion) setState(() => subjects = rows);
    } on ApiFailure catch (e) {
      if (!mounted || version != _loadVersion) return;
      if (mounted) setState(() => error = e.message);
      if (mounted && (e.status == 401 || e.status == 403)) {
        widget.api.clear();
        Navigator.of(context).pop(StudyEntryResult.expired);
      }
    } finally {
      if (mounted && version == _loadVersion) setState(() => loading = false);
    }
  }

  Future<void> checkSavedRequest() async {
    if (saving) return;
    final goalVersion = _goalVersion;
    setState(() => saving = true);
    try {
      final record = await widget.api.resolveStudyRequest(widget.goalId);
      if (!mounted || goalVersion != _goalVersion) return;
      if (record != null) {
        Navigator.of(context).pop(StudyEntryResult.saved);
      } else {
        setState(
          () => error = '커밋된 결과 미확인. 진행 중 또는 롤백 여부를 단정하지 않으며 재전송하지 않았습니다.',
        );
      }
    } on ApiFailure catch (e) {
      if (mounted && goalVersion == _goalVersion) {
        setState(() {
          error = e.message;
          expired = e.status == 401 || e.status == 403;
        });
      }
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  Future<void> save() async {
    if (saving || loading || uncertain || expired) {
      return;
    }
    final invalid = form.currentState!.validateGranularly();
    if (invalid.isNotEmpty) {
      final first = invalid.first;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted || !first.mounted) return;
        Scrollable.ensureVisible(
          first.context,
          duration: AppDesign.motion(context),
          alignment: .15,
        );
        if (first.widget.key == const Key('entryMinutes')) {
          minutesFocus.requestFocus();
        }
        if (first.widget.key == const Key('entryContent')) {
          contentFocus.requestFocus();
        }
      });
      return;
    }
    FocusScope.of(context).unfocus();
    final goalVersion = _goalVersion;
    setState(() {
      saving = true;
      error = null;
    });
    try {
      await widget.api.saveStudyRecord(
        goalId: widget.goalId,
        subjectId: subject!,
        date: dateText,
        minutes: int.parse(minutes.text.trim()),
        understanding: understanding!,
        focus: focus!,
        fatigue: fatigue!,
        content: content.text,
        method: method,
        memo: memo.text,
      );
      if (mounted && goalVersion == _goalVersion) {
        Navigator.of(context).pop(StudyEntryResult.saved);
      }
    } on ApiFailure catch (e) {
      if (!mounted || goalVersion != _goalVersion) return;
      setState(() {
        error = e.message;
        uncertain = e is StudySaveFailure && e.uncertain;
        expired = e.status == 401 || e.status == 403;
      });
      // Keep the draft on authentication failure; the user explicitly returns to login.
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  Widget rating(
    String label,
    String key,
    int? value,
    ValueChanged<int?> change,
  ) => FormField<int>(
    key: Key(key),
    initialValue: value,
    validator: (v) => v == null ? '$label 선택이 필요합니다.' : null,
    builder: (field) => Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Icon(
              key == 'entryFatigue'
                  ? Icons.battery_3_bar_outlined
                  : key == 'entryFocus'
                  ? Icons.center_focus_strong
                  : Icons.lightbulb_outline,
              color: Theme.of(context).colorScheme.primary,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                '$label · 필수',
                style: Theme.of(context).textTheme.titleSmall,
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        Text(
          key == 'entryFatigue' ? '1점 편안함 · 5점 매우 피곤함' : '1점 낮음 · 5점 높음',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 10),
        LayoutBuilder(
          builder: (context, constraints) {
            final scale = MediaQuery.textScalerOf(context).scale(14) / 14;
            final columns = constraints.maxWidth >= 272 * scale
                ? 5
                : constraints.maxWidth >= 160 * scale
                ? 3
                : constraints.maxWidth >= 104
                ? 2
                : 1;
            final width = (constraints.maxWidth - 8 * (columns - 1)) / columns;
            return Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (var i = 1; i <= 5; i++)
                  SizedBox(
                    width: width,
                    child: Semantics(
                      label: '$label $i점, 5점 기준',
                      child: ChoiceChip(
                        key: Key('${key}_$i'),
                        label: Text('$i'),
                        selected: field.value == i,
                        showCheckmark: false,
                        chipAnimationStyle: ChipAnimationStyle(
                          selectAnimation: AnimationStyle(
                            duration: AppDesign.motion(context),
                          ),
                          enableAnimation: AnimationStyle(
                            duration: AppDesign.motion(context),
                          ),
                        ),
                        onSelected: loading || saving || uncertain || expired
                            ? null
                            : (_) {
                                field.didChange(i);
                                change(i);
                              },
                      ),
                    ),
                  ),
              ],
            );
          },
        ),
        if (field.errorText != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              field.errorText!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
      ],
    ),
  );
  @override
  Widget build(BuildContext context) => PopScope<StudyEntryResult>(
    canPop: !saving && !uncertain && !expired,
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop && !saving && (uncertain || expired)) {
        Navigator.of(context).pop(
          uncertain ? StudyEntryResult.uncertain : StudyEntryResult.expired,
        );
      }
    },
    child: Scaffold(
      appBar: AppBar(
        title: const Text('공부 기록 입력 · 서버 저장'),
        actions: [
          TextButton(
            key: const Key('entrySaveShortcut'),
            onPressed:
                loading || saving || uncertain || expired || subjects.isEmpty
                ? null
                : save,
            child: const Text('저장'),
          ),
        ],
      ),
      body: SafeArea(
        child: Form(
          key: form,
          child: KeyedSubtree(
            key: ValueKey('entryFields/$_goalVersion'),
            child: ListView(
              key: const Key('entryScroll'),
              padding: AppDesign.screen,
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              children: [
                // Keep the whole form mounted so off-screen required fields are
                // validated too; this is a small, fixed set of input controls.
                Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    PageHeading(
                      compact: true,
                      eyebrow: 'STUDY JOURNAL',
                      title: '오늘의 노력을 남겨요',
                      subtitle:
                          '선택 목표: ${widget.goalName} (ID ${widget.goalId})',
                      icon: Icons.edit_note_rounded,
                    ),
                    if (loading) const LinearProgressIndicator(),
                    const SizedBox(height: 16),
                    SectionCard(
                      title: '학습 기본 정보',
                      children: [
                        DropdownButtonFormField<int>(
                          key: const Key('entrySubject'),
                          isExpanded: true,
                          itemHeight: null,
                          selectedItemBuilder: (context) => subjects
                              .map(
                                (s) => Text(
                                  s['subjectName'] as String,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              )
                              .toList(),
                          initialValue: subject,
                          decoration: const InputDecoration(
                            labelText: '과목 · 필수',
                            prefixIcon: Icon(Icons.book_outlined),
                          ),
                          items: subjects
                              .map(
                                (s) => DropdownMenuItem<int>(
                                  value: s['subjectId'] as int,
                                  child: Text(s['subjectName'] as String),
                                ),
                              )
                              .toList(),
                          onChanged: loading || saving || uncertain || expired
                              ? null
                              : (v) => setState(() => subject = v),
                          validator: (v) => v == null ? '과목을 선택하세요.' : null,
                        ),
                        if (subject != null)
                          Text(
                            '선택 과목: ${subjects.where((s) => s['subjectId'] == subject).firstOrNull?['subjectName'] ?? '정보 없음'}',
                          ),
                        if (!loading && subjects.isEmpty)
                          const Text('선택 목표에 등록된 과목이 없습니다. 저장할 수 없습니다.'),
                        OutlinedButton(
                          key: const Key('entryDate'),
                          onPressed: loading || saving || uncertain || expired
                              ? null
                              : () async {
                                  final goalVersion = _goalVersion;
                                  final d = await showDatePicker(
                                    context: context,
                                    initialDate: date,
                                    firstDate: DateTime(2020),
                                    lastDate: DateTime(2100),
                                  );
                                  if (d != null &&
                                      mounted &&
                                      goalVersion == _goalVersion) {
                                    setState(() => date = d);
                                  }
                                },
                          child: Text('공부 날짜: $dateText'),
                        ),
                        TextFormField(
                          key: const Key('entryMinutes'),
                          controller: minutes,
                          focusNode: minutesFocus,
                          enabled: !saving && !uncertain && !expired,
                          keyboardType: TextInputType.number,
                          textInputAction: TextInputAction.next,
                          onFieldSubmitted: (_) => contentFocus.requestFocus(),
                          onChanged: (_) => setState(() {}),
                          decoration: const InputDecoration(
                            labelText: '공부시간 (분)',
                            prefixIcon: Icon(Icons.timer_outlined),
                            suffixText: '분',
                            helperText: '실제로 공부한 시간을 입력하세요 · 필수',
                          ),
                          validator: (v) {
                            final n = int.tryParse(v?.trim() ?? '');
                            return n == null || n <= 0 || n > 2147483647
                                ? '유효한 양의 정수(분)를 입력하세요.'
                                : null;
                          },
                        ),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            for (final n in [15, 30, 60])
                              Semantics(
                                label: '공부시간 $n분으로 설정',
                                child: ChoiceChip(
                                  key: Key('entryPreset$n'),
                                  label: Text('$n분'),
                                  selected: minutes.text.trim() == '$n',
                                  chipAnimationStyle: ChipAnimationStyle(
                                    selectAnimation: AnimationStyle(
                                      duration: AppDesign.motion(context),
                                    ),
                                  ),
                                  onSelected:
                                      loading || saving || uncertain || expired
                                      ? null
                                      : (_) =>
                                            setState(() => minutes.text = '$n'),
                                ),
                              ),
                          ],
                        ),
                      ],
                    ),
                    SectionCard(
                      title: '공부한 내용',
                      children: [
                        TextFormField(
                          key: const Key('entryContent'),
                          controller: content,
                          focusNode: contentFocus,
                          enabled: !saving && !uncertain && !expired,
                          minLines: 3,
                          maxLines: 6,
                          keyboardType: TextInputType.multiline,
                          decoration: const InputDecoration(
                            labelText: '공부 내용 (필수)',
                            hintText: '배운 개념이나 풀어 본 문제를 적어 주세요.',
                            alignLabelWithHint: true,
                          ),
                          validator: (v) => v == null || v.trim().isEmpty
                              ? '공부 내용을 입력하세요.'
                              : null,
                        ),
                        DropdownButtonFormField<String>(
                          key: const Key('entryMethod'),
                          initialValue: method,
                          isExpanded: true,
                          decoration: const InputDecoration(
                            labelText: '학습 활동 (선택)',
                          ),
                          items: [
                            for (final m in [
                              '개념 학습',
                              '문제풀이',
                              '오답정리',
                              '복습',
                              '암기',
                            ])
                              DropdownMenuItem(value: m, child: Text(m)),
                          ],
                          onChanged: saving || uncertain || expired
                              ? null
                              : (v) => setState(() => method = v),
                        ),
                      ],
                    ),
                    SectionCard(
                      title: '나의 학습 컨디션',
                      children: [
                        rating(
                          '집중도',
                          'entryFocus',
                          focus,
                          (v) => setState(() => focus = v),
                        ),
                        rating(
                          '이해도',
                          'entryUnderstanding',
                          understanding,
                          (v) => setState(() => understanding = v),
                        ),
                        rating(
                          '피로도',
                          'entryFatigue',
                          fatigue,
                          (v) => setState(() => fatigue = v),
                        ),
                      ],
                    ),
                    SectionCard(
                      title: '학습 메모 · 선택',
                      children: [
                        TextFormField(
                          key: const Key('entryMemo'),
                          controller: memo,
                          minLines: 3,
                          maxLines: 5,
                          keyboardType: TextInputType.multiline,
                          enabled: !saving && !uncertain && !expired,
                          decoration: const InputDecoration(
                            labelText: '학습 메모 (선택)',
                            hintText: '어려웠던 점이나 다음에 복습할 내용을 남겨 보세요.',
                            alignLabelWithHint: true,
                          ),
                        ),
                      ],
                    ),
                    SectionCard(
                      title: saving
                          ? '저장 결과를 확인하고 있어요'
                          : uncertain
                          ? '저장 여부 확인 필요'
                          : expired
                          ? '다시 로그인해 주세요'
                          : '기록 저장',
                      children: [
                        if (saving) const LinearProgressIndicator(),
                        if (error != null)
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 16),
                            child: StateNotice(text: error!, warning: true),
                          ),
                        if (uncertain && !expired)
                          OutlinedButton(
                            key: const Key('entryLookup'),
                            onPressed: saving ? null : checkSavedRequest,
                            child: const Text('저장 결과 확인 (GET)'),
                          ),
                        Text(
                          widget.api.requestStore.persistent
                              ? '요청 키만 기기에 보관합니다. 서버 영속 보호는 별도 활성화가 필요하며 자동 재전송하지 않습니다.'
                              : '테스트 메모리 요청 키입니다. 서버 영속 보호 활성화와는 별개입니다.',
                        ),
                        if (error != null &&
                            subjects.isEmpty &&
                            !uncertain &&
                            !expired)
                          OutlinedButton(
                            onPressed: loading || saving ? null : load,
                            child: const Text('과목 다시 조회'),
                          ),
                        if (error != null && !saving && (uncertain || expired))
                          TextButton(
                            onPressed: () => Navigator.of(context).pop(
                              uncertain
                                  ? StudyEntryResult.uncertain
                                  : StudyEntryResult.expired,
                            ),
                            child: Text(uncertain ? '재전송 없이 기록 확인' : '입력 종료'),
                          ),
                        FilledButton.icon(
                          key: const Key('entrySave'),
                          onPressed:
                              loading ||
                                  saving ||
                                  uncertain ||
                                  expired ||
                                  subjects.isEmpty
                              ? null
                              : save,
                          icon: const Icon(Icons.save_outlined),
                          label: Text(saving ? '저장 확인 중…' : '공부 기록 저장'),
                        ),
                        const Text(
                          '저장 중에는 다시 누르지 마세요. 응답이 불확실하면 재전송 없이 저장 결과를 확인합니다.',
                        ),
                      ],
                    ),
                    if (loading || saving) const SizedBox(height: 20),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}
