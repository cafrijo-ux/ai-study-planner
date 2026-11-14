import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart' show kIsWeb;

import 'platform_services_io.dart'
    if (dart.library.js_interop) 'platform_services_web.dart'
    as platform;
import 'offline_runtime_stub.dart'
    if (dart.library.js_interop) 'offline_runtime_web.dart'
    as offline;

import 'planner_api.dart';
import 'server_screens.dart';
import 'app_design.dart';
export 'app_design.dart' show SectionCard, Metric;

final _plannerNavigatorKey = GlobalKey<NavigatorState>();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  if (kIsWeb) await offline.initializeOffline();
  const address = String.fromEnvironment('API_BASE_URL');
  try {
    final api = address.isEmpty
        ? null
        : PlannerApi(
            base: Uri.parse(address),
            transport: platform.createTransport(),
            requestStore: platform.createRequestStore(),
            allowDebugLanHttp: const bool.fromEnvironment(
              'ALLOW_DEBUG_LAN_HTTP',
            ),
          );
    runApp(PlannerApp(api: api));
  } on ApiFailure catch (e) {
    runApp(PlannerApp(configurationError: e.message));
  } on FormatException {
    runApp(const PlannerApp(configurationError: 'API_BASE_URL 주소 형식을 확인하세요.'));
  }
}

class PlannerApp extends StatelessWidget {
  const PlannerApp({super.key, this.api, this.configurationError});
  final PlannerApi? api;
  final String? configurationError;
  @override
  Widget build(BuildContext context) => MaterialApp(
    navigatorKey: kIsWeb ? _plannerNavigatorKey : null,
    title: 'AI 공부 플래너',
    debugShowCheckedModeBanner: false,
    theme: _webFont(AppDesign.theme(Brightness.light)),
    darkTheme: _webFont(AppDesign.theme(Brightness.dark)),
    themeMode: ThemeMode.system,
    builder: (context, child) {
      if (!kIsWeb) return child!;
      final screen = Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 1180),
          child: child!,
        ),
      );
      if (api != null || configurationError != null) return screen;
      return Column(
        children: [
          Material(
            color: Theme.of(context).colorScheme.primaryContainer,
            child: const SafeArea(
              bottom: false,
              child: Padding(
                padding: EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                child: Text(
                  'MOCK 시연 · 모든 일정·기록·응답은 가상 데이터입니다. 실제 저장·서버 연결·AI 호출 없음.',
                  textAlign: TextAlign.center,
                ),
              ),
            ),
          ),
          offline.OfflineStatusBanner(navigatorKey: _plannerNavigatorKey),
          Expanded(child: screen),
        ],
      );
    },
    home: configurationError != null
        ? Scaffold(
            appBar: AppBar(title: const Text('서버 연결 설정 오류')),
            body: Padding(
              padding: const EdgeInsets.all(24),
              child: Text(configurationError!),
            ),
          )
        : api == null
        ? const DemoLogin()
        : ServerLogin(api: api!),
  );
}

class MockNotice extends StatelessWidget {
  const MockNotice({super.key});
  @override
  Widget build(BuildContext context) => const Padding(
    padding: EdgeInsets.only(bottom: 20),
    child: Text(
      'MOCK 시연 · 서버 미연결\n가상 데이터만 표시합니다. 실제 저장·AI 호출 없음.',
      style: TextStyle(height: 1.6),
    ),
  );
}

class DemoLogin extends StatefulWidget {
  const DemoLogin({super.key});
  @override
  State<DemoLogin> createState() => _DemoLoginState();
}

