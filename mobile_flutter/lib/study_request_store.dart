part of 'planner_api.dart';

String newStudyRequestKey() {
  final random = Random.secure();
  return 'mobile_${List.generate(16, (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0')).join()}';
}

abstract class StudyRequestStore {
  bool get persistent;
  Future<String?> pending(String scope);
  Future<({String key, bool existing})> reserve(String scope);
  Future<void> complete(String scope, String key);
}

class MemoryStudyRequestStore implements StudyRequestStore {
  final Map<String, String> entries = {};
  @override
  bool get persistent => false;
  @override
  Future<String?> pending(String scope) async => entries[scope];
  @override
  Future<({String key, bool existing})> reserve(String scope) async {
    final old = entries[scope];
    if (old != null) return (key: old, existing: true);
    final key = newStudyRequestKey();
    entries[scope] = key;
    return (key: key, existing: false);
  }

  @override
  Future<void> complete(String scope, String key) async {
    if (entries[scope] == key) entries.remove(scope);
  }
}

/// Android AtomicFile in app-private files. No auth or study contents are stored.
class AndroidStudyRequestStore implements StudyRequestStore {
  static const channel = MethodChannel('ai_study_planner/study_requests');
  @override
  bool get persistent => true;
  Future<dynamic> _call(String method, String scope, [String? key]) async {
    try {
      return await channel.invokeMethod<dynamic>(method, {
        'scope': scope,
        'key': ?key,
      });
    } catch (_) {
      throw const ApiFailure('기기 요청 키 보관을 확인할 수 없습니다. 새 전송 전에 기록을 확인하세요.');
    }
  }

  bool _valid(dynamic key) =>
      key is String && RegExp(r'^[a-z0-9][a-z0-9_-]{15,79}$').hasMatch(key);
  @override
  Future<String?> pending(String scope) async {
    final value = await _call('pending', scope);
    if (value == null) return null;
    if (!_valid(value)) throw const ApiFailure('기기 요청 키 형식이 올바르지 않습니다.');
    return value as String;
  }

  @override
  Future<({String key, bool existing})> reserve(String scope) async {
    final value = await _call('reserve', scope, newStudyRequestKey());
    if (value is! Map || !_valid(value['key']) || value['existing'] is! bool) {
      throw const ApiFailure('기기 요청 키 보관 응답이 올바르지 않습니다.');
    }
    return (key: value['key'] as String, existing: value['existing'] as bool);
  }

  @override
  Future<void> complete(String scope, String key) async {
    await _call('complete', scope, key);
  }
}
