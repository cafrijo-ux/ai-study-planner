import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';

import 'package:flutter/material.dart';

import 'offline_repository.dart';
import 'offline_store.dart';
import 'offline_settings_button.dart';
import 'offline_store_web.dart';
import 'planner_api.dart';
import 'server_screens.dart';

@JS('PlannerOfflineBridge.refresh')
external JSPromise<JSString> _refresh();

final offlineRuntime = OfflineRuntime();
Future<void> initializeOffline() => offlineRuntime.initialize();

class OfflineRuntime extends ChangeNotifier {
  OfflineRuntime() {
    repository = OfflineRepository(
      IndexedDbOfflineStore(),
      onChanged: notifyListeners,
    );
    Timer.periodic(const Duration(seconds: 3), (_) => poll());
  }
  late final OfflineRepository repository;
  Future<void>? _initializing;
  bool online = true, shellReady = false, waiting = false, busy = false;
  String? error, version;
  int revision = 0;
  Future<void> initialize() => _initializing ??= _initialize();
  Future<void> _initialize() async {
    await poll();
    try {
      await repository.initialize();
    } on OfflineStoreFailure catch (e) {
      error = label(e.code);
    }
    notifyListeners();
  }

  static String label(String code) => switch (code) {
    'QUOTA' => '저장 공간이 부족합니다. 데이터 정리 후 다시 준비하세요.',
    'CORRUPT' => '로컬 데이터가 손상됐습니다. 가상 저장본을 지우고 다시 준비하세요.',
    'BLOCKED' => '다른 창이 저장소를 사용 중입니다. 해당 앱 창을 닫고 다시 여세요.',
    _ => '로컬 저장소 또는 오프라인 설치를 확인할 수 없습니다.',
  };
  Future<void> poll() async {
    try {
      final raw = jsonDecode(browserPwaStatus()) as Map;
      online = raw['online'] == true;
      shellReady = raw['shellReady'] == true;
      waiting = raw['waiting'] == true;
      version = raw['version'] as String?;
      if (raw['error'] != null && !shellReady) {
        error = '오프라인 실행 준비 미완료 · ${raw['error']}';
      }
      unawaited(_refresh().toDart.catchError((_) => '{}'.toJS));
    } catch (_) {
      error = '오프라인 브리지를 확인할 수 없습니다.';
    }
    notifyListeners();
  }

  Future<void> action(Future<void> Function() run) async {
    if (busy) return;
    busy = true;
    error = null;
    notifyListeners();
    try {
      await run();
      revision++;
    } on OfflineStoreFailure catch (e) {
      error = label(e.code);
    } on ApiFailure catch (e) {
      error = e.message;
    } catch (_) {
      error = '로컬 저장 작업을 완료하지 못했습니다.';
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  PlannerApi createApi() => PlannerApi(
    base: Uri.parse('https://mock.invalid'),
    transport: OfflineMockTransport(repository),
  );
}

class OfflineStatusBanner extends StatefulWidget {
  const OfflineStatusBanner({super.key, required this.navigatorKey});
  final GlobalKey<NavigatorState> navigatorKey;
  @override
  State<OfflineStatusBanner> createState() => _OfflineStatusBannerState();
}

class _OfflineStatusBannerState extends State<OfflineStatusBanner> {
  @override
  void initState() {
    super.initState();
    unawaited(offlineRuntime.initialize());
  }

  Widget settings(BuildContext context) => ListenableBuilder(
    listenable: offlineRuntime,
    builder: (context, _) {
      final r = offlineRuntime,
          date = r.repository.lastSync?.toUtc().add(const Duration(hours: 9));
      return AlertDialog(
        title: const Text('오프라인 조회 · Mock'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                '실제 개인 학습 데이터의 저장·동기화는 비활성입니다. 모든 저장본은 검증용 가상 데이터입니다.',
              ),
              const SizedBox(height: 12),
              Text('가상 사용자: ${r.repository.identity.userId}'),
              Text('데이터 출처: MOCK_FIXTURE · IndexedDB'),
              Text(
                '마지막 동기화(가상 저장) 시각: ${date == null ? '없음' : date.toIso8601String().substring(0, 19).replaceAll('T', ' ')}${date == null ? '' : ' KST'}',
              ),
              Text(r.shellReady ? '앱 필수 파일 캐시 확인됨' : '앱 필수 파일 캐시 미완료'),
              if (r.version != null) Text('앱 버전: ${r.version}'),
              if (r.waiting)
                const Text('새 버전 준비됨 · 이 앱의 모든 탭과 홈 화면 창을 닫고 다시 열면 적용됩니다.'),
              const SizedBox(height: 12),
              const Text(
                '오프라인: 저장된 일정·기록 조회만 가능합니다. AI 생성·서버 저장·오프라인 기록 입력은 제공하지 않습니다.',
              ),
              if (r.error != null)
                Text(
                  r.error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              if (r.busy) const LinearProgressIndicator(),
              FilledButton(
                onPressed: r.busy || !r.online
                    ? null
                    : () => r.action(
                        () => r.repository.seedMock(online: r.online),
                      ),
                child: const Text('검증용 가상 데이터 저장'),
              ),
              TextButton(
                onPressed: r.busy ? null : () => r.action(r.repository.clear),
                child: const Text('이 사용자의 가상 저장본 지우기'),
              ),
              TextButton(
                onPressed: r.busy
                    ? null
                    : () => r.action(r.repository.switchMockUser),
                child: const Text('가상 사용자 전환 · 이전 데이터 삭제'),
              ),
              const Text(
                '로그아웃하면 해당 사용자의 저장본을 지웁니다. 사용자 전환 시 이전 데이터도 삭제합니다. 재시도는 버튼을 직접 눌러야 합니다.',
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('닫기'),
          ),
        ],
      );
    },
  );

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: offlineRuntime,
    builder: (context, _) {
      final r = offlineRuntime;
      return Material(
        color: Theme.of(context).colorScheme.surfaceContainer,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  '${r.online ? '온라인(기기 네트워크)' : '오프라인'} · ${r.shellReady ? '앱 실행 준비 완료' : '앱 실행 준비 중/미완료'}\n'
                  '${r.repository.hasData ? '저장된 Mock 데이터 표시 중' : '내려받은 데이터 없음'}${r.error == null ? '' : ' · 저장/설치 확인 필요'}',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
              OfflineSettingsButton(
                navigatorKey: widget.navigatorKey,
                settingsBuilder: settings,
              ),
            ],
          ),
        ),
      );
    },
  );
}

class OfflineMockHome extends StatefulWidget {
  const OfflineMockHome({super.key});
  @override
  State<OfflineMockHome> createState() => _OfflineMockHomeState();
}

class _OfflineMockHomeState extends State<OfflineMockHome> {
  late PlannerApi api;
  int revision = 0;
  @override
  void initState() {
    super.initState();
    api = offlineRuntime.createApi();
    revision = offlineRuntime.revision;
    offlineRuntime.addListener(changed);
  }

  void changed() {
    if (!mounted || revision == offlineRuntime.revision) return;
    api.close();
    setState(() {
      revision = offlineRuntime.revision;
      api = offlineRuntime.createApi();
    });
  }

  @override
  void dispose() {
    offlineRuntime.removeListener(changed);
    api.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) =>
      ServerDashboard(key: ValueKey(revision), api: api);
}
