import 'package:flutter/material.dart';

Future<void> initializeOffline() async {}

class OfflineStatusBanner extends StatelessWidget {
  const OfflineStatusBanner({super.key, required this.navigatorKey});
  final GlobalKey<NavigatorState> navigatorKey;
  @override
  Widget build(BuildContext context) => const SizedBox.shrink();
}

class OfflineMockHome extends StatelessWidget {
  const OfflineMockHome({super.key});
  @override
  Widget build(BuildContext context) => const SizedBox.shrink();
}
