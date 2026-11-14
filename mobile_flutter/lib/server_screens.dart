import 'dart:async';

import 'package:flutter/foundation.dart'
    show TargetPlatform, defaultTargetPlatform, kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'planner_api.dart';
import 'server_ai_history.dart';
import 'server_plan_analysis.dart';
import 'study_record_entry.dart';
import 'main.dart' show PlannerHome;
import 'app_design.dart';

class ServerLogin extends StatefulWidget {
  const ServerLogin({super.key, required this.api});
  final PlannerApi api;
  @override
  State<ServerLogin> createState() => _ServerLoginState();
}

class _ServerLoginState extends State<ServerLogin> {
  final name = TextEditingController(), password = TextEditingController();
  bool busy = false;
  String? error;
  @override
  void dispose() {
    name.dispose();
    password.dispose();
    super.dispose();
  }

  Future<void> signIn() async {
    if (name.text.trim().isEmpty || password.text.isEmpty) {
      setState(() => error = '아이디와 비밀번호를 입력하세요.');
      return;
    }
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await widget.api.login(name.text.trim(), password.text);
      password.clear();
      if (!mounted) return;
      final message = await Navigator.of(context).push<String>(
        MaterialPageRoute<String>(
          builder: (_) => ServerDashboard(api: widget.api),
        ),
      );
      if (mounted && message != null) setState(() => error = message);
    } on ApiFailure catch (e) {
      if (mounted) setState(() => error = e.message);
    } finally {
      password.clear();
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('AI 공부 플래너 · 서버 로그인')),
    body: Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 440),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: Theme.of(context).colorScheme.primaryContainer,
                      borderRadius: BorderRadius.circular(18),
                    ),
                    child: Icon(
                      Icons.school_outlined,
                      color: Theme.of(context).colorScheme.primary,
                      size: 30,
                    ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '다시 만나 반가워요',
                          style: Theme.of(context).textTheme.headlineSmall
                              ?.copyWith(fontWeight: FontWeight.w800),
                        ),
                        const Text('나의 공부 흐름을 이어가세요.'),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 22),
              Text(
                '연결 서버 · ${widget.api.base.origin}',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 24),
              TextField(
                key: const Key('serverUsername'),
                controller: name,
                enabled: !busy,
                decoration: const InputDecoration(
                  labelText: '기존 계정 아이디',
                  prefixIcon: Icon(Icons.person_outline),
                ),
              ),
              const SizedBox(height: 16),
              TextField(
                key: const Key('serverPassword'),
                controller: password,
                enabled: !busy,
                obscureText: true,
                autocorrect: false,
                enableSuggestions: false,
                decoration: const InputDecoration(
                  labelText: '비밀번호',
                  prefixIcon: Icon(Icons.lock_outline),
                ),
              ),
              const SizedBox(height: 16),
              if (error != null) StateNotice(text: error!, warning: true),
              FilledButton(
                onPressed: busy ? null : signIn,
                child: Text(busy ? '로그인 확인 중…' : '서버 로그인'),
              ),
              const SizedBox(height: 14),
              const Text(
                '현재 서버의 단일 데모 계정으로 로그인합니다. 비밀번호와 세션은 파일에 저장하지 않습니다.',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),
              const Text(
                '읽기 전용 서버에서는 공부 기록·계획을 저장할 수 없습니다.',
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class ServerDashboard extends StatefulWidget {
  const ServerDashboard({super.key, required this.api});
  final PlannerApi api;
  @override
  State<ServerDashboard> createState() => _ServerDashboardState();
}

class _ServerDashboardState extends State<ServerDashboard>
    with WidgetsBindingObserver {
  List<Map<String, dynamic>> goals = [];
  Map<String, dynamic>? summary;
  int? selected;
  bool busy = true, leaving = false;
  String? error;
  int generation = 0;
  int tab = 0;
  bool expired = false;
  Timer? _exitReset;
  bool _exitArmed = false, _exiting = false;
  ScaffoldFeatureController<SnackBar, SnackBarClosedReason>? _exitNotice;
  bool get _androidExit =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  void _resetExit() {
    _exitReset?.cancel();
    _exitReset = null;
    _exitArmed = false;
    final notice = _exitNotice;
    _exitNotice = null;
    notice?.close();
  }

  Future<void> _handleBack(bool didPop) async {
    if (didPop ||
        leaving ||
        expired ||
        _exiting ||
        ModalRoute.of(context)?.isCurrent != true) {
      return;
    }
    if (tab != 0) {
      showTab(0);
      return;
    }
    if (!_androidExit) return;
    if (_exitArmed) {
      _resetExit();
      _exiting = true;
      try {
        // Close the Android activity; do not pop to login or log out the user.
        await SystemNavigator.pop();
      } on PlatformException {
        _exiting = false;
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('앱 종료 요청을 완료하지 못했습니다. 다시 확인해 주세요.')),
          );
        }
      }
      return;
    }
    _exitArmed = true;
    _exitReset = Timer(const Duration(seconds: 2), _resetExit);
    final messenger = ScaffoldMessenger.of(context);
    // The confirmation must be visible now, rather than queued behind an old
    // notice. Removing the old current bar also keeps our close handle current.
    messenger.clearSnackBars();
    messenger.removeCurrentSnackBar();
    final notice = messenger.showSnackBar(
      const SnackBar(
        content: Text('한 번 더 누르면 앱이 종료됩니다.'),
        duration: Duration(seconds: 2),
      ),
    );
    _exitNotice = notice;
    unawaited(
      notice.closed.then((_) {
        if (mounted && identical(_exitNotice, notice)) _exitNotice = null;
      }),
    );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) _resetExit();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _exitReset?.cancel();
    super.dispose();
  }

  final pages = List<Widget?>.filled(4, null);
  void sessionExpired() {
    if (!mounted || expired) return;
    expired = true;
    _resetExit();
    widget.api.clear();
    Navigator.of(context).pop('로그인이 만료됐습니다. 다시 로그인해 주세요.');
  }

  void showTab(int index) {
    if (leaving || expired) return;
    if (index != tab) _resetExit();
    setState(() {
      tab = index;
      if (index == 0 || selected == null || pages[index] != null) return;
      final id = selected!;
      final name =
          goals.firstWhere((g) => g['goalId'] == id)['goalName'] as String;
      pages[index] = switch (index) {
        1 => ServerStudyPlan(
          key: UniqueKey(),
          api: widget.api,
          goalId: id,
          goalName: name,
          onSessionExpired: sessionExpired,
        ),
        2 => ServerStudyAnalysis(
          key: UniqueKey(),
          api: widget.api,
          goalId: id,
          goalName: name,
          onSessionExpired: sessionExpired,
          onRecordsChanged: () {
            setState(() => pages[1] = null);
            select(id);
          },
        ),
        _ => ServerAiHistory(
          key: UniqueKey(),
          api: widget.api,
          goalId: id,
          goalName: name,
          onSessionExpired: sessionExpired,
        ),
      };
    });
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    loadGoals();
  }

  Future<void> loadGoals() async {
    setState(() {
      busy = true;
      error = null;
      summary = null;
    });
    try {
      final rows = await widget.api.goals();
      if (!mounted) return;
      setState(() {
        goals = rows;
        selected = null;
        busy = false;
      });
    } on ApiFailure catch (e) {
      fail(e);
    }
  }

  void fail(ApiFailure e) {
    if (!mounted) return;
    setState(() {
      busy = false;
      error = e.message;
      summary = null;
    });
    if (e.status == 401 || e.status == 403) {
      sessionExpired();
    }
  }

  Future<void> select(int id) async {
    _resetExit();
    final request = ++generation;
    setState(() {
      if (selected != id) {
        for (var i = 1; i < 4; i++) {
          pages[i] = null;
        }
      }
      selected = id;
      busy = true;
      error = null;
      summary = null;
    });
    try {
      final result = await widget.api.summary(id);
      if (!mounted || request != generation) return;
      setState(() {
        summary = result;
        busy = false;
      });
    } on ApiFailure catch (e) {
      if (request == generation) fail(e);
    }
  }

  Future<void> openServerDetail(bool plan) async {
    if (selected == null || busy || leaving) return;
    showTab(plan ? 1 : 2);
  }

  Future<void> enterStudy() async {
    if (selected == null || busy || leaving) return;
    _resetExit();
    final id = selected!;
    final goal = goals.firstWhere((g) => g['goalId'] == id);
    final result = await Navigator.of(context).push<StudyEntryResult>(
      MaterialPageRoute(
        builder: (_) => StudyRecordEntry(
          api: widget.api,
          goalId: id,
          goalName: goal['goalName'] as String,
        ),
      ),
    );
    if (!mounted) return;
    if (result == StudyEntryResult.expired) {
      sessionExpired();
      return;
    }
    if (result == StudyEntryResult.saved ||
        result == StudyEntryResult.uncertain) {
      if (result == StudyEntryResult.saved) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('서버에서 공부 기록 저장을 확인했습니다.')));
      }
      setState(() {
        pages[1] = null;
        pages[2] = null;
      });
      await select(id);
      if (mounted && summary != null) await openServerDetail(false);
    }
  }

  void openSavedAiResponses() => showTab(3);

  Future<void> logout() async {
    _resetExit();
    setState(() => leaving = true);
    try {
      await widget.api.logout();
      if (mounted) Navigator.of(context).pop();
    } on ApiFailure catch (e) {
      fail(e);
    } finally {
      if (mounted) setState(() => leaving = false);
    }
  }

  String score(dynamic value) =>
      value is num && value > 0 ? '${value.toStringAsFixed(1)} / 5' : '정보 없음';
  @override
  Widget build(BuildContext context) => PopScope(
    // Keep the Android home route in Flutter for both button and predictive
    // system back attempts. A second confirmed attempt closes the activity.
    canPop: !_androidExit && tab == 0,
    onPopInvokedWithResult: (didPop, result) {
      _handleBack(didPop);
    },
    child: Scaffold(
      bottomNavigationBar: NavigationBar(
        animationDuration: AppDesign.motion(context),
        selectedIndex: tab,
        onDestinationSelected: showTab,
        destinations: const [
          NavigationDestination(
            key: Key('realTab0'),
            icon: Icon(Icons.home_outlined),
            selectedIcon: Icon(Icons.home_rounded),
            label: '홈',
          ),
          NavigationDestination(
            key: Key('realTab1'),
            icon: Icon(Icons.calendar_month_outlined),
            selectedIcon: Icon(Icons.calendar_month_rounded),
            label: '학습 계획',
          ),
          NavigationDestination(
            key: Key('realTab2'),
            icon: Icon(Icons.bar_chart),
            selectedIcon: Icon(Icons.insights_rounded),
            label: '학습 분석',
          ),
          NavigationDestination(
            key: Key('realTab3'),
            icon: Icon(Icons.history),
            selectedIcon: Icon(Icons.auto_awesome_rounded),
            label: 'AI 코칭',
          ),
        ],
      ),
      body: IndexedStack(
        index: tab,
        children: [
          home(),
          for (var i = 1; i < 4; i++)
            pages[i] ?? const Center(child: Text('홈에서 목표를 선택하세요.')),
        ],
      ),
    ),
  );

  Widget home() => Scaffold(
    appBar: AppBar(
      automaticallyImplyLeading: false,
      title: const Text('대시보드 · 실제 조회'),
      actions: [
        IconButton(
          tooltip: '서버 로그아웃',
          onPressed: leaving ? null : logout,
          icon: const Icon(Icons.logout),
        ),
      ],
    ),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        _DashboardHeader(
          goalName:
              goals
                      .where((g) => g['goalId'] == selected)
                      .firstOrNull?['goalName']
                  as String?,
        ),
        const SizedBox(height: 16),
        DropdownButtonFormField<int>(
          key: ValueKey(goals.length),
          onTap: _resetExit,
          initialValue: selected,
          isExpanded: true,
          decoration: const InputDecoration(labelText: '목표를 직접 선택하세요'),
          items: goals
              .map(
                (g) => DropdownMenuItem<int>(
                  value: g['goalId'] as int,
                  child: Text(
                    g['goalName'] as String,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              )
              .toList(),
          onChanged: busy || leaving
              ? null
              : (id) {
                  if (id != null) select(id);
                },
        ),
        if (busy)
          const Padding(
            padding: EdgeInsets.all(20),
            child: LinearProgressIndicator(),
          ),
        if (!busy && goals.isEmpty && error == null)
          const StateNotice(text: '등록된 목표가 없습니다. 기존 웹사이트에서 확인해 주세요.'),
        if (!busy && goals.isNotEmpty && selected == null && error == null)
          const StateNotice(text: '목표를 선택하면 누적 학습 현황을 보여드립니다.'),
        if (error != null) ...[
          StateNotice(text: error!, warning: true),
          TextButton(
            onPressed: busy
                ? null
                : () {
                    if (selected != null) {
                      select(selected!);
                    } else {
                      loadGoals();
                    }
                  },
            child: const Text('조회 다시 시도'),
          ),
        ],
        if (summary case final data?) ...[
          const SizedBox(height: 20),
          _DashboardOverview(
            key: ValueKey('dashboard-overview-$selected'),
            data: data,
            onAnalysis: () => openServerDetail(false),
          ),
        ],
        const SizedBox(height: 16),
        FilledButton.icon(
          key: const Key('openStudyEntry'),
          onPressed: selected != null && !busy && !leaving ? enterStudy : null,
          icon: const Icon(Icons.edit_note),
          label: const Text('공부 기록 입력'),
        ),
        const SizedBox(height: 16),
        if (summary?['mainMessage'] is String &&
            (summary!['mainMessage'] as String).trim().isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(bottom: 16),
            child: StateNotice(text: summary!['mainMessage'] as String),
          ),
        Text('학습 도구', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 10),
        OutlinedButton.icon(
          key: const Key('openRealPlan'),
          onPressed: selected != null && !busy && !leaving
              ? () => openServerDetail(true)
              : null,
          icon: const Icon(Icons.calendar_month_outlined),
          label: const Text('실제 학습 계획 보기'),
        ),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          key: const Key('openRealAnalysis'),
          onPressed: selected != null && !busy && !leaving
              ? () => openServerDetail(false)
              : null,
          icon: const Icon(Icons.bar_chart_outlined),
          label: const Text('실제 학습 분석 보기'),
        ),
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 10),
          child: Text('날짜별 공부 기록과 자세한 통계는 학습 분석에서 확인하세요.'),
        ),
        FilledButton.icon(
          onPressed: selected != null && !busy && !leaving
              ? openSavedAiResponses
              : null,
          icon: const Icon(Icons.history),
          label: const Text('저장된 AI 답변 보기'),
        ),
        const SizedBox(height: 8),
        Text(
          '기존 Gemini·GPT 답변을 조회합니다. 생성 요청은 별도로 진행합니다.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 24),
        ExpansionTile(
          title: const Text('별도 가상 UI 예시'),
          subtitle: const Text('실제 목표와 연결되지 않는 Mock 화면'),
          children: [
            for (final entry in {1: '학습 계획', 2: '학습 분석', 3: 'AI 코칭'}.entries)
              OutlinedButton(
                onPressed: () {
                  _resetExit();
                  Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) =>
                          PlannerHome(initialIndex: entry.key, preview: true),
                    ),
                  );
                },
                child: Text('${entry.value} · Mock 예시'),
              ),
          ],
        ),
      ],
    ),
  );
}

