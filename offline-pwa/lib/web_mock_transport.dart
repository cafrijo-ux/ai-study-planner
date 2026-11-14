import 'dart:convert';

import 'plan_calendar.dart';
import 'planner_api.dart';

/// In-memory display data only. No socket, HTTP, disk or provider client.
class WebMockTransport implements Transport, MockDataTransport {
  final calls = <(String, String)>[];
  final String today = planDateKey(seoulToday());

  Map<String, dynamic> plan(String date) => {
    'goalId': 1,
    'planDate': date,
    'scheduleDate': date,
    'saved': true,
    'planSaved': true,
    'planStatus': 'DRAFT',
    'studyMinutes': 100,
    'carryoverMinutes': 9,
    'newStudyMinutes': 91,
    'isScheduleValid': true,
    'hasConflict': false,
    'subjectPlans': [
      {
        'subjectId': 1,
        'subjectName': '[Mock] 운영체제',
        'studyMinutes': 100,
        'carryoverMinutes': 9,
        'newStudyMinutes': 91,
      },
    ],
    'timeSlots': [
      {
        'subjectId': 1,
        'subjectName': '[Mock] 운영체제',
        'type': '공부',
        'allocationType': 'CATCHUP',
        'activity': '가상 복습',
        'minutes': 9,
        'startTime': '$date 19:00',
        'endTime': '$date 19:09',
      },
      {
        'subjectId': 1,
        'subjectName': '[Mock] 운영체제',
        'type': '공부',
        'allocationType': 'NEW',
        'activity': '가상 개념 학습',
        'minutes': 91,
        'startTime': '$date 19:09',
        'endTime': '$date 20:40',
      },
    ],
  };

  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    calls.add((method, uri.path));
    if (method != 'GET' && uri.path != '/auth/logout') {
      throw const ApiFailure('Mock 화면은 실제 저장·AI 생성을 하지 않습니다.');
    }
    final data = switch (uri.path) {
      '/auth/csrf' => {'headerName': 'X-CSRF-TOKEN', 'token': 'mock-only'},
      '/auth/me' => {'authenticated': true, 'userId': 1},
      '/auth/logout' => {'message': 'Mock 화면 종료'},
      '/goals' => [
        {'goalId': 1, 'goalName': '[Mock] 운영체제 학습'},
      ],
      '/subjects' => [
        {
          'goalId': 1,
          'subjectId': 1,
          'userId': 1,
          'subjectName': '[Mock] 운영체제',
        },
      ],
      '/analysis-summary' => {
        'goalId': 1,
        'scope': 'CURRENT_GOAL',
        'goalName': '[Mock] 운영체제 학습',
        'recordCount': 1,
        'totalStudyMinutes': 40,
        'averageFocus': 3,
        'averageUnderstanding': 3,
      },
      '/study-reschedule-calendar' => {
        'goalId': 1,
        'month': uri.queryParameters['month'],
        'plans': [
          plan('${uri.queryParameters['month']}-01'),
          if (today.startsWith(uri.queryParameters['month']!) &&
              !today.endsWith('-01'))
            plan(today),
        ],
      },
      '/study-reschedule-saved' => plan(uri.queryParameters['date']!),
      '/study-logs' => [
        {
          'logId': 1,
          'subjectId': 1,
          'userId': 1,
          'studyDate': today,
          'studyMinutes': 40,
          'studyContent': '[Mock] 프로세스와 스레드 복습',
          'focusScore': 3,
          'understandingScore': 3,
          'fatigueScore': 2,
        },
      ],
      '/ai-responses' || '/study-questions' => <Object>[],
      '/study-question/capabilities' => {
        'goalScopedResponse': true,
        'gemini': {
          'enabled': false,
          'configured': false,
          'model': 'gemini-mock',
        },
        'gpt': {'enabled': false, 'configured': false, 'model': 'gpt-4o-mini'},
      },
      '/llm-config-check' => {
        'provider': 'gemini',
        'model': 'gemini-mock',
        'demoMode': true,
        'ready': false,
      },
      '/gpt-weekly-coaching/preview' => {
        'goalId': 1,
        'model': 'gpt-4o-mini',
        'enabled': false,
        'apiKeyConfigured': false,
        'dataReady': false,
      },
      '/ai-generation-policy' => {'enabled': false},
      '/study-log-idempotency' => {'enabled': false},
      _ => throw const ApiFailure('이 동작은 웹 Mock 2단계에서 지원하지 않습니다.'),
    };
    return Reply(200, jsonEncode(data));
  }

  @override
  void close() {}
}

PlannerApi createWebMockApi() => PlannerApi(
  base: Uri.parse('https://mock.invalid'),
  transport: WebMockTransport(),
);
