import 'dart:convert';

class OfflineStoreFailure implements Exception {
  const OfflineStoreFailure(this.code);
  final String code;
  @override
  String toString() => '로컬 저장소 오류: $code';
}

class OfflineIdentity {
  const OfflineIdentity(this.server, this.userId);
  final String server;
  final int userId;
  Map<String, dynamic> toJson() => {'server': server, 'userId': userId};
}

class OfflineKey {
  const OfflineKey(this.identity, this.goalId, this.kind, this.period);
  final OfflineIdentity identity;
  final int goalId;
  final String kind, period;
  Map<String, dynamic> toJson() => {
    ...identity.toJson(),
    'goalId': goalId,
    'kind': kind,
    'period': period,
  };
  void validate() {
    if (identity.server != 'https://mock.invalid' ||
        identity.userId <= 0 ||
        goalId < 0 ||
        !const {
          'goals',
          'subjects',
          'month',
          'day',
          'logs',
          'logIndex',
          'summary',
        }.contains(kind) ||
        ((kind == 'goals') != (goalId == 0))) {
      throw const OfflineStoreFailure('REAL_DATA_DISABLED');
    }
    if (kind == 'month') {
      if (!RegExp(r'^\d{4}-(0[1-9]|1[0-2])$').hasMatch(period)) {
        throw const OfflineStoreFailure('INVALID_PERIOD');
      }
    } else if (kind == 'day' || kind == 'logs') {
      final date = DateTime.tryParse(period);
      if (date == null || date.toIso8601String().substring(0, 10) != period) {
        throw const OfflineStoreFailure('INVALID_PERIOD');
      }
    } else if (period != 'all') {
      throw const OfflineStoreFailure('INVALID_PERIOD');
    }
  }

  String get id {
    validate();
    return jsonEncode([identity.server, identity.userId, goalId, kind, period]);
  }
}

class StudySnapshot {
  const StudySnapshot(this.key, this.payload, this.syncedAt);
  final OfflineKey key;
  final dynamic payload;
  final DateTime syncedAt;
  Map<String, dynamic> toJson() => {
    'schema': 1,
    'key': key.toJson(),
    'source': 'MOCK_FIXTURE',
    'syncedAt': syncedAt.toUtc().toIso8601String(),
    'payload': payload,
  };
  void validate() {
    key.validate();
    void inspect(dynamic value) {
      if (value is Map) {
        for (final entry in value.entries) {
          final name = entry.key.toString().toLowerCase().replaceAll(
            RegExp('[^a-z0-9]'),
            '',
          );
          if (const {
            'password',
            'passwd',
            'cookie',
            'cookies',
            'session',
            'jsessionid',
            'csrf',
            'csrftoken',
            'token',
            'apikey',
            'authorization',
          }.contains(name)) {
            throw const OfflineStoreFailure('SENSITIVE_FIELD');
          }
          inspect(entry.value);
        }
      } else if (value is List) {
        for (final v in value) {
          inspect(v);
        }
      }
    }

    inspect(payload);
    final list = const {
      'goals',
      'subjects',
      'logs',
      'logIndex',
    }.contains(key.kind);
    if (list ? payload is! List : payload is! Map) {
      throw const OfflineStoreFailure('CORRUPT');
    }
    if (key.kind == 'day' &&
        (payload['goalId'] != key.goalId ||
            payload['planDate'] != key.period ||
            (payload['saved'] != false &&
                !const ['FINAL', 'DRAFT'].contains(payload['planStatus'])))) {
      throw const OfflineStoreFailure('CORRUPT');
    }
    if (key.kind == 'month' &&
        (payload['goalId'] != key.goalId ||
            payload['month'] != key.period ||
            payload['plans'] is! List ||
            (payload['plans'] as List).any(
              (p) =>
                  p is! Map ||
                  p['goalId'] != key.goalId ||
                  !const ['FINAL', 'DRAFT'].contains(p['planStatus']) ||
                  !(p['planDate'] as String? ?? '').startsWith(
                    '${key.period}-',
                  ),
            ))) {
      throw const OfflineStoreFailure('CORRUPT');
    }
    if (key.kind == 'logs' &&
        (payload as List).any(
          (p) =>
              p is! Map ||
              p['userId'] != key.identity.userId ||
              p['studyDate'] != key.period,
        )) {
      throw const OfflineStoreFailure('CORRUPT');
    }
    if (key.kind == 'subjects' &&
        (payload as List).any(
          (p) =>
              p is! Map ||
              p['userId'] != key.identity.userId ||
              p['goalId'] != key.goalId,
        )) {
      throw const OfflineStoreFailure('CORRUPT');
    }
    if (jsonEncode(toJson()).length > 2 * 1024 * 1024) {
      throw const OfflineStoreFailure('TOO_LARGE');
    }
  }

