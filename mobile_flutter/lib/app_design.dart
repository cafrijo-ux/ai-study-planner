import 'package:flutter/material.dart';

num? measuredNumber(dynamic value) =>
    value is num && value.isFinite ? value : null;

abstract final class AppDesign {
  static const blue = Color(0xFF315CDE);
  static const catchup = Color(0xFFF2A057);
  static const radius = 22.0;
  static const screen = EdgeInsets.fromLTRB(20, 20, 20, 32);
  static const curve = Curves.easeOutCubic;
  static Duration motion(BuildContext context) =>
      MediaQuery.disableAnimationsOf(context)
      ? Duration.zero
      : const Duration(milliseconds: 240);

  static ThemeData theme(Brightness brightness) {
    final dark = brightness == Brightness.dark;
    final colors = ColorScheme.fromSeed(
      seedColor: blue,
      brightness: brightness,
    );
    final base = ThemeData(
      useMaterial3: true,
      colorScheme: colors,
      brightness: brightness,
    );
    final shape = RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(16),
    );
    return base.copyWith(
      scaffoldBackgroundColor: dark
          ? const Color(0xFF10151F)
          : const Color(0xFFF5F7FC),
      textTheme: base.textTheme
          .apply(bodyColor: colors.onSurface, displayColor: colors.onSurface)
          .copyWith(
            headlineMedium: TextStyle(
              fontSize: 28,
              height: 1.25,
              fontWeight: FontWeight.w800,
              letterSpacing: -.8,
              color: colors.onSurface,
            ),
            titleLarge: TextStyle(
              fontSize: 20,
              height: 1.35,
              fontWeight: FontWeight.w700,
              color: colors.onSurface,
            ),
            titleMedium: TextStyle(
              fontSize: 16,
              height: 1.45,
              fontWeight: FontWeight.w700,
              color: colors.onSurface,
            ),
            bodyLarge: TextStyle(
              fontSize: 16,
              height: 1.6,
              color: colors.onSurface,
            ),
            bodyMedium: TextStyle(
              fontSize: 14,
              height: 1.55,
              color: colors.onSurface,
            ),
          ),
      appBarTheme: AppBarTheme(
        backgroundColor: dark
            ? const Color(0xFF10151F)
            : const Color(0xFFF5F7FC),
        foregroundColor: colors.onSurface,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(
          fontSize: 18,
          fontWeight: FontWeight.w700,
          color: colors.onSurface,
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: colors.surfaceContainerLowest,
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 16,
          vertical: 18,
        ),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(color: colors.outlineVariant),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(color: colors.outlineVariant),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(color: colors.primary, width: 2),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(48, 52),
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 15),
          shape: shape,
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size(48, 50),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          shape: shape,
          side: BorderSide(color: colors.outlineVariant),
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          minimumSize: const Size(48, 48),
          shape: shape,
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(minimumSize: const Size(48, 48)),
      ),
      navigationBarTheme: NavigationBarThemeData(
        height: 76,
        elevation: 0,
        backgroundColor: colors.surfaceContainerLowest,
        indicatorColor: colors.primaryContainer,
        labelTextStyle: WidgetStateProperty.resolveWith(
          (states) => TextStyle(
            fontSize: 12,
            fontWeight: states.contains(WidgetState.selected)
                ? FontWeight.w800
                : FontWeight.w500,
            color: states.contains(WidgetState.selected)
                ? colors.primary
                : colors.onSurfaceVariant,
          ),
        ),
      ),
      dialogTheme: DialogThemeData(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(26)),
        backgroundColor: colors.surface,
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      ),
      dividerTheme: DividerThemeData(color: colors.outlineVariant, space: 28),
      pageTransitionsTheme: PageTransitionsTheme(
        builders: {
          for (final platform in TargetPlatform.values)
            platform: const _GentleTransition(),
        },
      ),
    );
  }
}

class _GentleTransition extends PageTransitionsBuilder {
  const _GentleTransition();
  @override
  Widget buildTransitions<T>(
    PageRoute<T> route,
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    if (MediaQuery.disableAnimationsOf(context)) return child;
    final eased = animation.drive(CurveTween(curve: AppDesign.curve));
    return FadeTransition(
      opacity: eased,
      child: SlideTransition(
        position: eased.drive(
          Tween(begin: const Offset(.025, 0), end: Offset.zero),
        ),
        child: child,
      ),
    );
  }
}

