package ai_study_planner;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/** Read-only coaching evidence. Does not calculate or change scheduling/carryover balances. */
final class WeeklyLearningEvidence {
    private WeeklyLearningEvidence() {}
    static Integer minutes(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() > Integer.MAX_VALUE) return null;
        return n.intValue();
    }
    static boolean validPlan(Map<String, Object> plan) {
        return plan != null && !Boolean.FALSE.equals(plan.get("isScheduleValid"))
                && !Boolean.TRUE.equals(plan.get("hasConflict")) && minutes(plan.get("studyMinutes")) != null;
    }
    static Double average(List<StudyLog> logs, Function<StudyLog, Integer> score) {
        var values = logs.stream().map(score).filter(v -> v != null && v >= 1 && v <= 5).toList();
        return values.isEmpty() ? null : Math.round(values.stream().mapToInt(Integer::intValue).average().orElseThrow() * 10) / 10.0;
    }
    static Map<String, Object> metrics(List<StudyLog> logs) {
        Map<String, Object> m = new LinkedHashMap<>();
        var known = logs.stream().map(StudyLog::getStudyMinutes).filter(v -> v != null && v >= 0).toList();
        m.put("recordCount", logs.size());
        m.put("studyMinutes", known.isEmpty() ? null : known.stream().mapToInt(Integer::intValue).sum());
        m.put("timeRecordCount", known.size());
        m.put("averageFocus", average(logs, StudyLog::getFocusScore));
        m.put("averageUnderstanding", average(logs, StudyLog::getUnderstandingScore));
        m.put("weakTopics", logs.stream().map(StudyLog::getDifficultTopic).filter(Objects::nonNull)
                .filter(s -> !s.isBlank()).map(s -> s.substring(0, Math.min(s.length(), 150))).distinct().limit(5).toList());
        return m;
    }
    static Map<String, Object> trend(List<Map<String, Object>> days, String key) {
        List<Map<String, Object>> known = days.stream().filter(d -> d.get(key) instanceof Number).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("observedDays", known.size());
        result.put("firstDate", known.isEmpty() ? null : known.get(0).get("date"));
        result.put("lastDate", known.isEmpty() ? null : known.get(known.size()-1).get("date"));
        result.put("firstAverage", known.isEmpty() ? null : known.get(0).get(key));
        result.put("lastAverage", known.isEmpty() ? null : known.get(known.size()-1).get(key));
        result.put("delta", known.size() < 2 ? null : Math.round((((Number)known.get(known.size()-1).get(key)).doubleValue()
                - ((Number)known.get(0).get(key)).doubleValue()) * 10) / 10.0);
        result.put("basis", "서로 다른 기록 날짜의 첫날/마지막 날 평균 비교. 미기록 날짜는 0점으로 보지 않음");
        return result;
    }
    static Map<String, Object> build(List<Subject> subjects, List<StudyLog> logs, LocalDate start, LocalDate end,
                                     Map<LocalDate, Map<String, Object>> finals) {
        Map<LocalDate, List<StudyLog>> byDay = new TreeMap<>();
        Map<Long, List<StudyLog>> bySubject = new HashMap<>();
        for (StudyLog log : logs) {
            byDay.computeIfAbsent(log.getStudyDate(), ignored -> new ArrayList<>()).add(log);
            bySubject.computeIfAbsent(log.getSubjectId(), ignored -> new ArrayList<>()).add(log);
        }
        List<Map<String, Object>> days = new ArrayList<>(), subjectMetrics = new ArrayList<>();
        int finalDays = 0, comparableDays = 0, planned = 0, comparedPlan = 0, comparedActual = 0;
        List<String> missing = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            var records = byDay.getOrDefault(day, List.of());
            Map<String, Object> metrics = metrics(records); metrics.put("date", day.toString());
            Map<String, Object> plan = finals.get(day);
            Integer planMinutes = validPlan(plan) ? minutes(plan.get("studyMinutes")) : null;
            metrics.put("plannedStudyMinutes", planMinutes);
            metrics.put("planBasis", planMinutes == null ? "UNKNOWN" : "FINAL");
            metrics.put("recordStatus", records.isEmpty() ? "NO_RECORD" : "RECORDED");
            if (planMinutes != null) {
                finalDays++; planned += planMinutes;
                if (metrics.get("studyMinutes") instanceof Number actual && (int)metrics.get("timeRecordCount") == records.size()) {
                    comparableDays++; comparedPlan += planMinutes; comparedActual += actual.intValue();
                } else missing.add(day.toString());
            }
            days.add(metrics);
        }
        for (Subject subject : subjects) {
            var records = bySubject.getOrDefault(subject.getSubjectId(), List.of());
            Map<String, Object> metrics = metrics(records);
            metrics.put("subjectId", subject.getSubjectId()); metrics.put("subjectName", subject.getSubjectName());
            metrics.put("recordStatus", records.isEmpty() ? "NO_RECORD" : "RECORDED");
            subjectMetrics.add(metrics);
        }
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("finalPlanDays", finalDays);
        comparison.put("knownFinalPlannedMinutes", finalDays == 0 ? null : planned);
        comparison.put("comparableDays", comparableDays);
        comparison.put("comparablePlannedMinutes", comparableDays == 0 ? null : comparedPlan);
        comparison.put("comparableActualMinutes", comparableDays == 0 ? null : comparedActual);
        comparison.put("daysWithoutCompleteTimeRecords", missing);
        comparison.put("note", "계획과 기록이 모두 확인된 날짜끼리만 비교합니다. 기록 없음은 미공부가 아니며, 이 비교로 확정 부족분을 새로 계산하지 않습니다. 오늘 기록은 진행 중일 수 있습니다.");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dailyMetrics", days); result.put("subjectMetrics", subjectMetrics);
        result.put("focusTrend", trend(days, "averageFocus")); result.put("understandingTrend", trend(days, "averageUnderstanding"));
        result.put("weeklyPlanComparison", comparison);
        result.put("recordedDays", byDay.size());
        result.put("dataLimitations", "미입력·누락 값은 정보 없음입니다. 기록에 없는 성과, 시험 점수, 주간 미공부를 추정하지 않습니다. 코칭은 권고이며 FINAL 계획을 변경하지 않습니다.");
        return result;
    }
}
