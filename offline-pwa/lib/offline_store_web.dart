import 'dart:convert';
import 'dart:js_interop';

import 'offline_store.dart';

@JS('PlannerOfflineBridge.activate')
external JSPromise<JSString> _activate(String json);
@JS('PlannerOfflineBridge.read')
external JSPromise<JSString> _read(String json);
@JS('PlannerOfflineBridge.writeBatch')
external JSPromise<JSString> _write(String json);
@JS('PlannerOfflineBridge.clear')
external JSPromise<JSString> _clear();
@JS('PlannerOfflineBridge.status')
external String browserPwaStatus();

class IndexedDbOfflineStore implements OfflineStore {
  Future<dynamic> value(JSPromise<JSString> request) async {
    try {
      final raw = jsonDecode((await request.toDart).toDart) as Map;
      if (raw['ok'] != true) {
        throw OfflineStoreFailure(raw['error'] as String? ?? 'UNAVAILABLE');
      }
      return raw['value'];
    } on OfflineStoreFailure {
      rethrow;
    } catch (_) {
      throw const OfflineStoreFailure('UNAVAILABLE');
    }
  }

  @override
  Future<void> activate(OfflineIdentity identity) async {
    OfflineKey(identity, 0, 'goals', 'all').validate();
    await value(_activate(jsonEncode(identity.toJson())));
  }

  @override
  Future<SnapshotRead> read(OfflineKey key) async {
    key.validate();
    final raw = await value(_read(jsonEncode(key.toJson())));
    if (raw is! Map ||
        !const [
          'missing',
          'empty',
          'ready',
          'corrupt',
        ].contains(raw['state'])) {
      throw const OfflineStoreFailure('CORRUPT');
    }
    if (raw['state'] == 'missing' || raw['state'] == 'corrupt') {
      return SnapshotRead(raw['state']);
    }
    return SnapshotRead(
      raw['state'],
      StudySnapshot.fromJson(raw['record'] as Map, key),
    );
  }

  @override
  Future<void> writeBatch(List<StudySnapshot> snapshots) async {
    for (final s in snapshots) {
      s.validate();
    }
    await value(_write(jsonEncode(snapshots.map((s) => s.toJson()).toList())));
  }

  @override
  Future<void> clear(OfflineIdentity identity) async {
    // Bridge is bound to this active identity; activate before touching its rows.
    await activate(identity);
    await value(_clear());
  }
}