class _DashboardHeader extends StatelessWidget {
  const _DashboardHeader({required this.goalName});
  final String? goalName;
  @override
  Widget build(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [c.primary.withValues(alpha: .15), c.surfaceContainerLow],
        ),
        borderRadius: BorderRadius.circular(AppDesign.radius),
        border: Border.all(color: c.outlineVariant.withValues(alpha: .6)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.auto_stories_outlined, color: c.primary, size: 22),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  '나의 학습 흐름',
                  style: Theme.of(context).textTheme.labelLarge,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text('오늘도, 나의 속도로', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 8),
          Text(
            goalName == null ? '목표를 선택해 공부를 이어가세요.' : '선택 목표 · $goalName',
            key: const Key('dashboard-current-goal'),
            style: Theme.of(context).textTheme.bodyMedium
                ?.copyWith(color: c.primary, fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }
}

class _DashboardOverview extends StatelessWidget {
  const _DashboardOverview({
    super.key,
    required this.data,
    required this.onAnalysis,
  });
  final Map<String, dynamic> data;
  final VoidCallback onAnalysis;

  String value(dynamic number, String unit) =>
      number is num && number.isFinite && number >= 0
      ? '$number$unit'
      : '정보 없음';

  @override
  Widget build(BuildContext context) {
    final understanding = measuredNumber(data['averageUnderstanding']);
    final focus = measuredNumber(data['averageFocus']);
    String score(num? v) =>
        v != null && v > 0 && v <= 5 ? '${v.toStringAsFixed(1)} / 5' : '정보 없음';
    final metrics = [
      (
        'time',
        '실제 공부시간',
        value(data['totalStudyMinutes'], '분'),
        Icons.timer_outlined,
        null,
      ),
      (
        'records',
        '공부 기록',
        value(data['recordCount'], '건'),
        Icons.edit_note_outlined,
        null,
      ),
      (
        'understanding',
        '평균 이해도',
        score(understanding),
        Icons.lightbulb_outline,
        understanding,
      ),
      (
        'focus',
        '평균 집중도',
        score(focus),
        Icons.center_focus_strong_outlined,
        focus,
      ),
    ];
    // Only incoming data animates. Clearing summary on a goal switch removes
    // all old values immediately; no outgoing snapshot is retained.
    return TweenAnimationBuilder<double>(
      key: const Key('dashboard-summary-entry'),
      tween: Tween(begin: 0, end: 1),
      duration: AppDesign.motion(context),
      curve: AppDesign.curve,
      builder: (context, progress, child) => Opacity(
        opacity: progress,
        child: Transform.translate(
          offset: Offset(0, 10 * (1 - progress)),
          child: child,
        ),
      ),
      child: Column(
        key: const Key('dashboard-summary'),
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('선택 목표 · 누적 기록', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 4),
          Text(
            '선택한 목표에 저장된 전체 기록을 모았습니다.',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: 12),
          LayoutBuilder(
            builder: (context, size) {
              final columns =
                  size.maxWidth >= 260 &&
                      MediaQuery.textScalerOf(context).scale(14) < 22
                  ? 2
                  : 1;
              return Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  for (final metric in metrics)
                    SizedBox(
                      width: (size.maxWidth - (columns - 1) * 12) / columns,
                      child: _DashboardStatCard(
                        key: Key('dashboard-stat-${metric.$1}'),
                        id: metric.$1,
                        label: metric.$2,
                        value: metric.$3,
                        icon: metric.$4,
                        score: metric.$5,
                        onTap: onAnalysis,
                      ),
                    ),
                ],
              );
            },
          ),
          if (data['recordCount'] == 0)
            const Padding(
              padding: EdgeInsets.only(top: 12),
              child: Text('공부 기록 없음 · 첫 기록부터 시작해 보세요.'),
            ),
        ],
      ),
    );
  }
}

