import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:ai_study_planner_mobile/offline_store.dart';
import 'package:ai_study_planner_mobile/offline_repository.dart';
import 'package:ai_study_planner_mobile/planner_api.dart';
import 'package:ai_study_planner_mobile/plan_calendar.dart';

void main() {
  late MemoryOfflineStore store;
  late OfflineRepository repository;
  setUp(() async {
    store = MemoryOfflineStore();
    repository = OfflineRepository(store);
    await repository.initialize();
  });
  test('fresh cache is missing and offline cannot invent a fixture', () async {
    expect((await repository.read(0, 'goals', 'all')).state, 'missing');
    await expectLater(
      repository.seedMock(online: false),
      throwsA(isA<ApiFailure>()),
    );
    expect(store.records, isEmpty);
  });
  test(
    'explicit mock seed survives repository reopen and reads without network',
    () async {
      await repository.seedMock(online: true);
      final reopened = OfflineRepository(store);
      await reopened.initialize();
      final api = PlannerApi(
        base: Uri.parse('https://mock.invalid'),
        transport: OfflineMockTransport(reopened),
      );
      expect(await api.goals(), hasLength(2));
      expect(await api.studyRecords(1), hasLength(1));
      expect((await api.summary(1))['totalStudyMinutes'], 40);
      expect(reopened.lastSync, isNotNull);
      expect(reopened.hasData, isTrue);
      api.close();
    },
  );
  test(
    'FINAL, DRAFT, known absent day and unqueried month remain distinct',
    () async {
      await repository.seedMock(online: true);
      final today = planDateKey(seoulToday()), month = today.substring(0, 7);
      final finalDay = '$month-${today.endsWith('-01') ? '02' : '01'}';
      final emptyDay = '$month-${today.endsWith('-03') ? '04' : '03'}';
      expect((await repository.plan(1, today))['planStatus'], 'DRAFT');
      expect((await repository.plan(1, finalDay))['planStatus'], 'FINAL');
      expect((await repository.plan(1, emptyDay))['saved'], false);
      expect((await repository.read(1, 'day', emptyDay)).state, 'empty');
      await expectLater(
        repository.plan(1, '2099-12-01'),
        throwsA(isA<ApiFailure>()),
      );
    },
  );
  test('read results do not mutate stored FINAL snapshot', () async {
    await repository.seedMock(online: true);
    final month = planDateKey(seoulToday()).substring(0, 7);
    final raw = await repository.require(1, 'month', month) as Map;
    final finalDate = (raw['plans'] as List).first['planDate'] as String;
    final read = await repository.plan(1, finalDate);
    read['planStatus'] = 'DRAFT';
    (read['timeSlots'] as List).clear();
    final again = await repository.plan(1, finalDate);
    expect(again['planStatus'], 'FINAL');
    expect(again['timeSlots'], isNotEmpty);
  });
  test(
    'keys separate goal, date and month; real server storage disabled',
    () async {
      await repository.seedMock(online: true);
      expect((await repository.read(2, 'subjects', 'all')).state, 'missing');
      expect((await repository.read(1, 'month', '2099-01')).state, 'missing');
      expect(
        () => OfflineKey(
          const OfflineIdentity('https://real.example', 1),
          0,
          'goals',
          'all',
        ).id,
        throwsA(isA<OfflineStoreFailure>()),
      );
      expect(
        () => OfflineKey(repository.identity, 1, 'day', '2026-02-31').id,
        throwsA(isA<OfflineStoreFailure>()),
      );
    },
  );
  test(
    'nested credentials and invalid batch are rejected before any write',
    () async {
      final valid = StudySnapshot(
        OfflineKey(repository.identity, 0, 'goals', 'all'),
        [],
        DateTime.now(),
      );
      final invalid = StudySnapshot(
        OfflineKey(repository.identity, 1, 'subjects', 'all'),
        [
          {
            'goalId': 1,
            'userId': 1,
            'nested': {'csrf_token': 'secret'},
          },
        ],
        DateTime.now(),
      );
      await expectLater(
        store.writeBatch([valid, invalid]),
        throwsA(isA<OfflineStoreFailure>()),
      );
      expect(store.records, isEmpty);
    },
  );
  test(
    'user change purges previous rows and does not leak another identity',
    () async {
      await repository.seedMock(online: true);
      final old = repository.identity;
      await repository.switchMockUser();
      expect(repository.identity.userId, 2);
      expect(store.records, isEmpty);
      await expectLater(
        store.read(OfflineKey(old, 0, 'goals', 'all')),
        throwsA(isA<OfflineStoreFailure>()),
      );
    },
  );
  test(
    'logout deletes local rows but never persists CSRF or session',
    () async {
      await repository.seedMock(online: true);
      final api = PlannerApi(
        base: Uri.parse('https://mock.invalid'),
        transport: OfflineMockTransport(repository),
      );
      expect(
        jsonEncode(store.records.values.map((s) => s.toJson()).toList()),
        isNot(contains('mock-only')),
      );
      await api.logout();
      expect(store.records, isEmpty);
      expect(repository.lastSync, isNull);
      api.close();
    },
  );
  test(
    'quota failure is explicit without an in-memory production fallback',
    () async {
      store.failWith = 'QUOTA';
      await expectLater(
        repository.seedMock(online: true),
        throwsA(
          isA<OfflineStoreFailure>().having((e) => e.code, 'code', 'QUOTA'),
        ),
      );
      expect(store.records, isEmpty);
      final t = OfflineMockTransport(repository);
      await expectLater(
        t.send('GET', Uri.parse('https://mock.invalid/goals'), {}, null),
        throwsA(
          isA<ApiFailure>().having(
            (e) => e.message,
            'message',
            contains('저장 공간 부족'),
          ),
        ),
      );
    },
  );
  test('corrupt records produce an explicit read failure', () async {
    await repository.seedMock(online: true);
    final key = OfflineKey(repository.identity, 1, 'summary', 'all');
    store.records[key.id] = StudySnapshot(key, [], DateTime.now());
    await expectLater(
      repository.require(1, 'summary', 'all'),
      throwsA(isA<OfflineStoreFailure>()),
    );
  });
  test(
    'offline transport remains read-only for records, plans and AI',
    () async {
      await repository.seedMock(online: true);
      final before = jsonEncode(
        store.records.values.map((s) => s.toJson()).toList(),
      );
      final api = PlannerApi(
        base: Uri.parse('https://mock.invalid'),
        transport: OfflineMockTransport(repository),
      );
      await expectLater(
        api.saveStudyRecord(
          goalId: 1,
          subjectId: 1,
          date: '2026-11-06',
          minutes: 40,
          understanding: 3,
          focus: 3,
          fatigue: 2,
          content: 'Mock',
        ),
        throwsA(isA<ApiFailure>()),
      );
      await expectLater(
        api.saveReschedule(goalId: 1, date: '2026-11-06'),
        throwsA(isA<ApiFailure>()),
      );
      await expectLater(
        api.generateAi(
          feature: AiFeature.question,
          goalId: 1,
          question: 'Mock',
          confirmed: true,
        ),
        throwsA(isA<ApiFailure>()),
      );
      expect(
        jsonEncode(store.records.values.map((s) => s.toJson()).toList()),
        before,
      );
      api.close();
    },
  );
}