  static StudySnapshot fromJson(Map raw, OfflineKey expected) {
    if (raw['schema'] != 1 ||
        raw['source'] != 'MOCK_FIXTURE' ||
        jsonEncode(raw['key']) != jsonEncode(expected.toJson()) ||
        raw['syncedAt'] is! String) {
      throw const OfflineStoreFailure('CORRUPT');
    }
    final date = DateTime.tryParse(raw['syncedAt']);
    if (date == null) throw const OfflineStoreFailure('CORRUPT');
    final result = StudySnapshot(expected, raw['payload'], date);
    result.validate();
    return result;
  }
}

class SnapshotRead {
  const SnapshotRead(this.state, [this.snapshot]);
  final String state; // missing, empty, ready, corrupt
  final StudySnapshot? snapshot;
}

abstract interface class OfflineStore {
  Future<void> activate(OfflineIdentity identity);
  Future<SnapshotRead> read(OfflineKey key);
  Future<void> writeBatch(List<StudySnapshot> snapshots);
  Future<void> clear(OfflineIdentity identity);
}

/// VM tests only; production Web uses IndexedDB, never a silent memory fallback.
class MemoryOfflineStore implements OfflineStore {
  final records = <String, StudySnapshot>{};
  OfflineIdentity? active;
  String? failWith;
  void check() {
    if (failWith != null) throw OfflineStoreFailure(failWith!);
  }

  @override
  Future<void> activate(OfflineIdentity identity) async {
    check();
    OfflineKey(identity, 0, 'goals', 'all').validate();
    if (active != null &&
        (active!.server != identity.server ||
            active!.userId != identity.userId)) {
      records.clear();
    }
    active = identity;
  }

  void owner(OfflineKey key) {
    if (active?.server != key.identity.server ||
        active?.userId != key.identity.userId) {
      throw const OfflineStoreFailure('IDENTITY_CHANGED');
    }
  }

  @override
  Future<SnapshotRead> read(OfflineKey key) async {
    check();
    owner(key);
    final stored = records[key.id];
    final value = stored == null
        ? null
        : StudySnapshot.fromJson(
            jsonDecode(jsonEncode(stored.toJson())) as Map,
            key,
          );
    if (value == null) return const SnapshotRead('missing');
    try {
      value.validate();
    } catch (_) {
      return const SnapshotRead('corrupt');
    }
    return SnapshotRead(
      value.payload is List && (value.payload as List).isEmpty ||
              key.kind == 'day' && value.payload['saved'] == false
          ? 'empty'
          : 'ready',
      value,
    );
  }

  @override
  Future<void> writeBatch(List<StudySnapshot> snapshots) async {
    check();
    for (final s in snapshots) {
      s.validate();
      owner(s.key);
    }
    for (final s in snapshots) {
      records[s.key.id] = StudySnapshot(
        s.key,
        jsonDecode(jsonEncode(s.payload)),
        s.syncedAt,
      );
    }
  }

  @override
  Future<void> clear(OfflineIdentity identity) async {
    check();
    records.removeWhere(
      (_, s) =>
          s.key.identity.server == identity.server &&
          s.key.identity.userId == identity.userId,
    );
  }
}
