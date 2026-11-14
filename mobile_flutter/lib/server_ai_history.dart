import 'package:flutter/material.dart';

import 'app_design.dart';
import 'planner_api.dart';
import 'server_ai_request.dart';

/// Equal touch cards; measure labels with the actual system text scaler.
class AiGenerationActions extends StatelessWidget {
  const AiGenerationActions({super.key, required this.onSelected});
  final ValueChanged<AiFeature> onSelected;

  static const _labels = ['AI 질문하기', '일반 코칭', '주간 코칭'];
  static const _descriptions = [
    'Gemini 기본 선택, GPT-4o-mini 선택 가능',
    'Gemini 일반 학습 코칭',
    'GPT-4o-mini 주간 심층 코칭',
  ];
  static const _icons = [
    Icons.question_answer_outlined,
    Icons.lightbulb_outline,
    Icons.insights_outlined,
  ];

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context), colors = Theme.of(context).colorScheme;
    final style = theme.textTheme.labelLarge!.copyWith(
      fontWeight: FontWeight.w700,
    );
    final scaler = MediaQuery.textScalerOf(context);
    final direction = Directionality.of(context);
    return LayoutBuilder(
      builder: (context, constraints) {
        const gap = 10.0;
        // At 360dp normal text uses three cards; labels wrap without truncation.
        // Larger system text increases minimum width before measuring height.
        final fontSize = style.fontSize ?? 14;
        final neededWidth = 88 * scaler.scale(fontSize) / fontSize;
        final columns = ((constraints.maxWidth + gap) / (neededWidth + gap))
            .floor()
            .clamp(1, 3);
        final width = (constraints.maxWidth - gap * (columns - 1)) / columns;
        var height = 96.0;
        for (final label in _labels) {
          final painter = TextPainter(
            text: TextSpan(text: label, style: style),
            textDirection: direction,
            textScaler: scaler,
          )..layout(maxWidth: (width - 20).clamp(1, double.infinity));
          if (painter.height + 60 > height) height = painter.height + 60;
          painter.dispose();
        }
        return Wrap(
          spacing: gap,
          runSpacing: gap,
          children: [
            for (var i = 0; i < AiFeature.values.length; i++)
              SizedBox(
                key: Key('openAi_${AiFeature.values[i].name}'),
                width: width,
                height: height,
                child: Semantics(
                  button: true,
                  label: '${_labels[i]}, ${_descriptions[i]}',
                  child: Material(
                    color: colors.surfaceContainerLow,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(16),
                      side: BorderSide(color: colors.outlineVariant),
                    ),
                    clipBehavior: Clip.antiAlias,
                    child: InkWell(
                      onTap: () => onSelected(AiFeature.values[i]),
                      mouseCursor: SystemMouseCursors.click,
                      hoverColor: colors.primary.withValues(alpha: .08),
                      highlightColor: colors.primary.withValues(alpha: .12),
                      child: Padding(
                        padding: const EdgeInsets.all(10),
                        child: ExcludeSemantics(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(_icons[i], size: 26, color: colors.primary),
                              const SizedBox(height: 10),
                              Text(
                                _labels[i],
                                style: style.copyWith(color: colors.onSurface),
                                textAlign: TextAlign.center,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

/// History reads stay non-generating; a separate route requires explicit cost consent.
class ServerAiHistory extends StatefulWidget {
  const ServerAiHistory({
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
  State<ServerAiHistory> createState() => _ServerAiHistoryState();
}

class _ServerAiHistoryState extends State<ServerAiHistory> {
  static const _features = <String>[
    'DAILY_COACHING',
    'WEEKLY_COACHING',
    'QUESTION',
  ];
  static const _labels = <String>['Gemini 일반 코칭', 'GPT 주간 코칭', 'AI 질문 답변'];

  int _tab = 0;
  int _requestId = 0;
  String? _providerFilter;
  bool _loading = false;
  String? _error;
  List<Map<String, dynamic>> _rows = [];

  Future<void> _openGeneration(AiFeature feature) async {
    final result = await Navigator.of(context).push<AiFlowResult>(
      MaterialPageRoute(
        builder: (_) => ServerAiRequest(
          api: widget.api,
          goalId: widget.goalId,
          goalName: widget.goalName,
          feature: feature,
        ),
      ),
    );
    if (!mounted || result == null) return;
    if (result.expired) {
      widget.api.clear();
      if (widget.onSessionExpired != null) {
        widget.onSessionExpired!();
      } else {
        Navigator.pop(context, true);
      }
      return;
    }
    setState(
      () => _tab = feature == AiFeature.daily
          ? 0
          : feature == AiFeature.weekly
          ? 1
          : 2,
    );
    await _load();
  }

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant ServerAiHistory oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.goalId != widget.goalId || oldWidget.api != widget.api) {
      // Clear immediately and invalidate any delayed response for the old goal.
      _load();
    }
  }

  Future<void> _load() async {
    final id = ++_requestId;
    setState(() {
      _loading = true;
      _error = null;
      _rows = [];
      _providerFilter = null;
    });
    try {
      final rows = await widget.api.savedResponses(
        goalId: widget.goalId,
        feature: _features[_tab],
      );
      if (!mounted || id != _requestId) return;
      setState(() {
        _rows = [...rows]
          ..sort((a, b) {
            final date = (b['createdAt']?.toString() ?? '').compareTo(
              a['createdAt']?.toString() ?? '',
            );
            return date != 0
                ? date
                : (b['responseId'] as int).compareTo(a['responseId'] as int);
          });
        _loading = false;
      });
    } on ApiFailure catch (e) {
      if (!mounted || id != _requestId) return;
      if (e.status == 401 || e.status == 403) {
        widget.api.clear();
        setState(() {
          _loading = false;
          _error = '세션이 만료되었습니다. 다시 로그인해 주세요.';
        });
        // Let the calling dashboard return to the login screen.
        if (widget.onSessionExpired != null) {
          widget.onSessionExpired!();
        } else {
          Navigator.of(context).pop(true);
        }
        return;
      }
      setState(() {
        _error = e.message;
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      automaticallyImplyLeading: widget.onSessionExpired == null,
      title: const Text('AI 코칭'),
    ),
    body: SafeArea(
      child: ListView(
        key: const Key('aiHistoryScroll'),
        padding: AppDesign.screen,
        children: [
          PageHeading(
            compact: true,
            eyebrow: 'AI STUDY COACH',
            title: '나의 AI 학습 도우미',
            subtitle: '선택 목표: ${widget.goalName} (ID ${widget.goalId})',
            icon: Icons.auto_awesome_outlined,
          ),
          const StateNotice(
            icon: Icons.auto_awesome_outlined,
            title: '궁금한 것은 질문하고, 학습은 돌아보세요',
            text: '질문은 Gemini 또는 GPT-4o-mini 중 하나를 선택합니다. 일반 코칭과 주간 심층 코칭은 별도 기능입니다.',
          ),
          Material(
            color: Theme.of(context).colorScheme.surfaceContainerLowest,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(AppDesign.radius),
              side: BorderSide(
                color: Theme.of(context).colorScheme.outlineVariant,
              ),
            ),
            clipBehavior: Clip.antiAlias,
            child: ExpansionTile(
              shape: const Border(),
              collapsedShape: const Border(),
              leading: Icon(
                Icons.add_comment_outlined,
                color: Theme.of(context).colorScheme.primary,
              ),
              title: const Text('질문·코칭 생성'),
              subtitle: const Text('기능 선택 후 비용 확인 · 자동 호출 없음'),
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 4, 12, 16),
                  child: AiGenerationActions(onSelected: _openGeneration),
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),
          Text('이전 AI 응답 기록', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 8),
          const Text('선택한 목표의 저장 답변만 조회합니다. 새 AI 요청을 보내지 않습니다.'),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (var i = 0; i < 3; i++)
                ChoiceChip(
                  label: Text(const ['일반 코칭', '주간 GPT', '질문'][i]),
                  selected: _tab == i,
                  avatar: Icon(
                    const [
                      Icons.lightbulb_outline,
                      Icons.insights_outlined,
                      Icons.question_answer_outlined,
                    ][i],
                    size: 18,
                  ),
                  onSelected: (_) {
                    if (i == _tab) return;
                    setState(() => _tab = i);
                    _load();
                  },
                ),
            ],
          ),
          const SizedBox(height: 18),
          Row(
            children: [
              Expanded(
                child: Text(
                  _labels[_tab],
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              IconButton(
                tooltip: '저장된 답변 새로고침 (GET)',
                onPressed: _loading ? null : _load,
                icon: const Icon(Icons.refresh),
              ),
            ],
          ),
          if (_loading)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 24),
              child: LinearProgressIndicator(),
            ),
          if (_error != null) ...[
            StateNotice(text: _error!, warning: true),
            TextButton(
              onPressed: _loading ? null : _load,
              child: const Text('조회 다시 시도'),
            ),
          ],
          if (!_loading && _error == null && _rows.isEmpty)
            const SectionCard(
              title: '저장된 응답 없음',
              children: [
                Text(
                  '선택한 목표와 기능에 해당하는 저장 응답이 없습니다. 미생성 상태이며 새 API 호출은 하지 않습니다.',
                ),
              ],
            ),
          if (!_loading && _error == null && _rows.isNotEmpty) ...[
            Text(
              '${_rows.length}개의 저장 답변 · 최신순',
              key: const Key('savedAnswerCount'),
            ),
            const SizedBox(height: 12),
            if (_tab == 2)
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final entry in const <String?, String>{
                    null: '전체 모델',
                    'gemini': 'Gemini',
                    'gpt': 'GPT-4o-mini',
                  }.entries)
                    ChoiceChip(
                      key: Key('savedModel_${entry.key ?? 'all'}'),
                      label: Text(entry.value),
                      selected: _providerFilter == entry.key,
                      onSelected: (_) =>
                          setState(() => _providerFilter = entry.key),
                    ),
                ],
              ),
            const SizedBox(height: 12),
            if (_providerFilter != null &&
                !_rows.any(
                  (row) => row['requestedProvider'] == _providerFilter,
                ))
              const StateNotice(
                text: '선택한 모델의 저장 답변이 없습니다. 새 AI 요청은 보내지 않았습니다.',
              ),
          ],
          for (final row in _rows.where(
            (row) =>
                _providerFilter == null ||
                row['requestedProvider'] == _providerFilter,
          ))
            AiSavedAnswerCard(
              key: ValueKey('savedAnswer_${row['responseId']}'),
              row: row,
              title: '응답 #${row['responseId']}',
            ),
        ],
      ),
    ),
  );
}