class PageHeading extends StatelessWidget {
  const PageHeading({
    super.key,
    required this.title,
    required this.subtitle,
    this.eyebrow,
    this.icon = Icons.auto_stories_outlined,
    this.compact = false,
  });
  final String title, subtitle;
  final String? eyebrow;
  final IconData icon;
  final bool compact;
  @override
  Widget build(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    return Container(
      padding: EdgeInsets.all(compact ? 16 : 22),
      margin: const EdgeInsets.only(bottom: 20),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: [
            c.primaryContainer,
            c.primaryContainer.withValues(alpha: .35),
          ],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(26),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, color: c.onPrimaryContainer, size: 26),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  eyebrow ?? 'AI 공부 플래너',
                  style: TextStyle(
                    color: c.onPrimaryContainer,
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 1,
                  ),
                ),
              ),
            ],
          ),
          SizedBox(height: compact ? 8 : 16),
          Text(
            title,
            style: Theme.of(context).textTheme.headlineMedium?.copyWith(
              color: c.onPrimaryContainer,
              fontSize: compact ? 22 : 28,
            ),
          ),
          SizedBox(height: compact ? 4 : 10),
          Text(
            subtitle,
            style: TextStyle(color: c.onPrimaryContainer, height: 1.6),
          ),
        ],
      ),
    );
  }
}