class _DemoLoginState extends State<DemoLogin> {
  final form = GlobalKey<FormState>();
  final name = TextEditingController();
  final password = TextEditingController();
  @override
  void dispose() {
    name.dispose();
    password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 440),
            child: Form(
              key: form,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Icon(
                    Icons.school_outlined,
                    size: 64,
                    color: Color(0xFF2563EB),
                  ),
                  const SizedBox(height: 20),
                  Text(
                    'AI 공부 플래너',
                    style: Theme.of(context).textTheme.headlineMedium,
                  ),
                  const SizedBox(height: 12),
                  const MockNotice(),
                  const Text('실제 계정 대신 임의의 시연용 값을 입력하세요. 인증 서버에 전송하지 않습니다.'),
                  const SizedBox(height: 20),
                  TextFormField(
                    key: const Key('loginName'),
                    controller: name,
                    decoration: const InputDecoration(labelText: '시연용 아이디'),
                    validator: (v) => v == null || v.trim().isEmpty
                        ? '시연용 아이디를 입력하세요.'
                        : null,
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    key: const Key('loginPassword'),
                    controller: password,
                    obscureText: true,
                    decoration: const InputDecoration(labelText: '시연용 비밀번호'),
                    validator: (v) =>
                        v == null || v.isEmpty ? '시연용 비밀번호를 입력하세요.' : null,
                  ),
                  const SizedBox(height: 24),
                  FilledButton(
                    onPressed: () {
                      if (!form.currentState!.validate()) return;
                      name.clear();
                      password.clear();
                      final route = MaterialPageRoute<void>(
                        builder: (_) => kIsWeb
                            ? const offline.OfflineMockHome()
                            : const PlannerHome(),
                      );
                      if (kIsWeb) {
                        Navigator.of(context).push(route);
                      } else {
                        Navigator.of(context).pushReplacement(route);
                      }
                    },
                    child: const Text('Mock 로그인'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

class PlannerHome extends StatefulWidget {
  const PlannerHome({super.key, this.initialIndex = 0, this.preview = false});
  final int initialIndex;
  final bool preview;
  @override
  State<PlannerHome> createState() => _PlannerHomeState();
}

class _PlannerHomeState extends State<PlannerHome> {
  int selected = 0;
  @override
  void initState() {
    super.initState();
    selected = widget.initialIndex;
  }

  static const titles = ['대시보드', '학습 계획', '학습 분석', 'AI 코칭'];
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(titles[selected]),
      actions: [
        if (!widget.preview)
          IconButton(
            tooltip: 'Mock 로그아웃',
            icon: const Icon(Icons.logout),
            onPressed: () => Navigator.of(context).pushReplacement(
              MaterialPageRoute<void>(builder: (_) => const DemoLogin()),
            ),
          ),
      ],
    ),
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 720),
          child: ListView(
            key: ValueKey(selected),
            padding: const EdgeInsets.all(20),
            children: [const MockNotice(), ...pages()[selected]],
          ),
        ),
      ),
    ),
    bottomNavigationBar: NavigationBar(
      selectedIndex: selected,
      onDestinationSelected: (index) => setState(() => selected = index),
      destinations: const [
        NavigationDestination(
          icon: Icon(Icons.dashboard_outlined),
          label: '대시보드',
        ),
        NavigationDestination(
          icon: Icon(Icons.calendar_month_outlined),
          label: '학습 계획',
        ),
        NavigationDestination(icon: Icon(Icons.bar_chart), label: '학습 분석'),
        NavigationDestination(
          icon: Icon(Icons.auto_awesome_outlined),
          label: 'AI 코칭',
        ),
      ],
    ),
  );
  List<List<Widget>> pages() => [
    [
      const SectionCard(
        title: '나의 학습 목표',
        children: [
          Text(
            '운영체제 시험 준비',
            style: TextStyle(fontSize: 23, fontWeight: FontWeight.bold),
          ),
          Text('가상 기준일 2026.10.25 · 시험 2026.11.12 · D-18'),
        ],
      ),
      const SectionCard(
        title: '오늘의 학습',
        children: [
          Metric(label: '실제 공부시간', value: '40분'),
          Text('프로세스와 스레드의 차이를 복습하고, 핵심 개념을 내 말로 설명해 보세요.'),
          LinearProgressIndicator(
            value: .4,
            semanticsLabel: '가상 계획 대비 학습시간',
            semanticsValue: '40%',
          ),
          Text('계획 100분 중 기록 40분 · 시간 기준 40%'),
        ],
      ),
      const SectionCard(
        title: '미완료 학습과 다음 계획',
        children: [
          Metric(label: '확인된 부족분', value: '60분'),
          Text('다음 계획 보충 9분 · 배정 후 잔여 51분\n보충 배정은 공부 완료를 의미하지 않습니다.'),
          Text('기록이 없는 날짜를 공부하지 않은 날로 단정하지 않습니다.'),
        ],
      ),
    ],
    [
      const SectionCard(
        title: '다음 학습일 · 2026.10.26',
        children: [
          Text('가상 FINAL 계획 · 저장 기능 미연결'),
          Metric(label: '공부시간', value: '100분'),
          Text('운영체제 보충 9분 + 신규 학습 91분\n휴식 20분 포함 · 총 120분'),
        ],
      ),
      const SectionCard(
        title: '학습 시간표',
        children: [
          Slot(time: '19:00–19:09', label: '운영체제 · 보충', minutes: 9),
          Slot(time: '19:09–19:50', label: '운영체제 · 신규 개념', minutes: 41),
          Slot(time: '19:50–20:00', label: '휴식', minutes: 10),
          Slot(time: '20:00–20:50', label: '운영체제 · 신규 복습', minutes: 50),
          Slot(time: '20:50–21:00', label: '휴식', minutes: 10),
        ],
      ),
      const SectionCard(
        title: '계획과 기록은 다릅니다',
        children: [
          Text('FINAL은 확정된 계획이며 실제 공부 기록이 아닙니다. 기록 확인이 필요한 시간은 확정 부족분과 구분합니다.'),
        ],
      ),
    ],
    [
      const SectionCard(
        title: '최근 7일 · 가상 기록',
        children: [
          StudyBar(date: '10.19'),
          StudyBar(date: '10.20'),
          StudyBar(date: '10.21'),
          StudyBar(date: '10.22'),
          StudyBar(date: '10.23'),
          StudyBar(date: '10.24'),
          StudyBar(date: '10.25', minutes: 40),
        ],
      ),
      const SectionCard(
        title: '운영체제 학습 분석',
        children: [
          Metric(label: '평균 이해도', value: '3.0 / 5'),
          Metric(label: '평균 집중도', value: '3.0 / 5'),
          Metric(label: '정답률', value: '정보 없음'),
          Text('공부 기록 1건 · 문제풀이 기록 없음\n기록이 부족하여 추세와 과목별 우열을 판단하기 어렵습니다.'),
        ],
      ),
    ],
    [
      const SectionCard(
        title: '역할이 다른 두 AI',
        children: [
          Text(
            'Gemini: 일상 학습 코칭과 질문 답변\nGPT-4o-mini: 주간 심층 코칭과 질문 답변\n일정 생성·재조정: 서버의 규칙 기반 알고리즘',
          ),
        ],
      ),
      const CoachingExample(
        title: 'Gemini 일반 코칭 · Mock',
        text: '프로세스와 스레드가 공유하는 자원을 표로 비교하고 차이를 설명해 보세요. 이 문장은 UI 확인용 고정 예시입니다.',
      ),
      const CoachingExample(
        title: 'GPT 주간 심층 코칭 · Mock',
        text: '가상 기록은 40분 1건으로 추세를 단정하기 어렵습니다. 계획 100분은 실제 공부시간이 아니며 보충 배정 9분은 전체 부족분 60분과 다릅니다.',
      ),
      const SectionCard(
        title: '저장된 AI 응답',
        children: [
          Text('서버 미연결 · 실제 저장 이력을 조회하지 않았습니다.\n이 화면에는 실제 AI가 생성한 답변이 없습니다.'),
        ],
      ),
    ],
  ];
}