class _DashboardStatCard extends StatefulWidget {
  const _DashboardStatCard({
    super.key,
    required this.id,
    required this.label,
    required this.value,
    required this.icon,
    required this.score,
    required this.onTap,
  });
  final String id, label, value;
  final IconData icon;
  final num? score;
  final VoidCallback onTap;
  @override
  State<_DashboardStatCard> createState() => _DashboardStatCardState();
}

class _DashboardStatCardState extends State<_DashboardStatCard> {
  bool _pressed = false;
  @override
  Widget build(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    final score = widget.score;
    final rated = score != null && score.isFinite && score > 0 && score <= 5;
    return Semantics(
      button: true,
      label: '${widget.label} ${widget.value}. 학습 분석 보기',
      excludeSemantics: true,
      child: AnimatedScale(
        key: Key('dashboard-stat-scale-${widget.id}'),
        scale: _pressed ? .98 : 1,
        duration: AppDesign.motion(context),
        curve: AppDesign.curve,
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            borderRadius: BorderRadius.circular(18),
            onTap: widget.onTap,
            onTapDown: (_) => setState(() => _pressed = true),
            onTapUp: (_) => setState(() => _pressed = false),
            onTapCancel: () => setState(() => _pressed = false),
            child: AnimatedContainer(
              duration: AppDesign.motion(context),
              curve: AppDesign.curve,
              padding: const EdgeInsets.all(16),
              constraints: const BoxConstraints(minHeight: 146),
              decoration: BoxDecoration(
                color: _pressed
                    ? c.primary.withValues(alpha: .12)
                    : c.surfaceContainerLow,
                borderRadius: BorderRadius.circular(18),
                border: Border.all(
                  color: _pressed
                      ? c.primary
                      : c.outlineVariant.withValues(alpha: .6),
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(widget.icon, size: 22, color: c.primary),
                  const SizedBox(height: 10),
                  Text(
                    widget.label,
                    style: Theme.of(context).textTheme.bodySmall
                        ?.copyWith(color: c.onSurfaceVariant),
                  ),
                  const SizedBox(height: 5),
                  Text(
                    widget.value,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  if (rated)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: LinearProgressIndicator(
                        value: score.toDouble() / 5,
                        minHeight: 5,
                        borderRadius: BorderRadius.circular(6),
                        semanticsLabel: '${widget.label}, 5점 기준',
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
