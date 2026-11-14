import 'planner_api.dart';

/// Deliberately NOT a memory fallback. No unverified persistent web writes.
class WebStudyRequestStore implements StudyRequestStore {
  @override
  bool get persistent => false;
  @override
  Future<String?> pending(String scope) async => null;
  @override
  Future<({String key, bool existing})> reserve(String scope) async =>
      throw const ApiFailure('웹 요청 키의 영속성 검증 전에는 실제 저장·AI 호출을 허용하지 않습니다.');
  @override
  Future<void> complete(String scope, String key) async =>
      throw const ApiFailure('웹 요청 키 저장소는 아직 활성화되지 않았습니다.');
}
