import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/study_record_entry.dart';

class RequestTransport implements Transport {
  int posts = 0;
  bool lost = false, enabled = true, unknown = false;
  int status = 200;
  final saved = <String, Map<String, dynamic>>{};
  final keys = <String>[];
  final calls = <String>[];
  @override
  Future<Reply> send(
    String method,
    Uri uri,
    Map<String, String> headers,
    String? body,
  ) async {
    calls.add('$method ${uri.path}');
    Object value = {};
    switch (uri.path) {
      case '/auth/me':
        value = {'authenticated': true, 'userId': 1};
      case '/auth/csrf':
        value = {'headerName': 'X-CSRF-TOKEN', 'token': 'synthetic'};
      case '/subjects':
        final goal = int.parse(uri.queryParameters['goalId']!);
        value = [
          {'goalId': goal, 'userId': 1, 'subjectId': goal, 'subjectName': '가상'},
        ];
      case '/study-logs':
        if (method == 'POST') {
          posts++;
          final key = headers['Idempotency-Key']!;
          keys.add(key);
          if (status != 200) return Reply(status, '{}');
          value = saved.putIfAbsent(
            key,
            () => {
              ...jsonDecode(body!) as Map<String, dynamic>,
              'logId': posts,
              'idempotency': {
                'enabled': true,
                'state': 'COMPLETED',
                'requestKey': key,
              },
            },
          );
          if (lost) throw const ApiFailure('합성 응답 유실');
        } else {
          value = saved.values.toList();
        }
      case '/study-log-idempotency':
        value = {'enabled': enabled};
      case '/study-log-requests':
        final key = uri.queryParameters['requestKey']!;
        value = {
          'requestKey': key,
          'goalId': int.parse(uri.queryParameters['goalId']!),
          'state': !unknown && saved.containsKey(key)
              ? 'COMPLETED'
              : 'NOT_CONFIRMED',
          if (!unknown && saved.containsKey(key)) 'result': saved[key],
        };
      default:
        throw StateError('Unexpected mock request');
    }
    return Reply(200, jsonEncode(value));
  }

  @override
  void close() {}
}

PlannerApi client(RequestTransport transport, StudyRequestStore store) =>
    PlannerApi(
      base: Uri.parse('http://localhost:8080'),
      transport: transport,
      requestStore: store,
    );