class Slot extends StatelessWidget {
  const Slot({
    super.key,
    required this.time,
    required this.label,
    required this.minutes,
  });
  final String time, label;
  final int minutes;
  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(child: Text('$time\n$label')),
      Text('$minutes분'),
    ],
  );
}

class StudyBar extends StatelessWidget {
  const StudyBar({super.key, required this.date, this.minutes});
  final String date;
  final int? minutes;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Text('$date · ${minutes == null ? "기록 없음" : "$minutes분"}'),
      if (minutes != null)
        LinearProgressIndicator(
          value: minutes! / 100,
          semanticsLabel: '$date 공부시간 $minutes분 (100분 기준)',
        ),
    ],
  );
}

class CoachingExample extends StatelessWidget {
  const CoachingExample({super.key, required this.title, required this.text});
  final String title, text;
  @override
  Widget build(BuildContext context) => Card(
    child: ExpansionTile(
      title: Text(title),
      subtitle: const Text('출처: UI 고정 예시 · 실제 모델 미호출'),
      children: [Padding(padding: const EdgeInsets.all(20), child: Text(text))],
    ),
  );
}

ThemeData _webFont(ThemeData theme) => !kIsWeb
    ? theme
    : theme.copyWith(
        textTheme: theme.textTheme.apply(fontFamily: 'PlannerKorean'),
        primaryTextTheme: theme.primaryTextTheme.apply(
          fontFamily: 'PlannerKorean',
        ),
      );
