import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';

import 'ai_generation_test.dart' show AiMock, apiFor;

class DeadlineAiMock extends AiMock implements DeadlineTransport {
  final deadlines = <Duration>[];
  @override
  Future<Reply> sendWithDeadline(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
    Duration deadline,
  ) {
    deadlines.add(deadline);
    return super.send(method, uri, headers, body);
  }
}

void main() {
  test(
    'transport split preserves 15s reads and 90s AI without retries',
    () async {
      final mock = DeadlineAiMock();
      final api = apiFor(mock);
      addTearDown(api.close);
      await api.generateAi(
        feature: AiFeature.question,
        goalId: 1,
        question: '가상 질문',
        subject: '가상 과목',
        confirmed: true,
      );
      expect(mock.deadlines.last, const Duration(seconds: 90));
      expect(
        mock.deadlines.take(mock.deadlines.length - 1),
        everyElement(const Duration(seconds: 15)),
      );
      expect(mock.generated, 1);
      expect(mock.calls.where((c) => c.$2.path == '/study-question').length, 1);
    },
  );
}
