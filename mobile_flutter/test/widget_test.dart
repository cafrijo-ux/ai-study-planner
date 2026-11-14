import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/main.dart';

Future<void> login(WidgetTester tester) async {
  await tester.pumpWidget(const PlannerApp());
  await tester.enterText(find.byKey(const Key('loginName')), 'demo');
  await tester.enterText(find.byKey(const Key('loginPassword')), 'mock-only');
  await tester.tap(find.text('Mock 로그인'));
  await tester.pumpAndSettle();
}

Future<void> tab(WidgetTester tester, String label) async {
  await tester.tap(
    find.descendant(of: find.byType(NavigationBar), matching: find.text(label)),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('빈 로그인은 진행하지 않고 실제 인증으로 표시하지 않는다', (tester) async {
    await tester.pumpWidget(const PlannerApp());
    await tester.tap(find.text('Mock 로그인'));
    await tester.pumpAndSettle();
    expect(find.text('시연용 아이디를 입력하세요.'), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);
  });
  testWidgets('Mock 로그인, 네 화면 이동, 로그아웃', (tester) async {
    await login(tester);
    expect(find.text('운영체제 시험 준비'), findsOneWidget);
    await tab(tester, '학습 계획');
    expect(find.text('가상 FINAL 계획 · 저장 기능 미연결'), findsOneWidget);
    expect(find.textContaining('보충 9분 + 신규 학습 91분'), findsOneWidget);
    await tab(tester, '학습 분석');
    expect(find.text('10.19 · 기록 없음'), findsOneWidget);
    expect(find.text('10.25 · 40분'), findsOneWidget);
    await tab(tester, 'AI 코칭');
    expect(find.text('Gemini 일반 코칭 · Mock'), findsOneWidget);
    await tester.tap(find.text('Gemini 일반 코칭 · Mock'));
    await tester.pumpAndSettle();
    expect(find.textContaining('UI 확인용 고정 예시'), findsOneWidget);
    await tester.tap(find.byTooltip('Mock 로그아웃'));
    await tester.pumpAndSettle();
    expect(find.text('Mock 로그인'), findsOneWidget);
    expect(find.text('mock-only'), findsNothing);
  });
  testWidgets('360px 화면에서 네 메뉴에 레이아웃 예외가 없다', (tester) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await login(tester);
    for (final label in ['대시보드', '학습 계획', '학습 분석', 'AI 코칭']) {
      await tab(tester, label);
      await tester.drag(find.byType(ListView), const Offset(0, -600));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    }
  });
  testWidgets('미측정 정답률은 정보 없음이고 AI는 Mock으로 표시', (tester) async {
    await login(tester);
    await tab(tester, '학습 분석');
    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pumpAndSettle();
    expect(find.text('정보 없음'), findsOneWidget);
    await tab(tester, 'AI 코칭');
    expect(find.text('출처: UI 고정 예시 · 실제 모델 미호출'), findsNWidgets(2));
  });
}