Future<Map<String, dynamic>> save(
  PlannerApi api, {
  int goal = 1,
  int minutes = 40,
}) => api.saveStudyRecord(
  goalId: goal,
  subjectId: goal,
  date: '2026-11-01',
  minutes: minutes,
  understanding: 3,
  focus: 3,
  fatigue: 2,
  content: '가상 기록',
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('response loss keeps key across API recreation, blocks changed body and recovers with GET', () async {
    final store = MemoryStudyRequestStore(),
        t = RequestTransport()..lost = true;
    final first = client(t, store);
    await expectLater(
      save(first),
      throwsA(
        isA<StudySaveFailure>().having((e) => e.uncertain, 'uncertain', true),
      ),
    );
    final key = await first.pendingStudyRequest(1);
    expect(key, matches(r'^mobile_[a-f0-9]{32}$'));
    final second = client(t, store);
    await expectLater(
      save(second, minutes: 41),
      throwsA(isA<StudySaveFailure>()),
    );
    expect(t.posts, 1);
    expect(await second.pendingStudyRequest(1), key);
    final restored = await second.resolveStudyRequest(1);
    expect(restored!['studyMinutes'], 40);
    expect(t.posts, 1);
    expect(await second.pendingStudyRequest(1), isNull);
  });
  test('completed logical records use distinct new keys', () async {
    final t = RequestTransport(), api = client(t, MemoryStudyRequestStore());
    await save(api);
    await save(api);
    expect(t.posts, 2);
    expect(t.keys[0], isNot(t.keys[1]));
  });
  test(
    'unknown or disabled lookup never clears a pending key or resends',
    () async {
      final t = RequestTransport()..lost = true,
          store = MemoryStudyRequestStore(),
          api = client(t, store);
      await expectLater(save(api), throwsA(isA<StudySaveFailure>()));
      t.unknown = true;
      expect(await api.resolveStudyRequest(1), isNull);
      expect(await api.pendingStudyRequest(1), isNotNull);
      t.enabled = false;
      await expectLater(api.resolveStudyRequest(1), throwsA(isA<ApiFailure>()));
      expect(t.posts, 1);
    },
  );
  test(
    'goal B has independent intent and cannot clear goal A pending key',
    () async {
      final t = RequestTransport()..lost = true,
          store = MemoryStudyRequestStore(),
          api = client(t, store);
      await expectLater(save(api), throwsA(isA<StudySaveFailure>()));
      final a = await api.pendingStudyRequest(1);
      t.lost = false;
      await save(api, goal: 3);
      expect(await api.pendingStudyRequest(1), a);
      expect(await api.pendingStudyRequest(3), isNull);
      expect(t.keys[0], isNot(t.keys[1]));
    },
  );
  test(
    'definitive validation rejection releases intent; conflict remains pending',
    () async {
      final t = RequestTransport()..status = 400,
          api = client(t, MemoryStudyRequestStore());
      await expectLater(save(api), throwsA(isA<StudySaveFailure>()));
      expect(await api.pendingStudyRequest(1), isNull);
      t.status = 409;
      await expectLater(
        save(api),
        throwsA(
          isA<StudySaveFailure>().having(
            (e) => e.uncertain,
            'conflict uncertain',
            true,
          ),
        ),
      );
      expect(await api.pendingStudyRequest(1), isNotNull);
    },
  );
  test(
    'native channel store reopens metadata without payload or credentials',
    () async {
      final disk = <String, String>{}, messages = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(AndroidStudyRequestStore.channel, (
            call,
          ) async {
            messages.add(call);
            final args = call.arguments as Map, scope = args['scope'] as String;
            switch (call.method) {
              case 'pending':
                return disk[scope];
              case 'reserve':
                final old = disk[scope];
                disk.putIfAbsent(scope, () => args['key'] as String);
                return {'key': disk[scope], 'existing': old != null};
              case 'complete':
                if (disk[scope] == args['key']) disk.remove(scope);
                return null;
            }
            return null;
          });
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(AndroidStudyRequestStore.channel, null),
      );
      final t = RequestTransport()..lost = true;
      await expectLater(
        save(client(t, AndroidStudyRequestStore())),
        throwsA(isA<StudySaveFailure>()),
      );
      final reopened = client(t, AndroidStudyRequestStore());
      expect(await reopened.pendingStudyRequest(1), isNotNull);
      await expectLater(save(reopened), throwsA(isA<StudySaveFailure>()));
      expect(t.posts, 1);
      for (final c in messages) {
        expect(
          (c.arguments as Map).keys.every((k) => k == 'scope' || k == 'key'),
          true,
        );
        expect(jsonEncode(c.arguments), isNot(contains('가상 기록')));
        expect(jsonEncode(c.arguments), isNot(contains('synthetic')));
      }
    },
  );
  testWidgets(
    'entry recreation is locked and only explicit GET resolves success',
    (tester) async {
      final t = RequestTransport()..lost = true,
          store = MemoryStudyRequestStore();
      await expectLater(
        save(client(t, store)),
        throwsA(isA<StudySaveFailure>()),
      );
      final api = client(t, store);
      await tester.pumpWidget(
        MaterialApp(
          home: StudyRecordEntry(api: api, goalId: 1, goalName: '목표 A'),
        ),
      );
      await tester.pumpAndSettle();
      expect(t.posts, 1);
      await tester.scrollUntilVisible(
        find.byKey(const Key('entrySave')),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      expect(
        tester
            .widget<FilledButton>(find.byKey(const Key('entrySave')))
            .onPressed,
        isNull,
      );
      expect(find.byKey(const Key('entryLookup')), findsOneWidget);
      await tester.ensureVisible(find.byKey(const Key('entryLookup')));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const Key('entryLookup')).hitTestable(),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const Key('entryLookup')));
      await tester.pumpAndSettle();
      expect(t.posts, 1);
      expect(t.calls, contains('GET /study-log-requests'));
      expect(await api.pendingStudyRequest(1), isNull);
    },
  );
}
