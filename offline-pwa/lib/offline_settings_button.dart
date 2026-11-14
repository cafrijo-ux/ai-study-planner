import 'package:flutter/material.dart';

/// The status banner is above MaterialApp's Navigator. Dialogs must use the
/// Navigator's overlay context rather than the banner's own BuildContext.
class OfflineSettingsButton extends StatefulWidget {
  const OfflineSettingsButton({
    super.key,
    required this.navigatorKey,
    required this.settingsBuilder,
  });
  final GlobalKey<NavigatorState> navigatorKey;
  final WidgetBuilder settingsBuilder;
  @override
  State<OfflineSettingsButton> createState() => _OfflineSettingsButtonState();
}

class _OfflineSettingsButtonState extends State<OfflineSettingsButton> {
  bool opening = false;
  Future<void> open() async {
    if (opening) return;
    final overlay = widget.navigatorKey.currentState?.overlay;
    if (overlay == null) return;
    setState(() => opening = true);
    try {
      await showDialog<void>(
        context: overlay.context,
        builder: widget.settingsBuilder,
      );
    } finally {
      if (mounted) setState(() => opening = false);
    }
  }

  @override
  Widget build(BuildContext context) => TextButton(
    onPressed: opening ? null : open,
    child: const Text('오프라인 설정'),
  );
}