class StateNotice extends StatelessWidget {
  const StateNotice({
    super.key,
    required this.text,
    this.title,
    this.warning = false,
    this.icon = Icons.info_outline,
  });
  final String text;
  final String? title;
  final bool warning;
  final IconData icon;
  @override
  Widget build(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    return AnimatedContainer(
      duration: AppDesign.motion(context),
      curve: AppDesign.curve,
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: warning ? c.errorContainer : c.surfaceContainerLow,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            warning ? Icons.error_outline : icon,
            size: 20,
            color: warning ? c.onErrorContainer : c.primary,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (title != null) ...[
                  Text(
                    title!,
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      color: warning ? c.onErrorContainer : c.onSurface,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 4),
                ],
                Text(
                  text,
                  style: TextStyle(
                    height: 1.55,
                    color: warning ? c.onErrorContainer : c.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class SectionCard extends StatelessWidget {
  const SectionCard({super.key, required this.title, required this.children});
  final String title;
  final List<Widget> children;
  @override
  Widget build(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.only(bottom: 18),
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: c.surfaceContainerLowest,
        borderRadius: BorderRadius.circular(AppDesign.radius),
        border: Border.all(color: c.outlineVariant.withValues(alpha: .7)),
        boxShadow: [
          BoxShadow(
            color: c.shadow.withValues(alpha: .035),
            blurRadius: 22,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          for (final child in children)
            Padding(padding: const EdgeInsets.only(top: 14), child: child),
        ],
      ),
    );
  }
}

class Metric extends StatelessWidget {
  const Metric({super.key, required this.label, required this.value});
  final String label, value;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 3),
    child: Wrap(
      alignment: WrapAlignment.spaceBetween,
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: 14,
      runSpacing: 5,
      children: [
        Text(
          label,
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
        Text(
          value,
          style: TextStyle(
            fontSize: 22,
            letterSpacing: -.4,
            color: Theme.of(context).colorScheme.primary,
            fontWeight: FontWeight.w800,
          ),
        ),
      ],
    ),
  );
}

class MetricGrid extends StatelessWidget {
  const MetricGrid({super.key, required this.items});
  final List<(String, String, IconData)> items;
  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, size) {
      final columns =
          size.maxWidth >= 260 &&
              MediaQuery.textScalerOf(context).scale(14) < 22
          ? 2
          : 1;
      final width = (size.maxWidth - (columns - 1) * 12) / columns;
      final c = Theme.of(context).colorScheme;
      return Wrap(
        spacing: 12,
        runSpacing: 12,
        children: [
          for (final item in items)
            SizedBox(
              width: width,
              child: Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: c.surfaceContainerLow,
                  borderRadius: BorderRadius.circular(18),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(item.$3, size: 22, color: c.primary),
                    const SizedBox(height: 12),
                    Text(item.$1, style: TextStyle(color: c.onSurfaceVariant)),
                    const SizedBox(height: 5),
                    Text(
                      item.$2,
                      style: TextStyle(
                        color: c.onSurface,
                        fontSize: 22,
                        fontWeight: FontWeight.w800,
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

class PlanBadge extends StatelessWidget {
  const PlanBadge({super.key, required this.label});
  final String label;
  @override
  Widget build(BuildContext context) => Align(
    alignment: Alignment.centerLeft,
    widthFactor: 1,
    child: Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.secondaryContainer,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: Theme.of(context).colorScheme.onSecondaryContainer,
          fontSize: 12,
          fontWeight: FontWeight.w700,
        ),
      ),
    ),
  );
}

/// 모든 수치는 입력값에서 계산한다. 부족분은 배정량과 별개의 지표다.
class AllocationBar extends StatelessWidget {
  const AllocationBar({
    super.key,
    required this.catchup,
    required this.newStudy,
    required this.total,
  });
  final num? catchup, newStudy, total;
  static String percent(double value) => (value * 100).toStringAsFixed(
    value * 100 == (value * 100).roundToDouble() ? 0 : 1,
  );
  @override
  Widget build(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    if ([
          catchup,
          newStudy,
          total,
        ].any((n) => n == null || !n.isFinite || n < 0) ||
        catchup! + newStudy! != total) {
      return const StateNotice(
        text: '학습 배정 비율을 확인할 수 없습니다. 시간 합계를 확인하세요.',
        warning: true,
      );
    }
    final ratio = total == 0 ? 0.0 : catchup!.toDouble() / total!.toDouble();
    final fresh = total == 0 ? 0.0 : newStudy!.toDouble() / total!.toDouble();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Semantics(
          label: total == 0
              ? '배정된 학습 시간 없음'
              : '보충 $catchup분 ${percent(ratio)}퍼센트, 신규 $newStudy분 ${percent(fresh)}퍼센트',
          child: ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: SizedBox(
              height: 14,
              child: total == 0
                  ? ColoredBox(color: c.surfaceContainerHighest)
                  : TweenAnimationBuilder<double>(
                      tween: Tween(begin: 0, end: ratio),
                      duration: AppDesign.motion(context),
                      curve: AppDesign.curve,
                      builder: (context, value, _) => Stack(
                        fit: StackFit.expand,
                        children: [
                          const ColoredBox(color: AppDesign.blue),
                          Align(
                            alignment: Alignment.centerLeft,
                            child: FractionallySizedBox(
                              widthFactor: value,
                              heightFactor: 1,
                              child: const ColoredBox(color: AppDesign.catchup),
                            ),
                          ),
                        ],
                      ),
                    ),
            ),
          ),
        ),
        const SizedBox(height: 12),
        Wrap(
          spacing: 16,
          runSpacing: 10,
          children: [
            Text(
              '● 보충 $catchup분 · ${percent(ratio)}%',
              style: TextStyle(
                color: c.brightness == Brightness.dark
                    ? const Color(0xFFFFBA7A)
                    : const Color(0xFF984500),
                fontWeight: FontWeight.w600,
              ),
            ),
            Text(
              '● 신규 $newStudy분 · ${percent(fresh)}%',
              style: TextStyle(color: c.primary, fontWeight: FontWeight.w600),
            ),
          ],
        ),
        if (total == 0)
          const Padding(
            padding: EdgeInsets.only(top: 8),
            child: Text('배정된 학습 시간이 없습니다.'),
          ),
      ],
    );
  }
}

class ScoreMeter extends StatelessWidget {
  const ScoreMeter({super.key, required this.label, required this.score});
  final String label;
  final num? score;
  @override
  Widget build(BuildContext context) {
    final valid = score != null && score!.isFinite && score! > 0 && score! <= 5;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Metric(
          label: label,
          value: valid ? '${score!.toStringAsFixed(1)} / 5' : '정보 없음',
        ),
        if (valid)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: LinearProgressIndicator(
              value: score!.toDouble() / 5,
              minHeight: 7,
              borderRadius: BorderRadius.circular(8),
              semanticsLabel: '$label $score점, 5점 기준',
            ),
          ),
      ],
    );
  }
}

