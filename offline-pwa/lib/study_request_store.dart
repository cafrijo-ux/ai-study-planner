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
