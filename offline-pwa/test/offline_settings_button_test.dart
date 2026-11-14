import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/offline_settings_button.dart';

void main() {
  testWidgets('settings opens above Navigator and dialog action responds', (
    t,
  ) async {
    final key = GlobalKey<NavigatorState>();
    int prepared = 0;
    await t.pumpWidget(
      MaterialApp(
        navigatorKey: key,
        builder: (context, child) => Column(
          children: [
            OfflineSettingsButton(
              navigatorKey: key,
              settingsBuilder: (dialogContext) => AlertDialog(
                title: const Text('오프라인 조회 · Mock'),
                actions: [
                  FilledButton(
                    onPressed: () => prepared++,
                    child: const Text('검증용 가상 데이터 저장'),
                  ),
                  TextButton(
                    onPressed: () => Navigator.pop(dialogContext),
                    child: const Text('닫기'),
                  ),
                ],
              ),
            ),
            Expanded(child: child!),
          ],
        ),
        home: const Scaffold(body: Text('Mock 로그인')),
      ),
    );
    await t.tap(find.text('오프라인 설정'));
    await t.pumpAndSettle();
    expect(find.byType(AlertDialog), findsOneWidget);
    await t.tap(find.text('검증용 가상 데이터 저장'));
    await t.pump();
    expect(prepared, 1);
    await t.tap(find.text('닫기'));
    await t.pumpAndSettle();
    expect(find.byType(AlertDialog), findsNothing);
    expect(find.text('Mock 로그인'), findsOneWidget);
    expect(t.takeException(), isNull);
  });
  testWidgets('settings keeps working after navigation and reopen', (t) async {
    final key = GlobalKey<NavigatorState>();
    await t.pumpWidget(
      MaterialApp(
        navigatorKey: key,
        builder: (context, child) => Column(
          children: [
            OfflineSettingsButton(
              navigatorKey: key,
              settingsBuilder: (dialogContext) => AlertDialog(
                title: const Text('오프라인 조회 · Mock'),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.pop(dialogContext),
                    child: const Text('닫기'),
                  ),
                ],
              ),
            ),
            Expanded(child: child!),
          ],
        ),
        home: const Scaffold(body: Text('로그인')),
      ),
    );
    key.currentState!.push(
      MaterialPageRoute<void>(
        builder: (_) => const Scaffold(body: Text('대시보드')),
      ),
    );
    await t.pumpAndSettle();
    for (int i = 0; i < 2; i++) {
      await t.tap(find.text('오프라인 설정'));
      await t.pumpAndSettle();
      expect(find.byType(AlertDialog), findsOneWidget);
      await t.tap(find.text('닫기'));
      await t.pumpAndSettle();
      expect(find.text('대시보드'), findsOneWidget);
      expect(t.takeException(), isNull);
    }
  });
}