class SubjectAllocations extends StatelessWidget {
  const SubjectAllocations({super.key, required this.items});
  final dynamic items;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      if (items is! List || (items as List).isEmpty) const Text('과목별 배분 정보 없음'),
      if (items is List)
        for (final item in items as List)
          if (item is Map)
            Padding(
              padding: const EdgeInsets.only(bottom: 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '${item['subjectName'] ?? '과목명 정보 없음'}',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 8,
                    runSpacing: 6,
                    children: [
                      PlanBadge(
                        label: '보충 ${item['carryoverMinutes'] ?? '정보 없음'}분',
                      ),
                      PlanBadge(
                        label: '신규 ${item['newStudyMinutes'] ?? '정보 없음'}분',
                      ),
                    ],
                  ),
                ],
              ),
            ),
    ],
  );
}

class ScheduleTimeline extends StatelessWidget {
  const ScheduleTimeline({super.key, required this.items});
  final dynamic items;
  static String clock(dynamic value) {
    final text = value?.toString() ?? '정보 없음';
    return text.length >= 16 && (text.contains(' ') || text.contains('T'))
        ? text.substring(11, 16)
        : text;
  }

  @override
  Widget build(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (items is! List || (items as List).isEmpty)
          const Text('이 계획에는 표시할 시간표 세부 항목이 없습니다.'),
        if (items is List)
          for (final item in items as List)
            if (item is Map)
              Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      width: 10,
                      height: 10,
                      margin: const EdgeInsets.only(top: 8, right: 14),
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: item['type'] == '휴식'
                            ? c.outlineVariant
                            : item['allocationType'] == 'CATCHUP'
                            ? AppDesign.catchup
                            : c.primary,
                      ),
                    ),
                    Expanded(
                      child: Container(
                        padding: const EdgeInsets.all(14),
                        decoration: BoxDecoration(
                          color: c.surfaceContainerLow,
                          borderRadius: BorderRadius.circular(14),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              '${clock(item['startTime'])}–${clock(item['endTime'])}',
                              style: TextStyle(
                                color: c.primary,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                            const SizedBox(height: 6),
                            Text(
                              '${item['subjectName'] == null ? '' : '${item['subjectName']} · '}${item['activity'] ?? item['type'] ?? '정보 없음'} (${item['type'] ?? '정보 없음'})',
                            ),
                            const SizedBox(height: 6),
                            Text(
                              '${item['minutes'] ?? '정보 없음'}분',
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
      ],
    );
  }
}

/// 제한된 Markdown만 네이티브 텍스트로 표현한다. HTML/WebView/외부 링크 실행 없음.
class AnswerText extends StatelessWidget {
  const AnswerText({super.key, required this.text});
  final String text;
  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.bodyLarge?.copyWith(height: 1.75);
    if (!text.contains('**') &&
        !RegExp(r'^#{1,6}\s', multiLine: true).hasMatch(text)) {
      return SelectableText(text, textAlign: TextAlign.left, style: style);
    }
    final spans = <InlineSpan>[];
    final bold = RegExp(r'\*\*(.+?)\*\*');
    for (final line in text.split('\n')) {
      final heading = RegExp(r'^(#{1,6})\s+').firstMatch(line);
      final clean = heading == null ? line : line.substring(heading.end);
      final pieces = <InlineSpan>[];
      int position = 0;
      for (final match in bold.allMatches(clean)) {
        pieces.add(TextSpan(text: clean.substring(position, match.start)));
        pieces.add(
          TextSpan(
            text: match.group(1),
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
        );
        position = match.end;
      }
      pieces.add(TextSpan(text: clean.substring(position)));
      spans.add(
        TextSpan(
          children: pieces,
          style: heading == null
              ? null
              : const TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.w700,
                  height: 1.8,
                ),
        ),
      );
      spans.add(const TextSpan(text: '\n'));
    }
    return SelectableText.rich(
      TextSpan(style: style, children: spans),
      textAlign: TextAlign.left,
    );
  }
}
