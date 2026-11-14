package ai_study_planner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
public class StudyPlanController {

    private final GoalRepository goalRepository;
    private final SubjectRepository subjectRepository;
    private final StudyLogRepository studyLogRepository;
    private final JdbcTemplate jdbcTemplate;
    private final StudySavedPlanService savedPlanService;
    private static final long USER_ID = 1L;
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    public StudyPlanController(
            GoalRepository goalRepository,
            SubjectRepository subjectRepository,
            StudyLogRepository studyLogRepository,
            JdbcTemplate jdbcTemplate,
            StudySavedPlanService savedPlanService
    ) {
        this.goalRepository = goalRepository;
        this.subjectRepository = subjectRepository;
        this.studyLogRepository = studyLogRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.savedPlanService = savedPlanService;
    }

    // 기존 Java 호출과의 호환을 위해 파라미터 없는 호출도 유지합니다.
    public Map<String, Object> getStudyPlan() {
        return getStudyPlan(null, null, null, null);
    }

    // 예: /study-plan?startTime=19:00&endTime=23:00&busyStart=20:00&busyEnd=20:30
    // 시작 시간을 지정하지 않으면 테스트용 기본값 19:00을 사용합니다.
    // busyStart/busyEnd는 아직 캘린더 연동 전이므로 수동으로 전달하는 '다른 일정' 1개입니다.
    // 기존 StudyProgressController 등 Java 내부 호출과의 호환을 유지합니다.
    public Map<String, Object> getStudyPlan(String startTime, String endTime,
                                             String busyStart, String busyEnd) {
        return getSavedOrGenerated(LocalDate.now(KOREA), startTime, endTime, busyStart, busyEnd);
    }

    // ?date=YYYY-MM-DD 로 특정 날짜의 계획을 조회할 수도 있습니다.
    @GetMapping("/study-plan")
    public Map<String, Object> getStudyPlan(
            @RequestParam(name = "startTime", required = false) String startTime,
            @RequestParam(name = "endTime", required = false) String endTime,
            @RequestParam(name = "busyStart", required = false) String busyStart,
            @RequestParam(name = "busyEnd", required = false) String busyEnd,
            @RequestParam(name = "date", required = false) String date
    ) {
        LocalDate targetDate;
        try {
            targetDate = blank(date) ? LocalDate.now(KOREA) : LocalDate.parse(date);
        } catch (DateTimeException ex) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"계획 날짜는 YYYY-MM-DD 형식으로 입력하세요.");
        }
        return getSavedOrGenerated(targetDate, startTime, endTime, busyStart, busyEnd);
    }

    // 같은 날짜/목표로 저장한 검증된 시간표를 우선 사용합니다.
    // 단, 시간 입력을 변경했거나 임시 저장 뒤 원본 공부일이 종료되었다면 새로 계산합니다.
    // 조회(GET)에서는 DB를 수정하지 않습니다.
    private Map<String, Object> getSavedOrGenerated(LocalDate targetDate,
            String startTime, String endTime, String busyStart, String busyEnd) {
        List<Goal> goals = GoalSelection.goals(goalRepository.findAll()).stream()
                .filter(goal -> Objects.equals(goal.getUserId(), USER_ID)).toList();
        if (!goals.isEmpty()) {
            Goal latest = goals.stream().max(Comparator.comparing(Goal::getGoalId)).orElseThrow();
            StudySavedPlanService.SavedPlan saved =
                    savedPlanService.find(latest.getGoalId(), targetDate);
            if (saved != null && saved.usableNow()
                    && saved.sameInputs(startTime, endTime, busyStart, busyEnd)) {
                return new LinkedHashMap<>(saved.plan());
            }
            Map<String, Object> recalculated = buildStudyPlan(targetDate,
                    startTime, endTime, busyStart, busyEnd, false);
            if (saved != null) {
                recalculated.put("planSaved", false);
                recalculated.put("savedSnapshotExists", true);
                recalculated.put("savedPlanNotice", !saved.usableNow()
                        ? "이전에 임시 저장한 계획이 더는 유효하지 않습니다. 최신 기록으로 다시 저장하세요."
                        : "시간 입력이 저장된 계획과 달라 새로 계산했습니다. 이 결과는 아직 저장되지 않았습니다.");
            }
            return recalculated;
        }
        return buildStudyPlan(targetDate, startTime, endTime, busyStart, busyEnd, false);
    }

    // 테스트 전용: 오늘의 기록이 이대로 마감된다고 가정한 내일 시간표 미리보기.
    // 실제 DB나 저장된 시간표는 변경하지 않습니다.
    @GetMapping("/study-reschedule-preview")
    public Map<String, Object> previewTomorrowPlan() {
        LocalDate today = LocalDate.now(KOREA);
        // 다음 날 별도 저장된 시간 설정이 있다면 우선합니다.
        // 없다면 오늘 저장한 시간대를 내일 미리보기의 기본값으로 사용합니다.
        List<Map<String, Object>> settings = jdbcTemplate.queryForList(
                "SELECT start_time, end_time, busy_start, busy_end "
                + "FROM study_schedule_settings WHERE user_id = ? AND schedule_date = ?",
                USER_ID, Date.valueOf(today.plusDays(1)));
        if (settings.isEmpty()) {
            settings = jdbcTemplate.queryForList(
                    "SELECT start_time, end_time, busy_start, busy_end "
                    + "FROM study_schedule_settings WHERE user_id = ? AND schedule_date = ?",
                    USER_ID, Date.valueOf(today));
        }
        String start = null, end = null, busyStart = null, busyEnd = null;
        if (!settings.isEmpty()) {
            Map<String, Object> stored = settings.get(0);
            start = labelTime(stored.get("start_time"));
            end = labelTime(stored.get("end_time"));
            busyStart = labelTime(stored.get("busy_start"));
            busyEnd = labelTime(stored.get("busy_end"));
        }
        Map<String, Object> preview = buildStudyPlan(today.plusDays(1),
                start, end, busyStart, busyEnd, true);
        // 저장 단계에서도 미리보기에 적용한 일정을 그대로 사용합니다. 빈 값은 '다른 일정 없음'입니다.
        preview.put("busyStart", busyStart == null ? "" : busyStart);
        preview.put("busyEnd", busyEnd == null ? "" : busyEnd);
        preview.put("previewMode", true);
        preview.put("previewNotice", "미리보기: 오늘 공부가 현재 기록대로 끝난다고 가정한 내일 계획입니다. "
                + "오늘 공부 기록이 추가되면 실제 내일 계획은 달라질 수 있습니다. DB에는 저장하지 않았습니다.");
        return preview;
    }

    private String labelTime(Object value) {
        LocalTime clock = readSqlTime(value);
        return clock == null ? null : clock.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private Map<String, Object> buildStudyPlan(LocalDate targetDate,
            String startTime, String endTime, String busyStart, String busyEnd,
            boolean previewMode) {
        List<Goal> goals = GoalSelection.goals(goalRepository.findAll()).stream()
                .filter(goal -> Objects.equals(goal.getUserId(), USER_ID)).toList();
        Map<String, Object> result = new LinkedHashMap<>();

        if (goals.isEmpty()) {
            result.put("message", "학습 목표가 없어 학습 계획을 생성할 수 없습니다.");
            result.put("nextStep", "먼저 학습 목표 화면에서 목표를 입력하세요.");
            return result;
        }

        // findAll()의 반환 순서는 보장되지 않으므로 가장 큰 목표 ID를 최신 목표로 사용합니다.
        // 현재 프로젝트는 단일 사용자 기준입니다.
        Goal latestGoal = goals.stream()
                .max(Comparator.comparing(Goal::getGoalId))
                .orElseThrow();

        // 이전 목표(goal_id 6,7,8 등)의 동일한 과목이 시간표에 섞이지 않도록 합니다.
        List<Subject> subjects = subjectRepository.findAll().stream()
                .filter(subject -> Objects.equals(subject.getGoalId(), latestGoal.getGoalId()))
                .filter(subject -> Objects.equals(subject.getUserId(), USER_ID))
                .toList();

        if (subjects.isEmpty()) {
            result.put("goalId", latestGoal.getGoalId());
            result.put("message", "현재 학습 목표에 등록된 과목이 없어 학습 계획을 생성할 수 없습니다.");
            result.put("nextStep", "현재 목표에 과목을 추가한 뒤 다시 실행하세요.");
            return result;
        }

        // 이전 목표의 공부 기록으로 현재 목표가 달성/미달 처리되지 않도록 과목 ID로 제한합니다.
        java.util.Set<Long> subjectIds = subjects.stream().map(Subject::getSubjectId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        List<StudyLog> logs = studyLogRepository.findAll().stream()
                .filter(log -> Objects.equals(log.getUserId(), USER_ID))
                .filter(log -> subjectIds.contains(log.getSubjectId()))
                .toList();

        LocalDate today = targetDate;

        Integer configuredMinutes = latestGoal.getDailyStudyTime();
        int dailyStudyTime = configuredMinutes != null && configuredMinutes > 0
                ? Math.min(configuredMinutes, 1440) : 90;
        String timeNotice = (configuredMinutes == null || configuredMinutes <= 0)
                ? "하루 공부 시간이 없어 임시로 90분을 적용했습니다."
                : configuredMinutes > 1440
                ? "하루 공부 시간은 최대 1440분으로 제한했습니다."
                : "설정된 하루 공부 가능 시간을 사용했습니다.";

        // 실제 시각 구간과 다른 일정(선택)을 먼저 검증합니다.
        // 화면의 '하루 공부 가능 시간'은 휴식 포함 총 예산입니다.
        LocalTime startClock;
        LocalDateTime windowStart;
        LocalDateTime windowEnd;
        LocalDateTime occupiedStart = null;
        LocalDateTime occupiedEnd = null;
        try {
            startClock = LocalTime.parse(blank(startTime) ? "19:00" : startTime,
                    DateTimeFormatter.ofPattern("HH:mm"));
            windowStart = LocalDateTime.of(today, startClock);
            if (blank(endTime)) {
                windowEnd = windowStart.plusMinutes(dailyStudyTime);
            } else {
                LocalTime endClock = LocalTime.parse(endTime, DateTimeFormatter.ofPattern("HH:mm"));
                windowEnd = LocalDateTime.of(today, endClock);
                if (!windowEnd.isAfter(windowStart)) {
                    result.put("message", "종료 시간은 시작 시간보다 늦어야 합니다. 같은 날짜 안의 시간대를 입력하세요.");
                    return result;
                }
            }

            if (blank(busyStart) != blank(busyEnd)) {
                result.put("message", "다른 일정은 busyStart와 busyEnd를 함께 입력해야 합니다.");
                return result;
            }
            if (!blank(busyStart)) {
                occupiedStart = LocalDateTime.of(today, LocalTime.parse(busyStart,
                        DateTimeFormatter.ofPattern("HH:mm")));
                occupiedEnd = LocalDateTime.of(today, LocalTime.parse(busyEnd,
                        DateTimeFormatter.ofPattern("HH:mm")));
                if (!occupiedStart.isBefore(occupiedEnd)
                        || occupiedStart.isBefore(windowStart) || occupiedEnd.isAfter(windowEnd)) {
                    result.put("message", "다른 일정의 시작/종료 시각은 공부 시간대 안에 올바르게 입력해야 합니다.");
                    return result;
                }
            }
        } catch (DateTimeException ex) {
            result.put("message", "시간 형식이 올바르지 않습니다. HH:mm 형식으로 입력하세요. 예: 19:00");
            return result;
        }

        int windowMinutes = (int) ChronoUnit.MINUTES.between(windowStart, windowEnd);
        int occupiedMinutes = occupiedStart == null ? 0
                : (int) ChronoUnit.MINUTES.between(occupiedStart, occupiedEnd);
        int usableWindowMinutes = windowMinutes - occupiedMinutes;
        if (usableWindowMinutes <= 0) {
            result.put("message", "선택한 시간대에 배치할 수 있는 공부 시간이 없습니다.");
            return result;
        }
        int effectiveDailyMinutes = Math.min(dailyStudyTime, usableWindowMinutes);
        int breakMinutes = (effectiveDailyMinutes / 60) * 10;
        int availableStudyMinutes = effectiveDailyMinutes - breakMinutes;

        LocalDate examDate = latestGoal.getExamDate();
        Long daysLeft = examDate == null ? null : ChronoUnit.DAYS.between(today, examDate);
        String deadlineStatus;
        if (daysLeft == null) {
            deadlineStatus = "시험일이 지정되지 않았습니다.";
        } else if (daysLeft < 0) {
            deadlineStatus = "시험일이 지났습니다. 목표 날짜를 확인하세요.";
        } else if (daysLeft == 0) {
            deadlineStatus = "오늘이 시험일입니다. 새로운 학습보다 핵심 요약과 오답 복습을 우선하세요.";
        } else if (daysLeft <= 3) {
            deadlineStatus = "시험이 임박했습니다(D-" + daysLeft + "). 오답 및 핵심 개념 위주로 공부하세요.";
        } else {
            deadlineStatus = "시험일까지 " + daysLeft + "일 남았습니다.";
        }

        // 난이도 / 중요도 / 자신감으로 우선순위를 정합니다.
        List<Subject> prioritized = new ArrayList<>(subjects);
        prioritized.sort(Comparator.comparingInt(this::priorityWeight).reversed());

        // 최소 20분 정도의 단위를 확보할 수 있는 과목부터 오늘 계획에 포함합니다.
        // 가용 시간이 아주 짧으면 가장 중요한 과목 하나에 집중합니다.
        // 확정된 이전 학습일의 미달 시간을 오늘의 총 공부 시간 안에서 우선 배정합니다.
        // 이전 날짜의 시간표가 저장되어 있어야 미달분을 계산할 수 있습니다.
        Carryover carryover = calculateCarryover(today, latestGoal, logs, previewMode, subjects);
        int carryoverMinutes = Math.min(availableStudyMinutes,
                carryover.todayRecommendedMinutes());
        Map<Long, Integer> catchupAllocations = allocateCatchup(carryover.bySubject(), carryoverMinutes);
        int newStudyMinutes = availableStudyMinutes - carryoverMinutes;
        int maxSubjectsToday = Math.max(1, newStudyMinutes / 20);
        int selectedCount = newStudyMinutes == 0 ? 0 : Math.min(prioritized.size(), maxSubjectsToday);
        List<Subject> selected = prioritized.subList(0, selectedCount);
        int[] allocations = allocateMinutes(selected, newStudyMinutes);
        Map<Long, Integer> newAllocations = new HashMap<>();
        for (int i = 0; i < selected.size(); i++) newAllocations.put(selected.get(i).getSubjectId(), allocations[i]);
        List<Subject> assigned = prioritized.stream().filter(subject ->
                catchupAllocations.getOrDefault(subject.getSubjectId(), 0) > 0
                || newAllocations.getOrDefault(subject.getSubjectId(), 0) > 0).toList();

        List<Map<String, Object>> subjectPlans = new ArrayList<>();
        List<String> orderParts = new ArrayList<>();
        List<String> timeParts = new ArrayList<>();
        List<Session> sessions = new ArrayList<>();

        // 이월 학습이 새 학습보다 먼저 나오도록 실제 시간표 세션의 맨 앞에 추가합니다.
        // 과목 ID로 확인한 잔액에만 배정합니다. 과목 미확인 잔액은 배정하지 않습니다.
        for (Subject subject : assigned) {
            int minutes = catchupAllocations.getOrDefault(subject.getSubjectId(), 0);
            if (minutes == 0) continue;
            sessions.add(new Session("공부", subject.getSubjectId(), subject.getSubjectName(),
                    "미완료 학습 보충", "CATCHUP", minutes));
            orderParts.add(subject.getSubjectName() + "(미완료 보충 " + minutes + "분)");
            timeParts.add(subject.getSubjectName() + ": 미완료 학습 보충 " + minutes + "분");
        }

        for (Subject subject : assigned) {
            int minutes = newAllocations.getOrDefault(subject.getSubjectId(), 0);
            boolean basicFirst = needsBasicFirst(subject);
            int first = minutes >= 3 ? (minutes * 5 / 10) : minutes;
            int second = minutes >= 3 ? (minutes * 3 / 10) : 0;
            int third = minutes - first - second;

            boolean examImminent = daysLeft != null && daysLeft >= 0 && daysLeft <= 3;
            String firstLabel = examImminent ? "오답 복습" : basicFirst ? "개념 복습" : "문제풀이";
            String secondLabel = examImminent ? "핵심 개념 점검" : basicFirst ? "문제풀이" : "오답 정리";
            String thirdLabel = examImminent ? "실전 문제" : basicFirst ? "오답 정리" : "약점 복습";
            String activityText = activity(firstLabel, first, secondLabel, second, thirdLabel, third);
            // 각 과목의 세부 작업을 시간대 배치용 순서로 쌓습니다.
            if (first > 0) sessions.add(new Session("공부", subject.getSubjectId(), subject.getSubjectName(), firstLabel, "NEW", first));
            if (second > 0) sessions.add(new Session("공부", subject.getSubjectId(), subject.getSubjectName(), secondLabel, "NEW", second));
            if (third > 0) sessions.add(new Session("공부", subject.getSubjectId(), subject.getSubjectName(), thirdLabel, "NEW", third));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("subjectId", subject.getSubjectId());
            item.put("subjectName", subject.getSubjectName());
            item.put("difficulty", subject.getDifficulty());
            item.put("priorityLevel", subject.getPriorityLevel());
            item.put("currentConfidence", subject.getCurrentConfidence());
            item.put("priorityScore", priorityWeight(subject));
            int subjectCarryover = catchupAllocations.getOrDefault(subject.getSubjectId(), 0);
            item.put("studyMinutes", minutes + subjectCarryover);
            item.put("newStudyMinutes", minutes);
            item.put("carryoverMinutes", subjectCarryover);
            item.put("studyOrder", minutes == 0 ? "미완료 학습 보충" : subjectCarryover > 0
                    ? "미완료 학습 보충 → " + firstLabel + " → " + secondLabel + " → " + thirdLabel
                    : firstLabel + " → " + secondLabel + " → " + thirdLabel);
            item.put("timePlan", minutes == 0 ? "미완료 학습 보충 " + subjectCarryover + "분" : subjectCarryover > 0
                    ? "미완료 학습 보충 " + subjectCarryover + "분 → " + activityText
                    : activityText);
            subjectPlans.add(item);

            if (minutes > 0) {
                orderParts.add(subject.getSubjectName() + "(" + minutes + "분)");
                timeParts.add(subject.getSubjectName() + ": " + activityText);
            }
        }

        int actualAssigned = carryoverMinutes;
        for (int minutes : allocations) {
            actualAssigned += minutes;
        }
        boolean withinLimit = actualAssigned + breakMinutes <= dailyStudyTime
                && actualAssigned + breakMinutes <= usableWindowMinutes;

        // 공부를 50분 단위 이내로 나누고 10분씩 휴식을 배정합니다.
        // 기존 과목/활동별 공부 시간은 변경하지 않습니다.
        List<Session> sessionWithBreaks = insertBreaks(sessions, breakMinutes);
        List<Map<String, Object>> timeSlots = new ArrayList<>();
        boolean placed = placeIntoWindow(sessionWithBreaks, windowStart, windowEnd,
                occupiedStart, occupiedEnd, timeSlots);
        boolean hasConflict = !placed || hasOverlaps(timeSlots, occupiedStart, occupiedEnd,
                windowStart, windowEnd);
        int scheduledStudy = sumMinutesByType(timeSlots, "공부");
        int scheduledBreak = sumMinutesByType(timeSlots, "휴식");
        boolean isScheduleValid = withinLimit && !hasConflict
                && scheduledStudy == actualAssigned && scheduledBreak == breakMinutes
                && subjectTotalsMatch(subjectPlans, timeSlots, actualAssigned, carryoverMinutes, newStudyMinutes);
        String skipped = assigned.size() < prioritized.size()
                ? " 오늘 배정하지 않은 " + (prioritized.size() - assigned.size())
                  + "개 과목은 다음 학습일에 배치해야 합니다."
                : "";

        result.put("planTitle", "오늘의 학습 계획 (시간대 및 충돌 검증)");
        result.put("goalId", latestGoal.getGoalId());
        result.put("goalName", latestGoal.getGoalName());
        result.put("subjectName", assigned.get(0).getSubjectName()); // 기존 화면 호환
        result.put("examDate", examDate);
        result.put("daysLeft", daysLeft != null ? daysLeft : 0); // 기존 화면 호환
        result.put("scheduleDate", today);
        result.put("deadlineStatus", deadlineStatus);
        result.put("dailyStudyTime", dailyStudyTime);
        result.put("studyMinutes", actualAssigned);
        result.put("newStudyMinutes", newStudyMinutes);
        result.put("carryoverMinutes", carryoverMinutes);
        result.put("carryoverTotalPendingMinutes", carryover.totalPendingMinutes());
        result.put("carryoverUnattributedMinutes", carryover.unattributed());
        result.put("carryoverAllocatableMinutes", sumBalances(carryover.bySubject()));
        List<Map<String, Object>> balanceDetails = new ArrayList<>();
        for (Subject subject : subjects) {
            int pending = carryover.bySubject().getOrDefault(subject.getSubjectId(), 0);
            if (pending > 0) balanceDetails.add(Map.of("subjectId", subject.getSubjectId(),
                    "subjectName", Objects.toString(subject.getSubjectName(), "과목"), "pendingMinutes", pending,
                    "allocatedMinutes", catchupAllocations.getOrDefault(subject.getSubjectId(), 0)));
        }
        result.put("carryoverBySubject", balanceDetails);
        result.put("unrecordedSubjects", carryover.unrecordedSubjects());
        result.put("carryoverAttributionReviewDates", carryover.attributionReviewDates());
        result.put("carryoverRemainingMinutes", Math.max(0, carryover.totalPendingMinutes() - carryoverMinutes));
        result.put("carryoverSourceDays", carryover.sourceDays());
        result.put("unrecordedDates", carryover.unrecordedDates());
        result.put("unrecordedPlannedMinutes", carryover.unrecordedPlannedMinutes());
        result.put("unrecordedDays", carryover.unrecordedDays());
        result.put("carryoverPlanReviewDates", carryover.planReviewDates());
        result.put("carryoverCalculation", carryover.calculation());
        result.put("rescheduleApplied", carryoverMinutes > 0);
        result.put("rescheduleNotice", carryoverMinutes > 0
                ? "과목별로 확인한 미완료 학습 " + sumBalances(carryover.bySubject()) + "분 중 "
                  + carryoverMinutes + "분을 오늘 시간표의 첫 순서에 자동 반영했습니다. "
                  + "전체 공부 시간과 휴식 예산은 늘리지 않았습니다."
                : carryover.unattributed() > 0
                ? "과목 미확인 부족분을 보존했으며, 과목 근거가 확인될 때까지 자동 보충 배정을 보류합니다."
                : "현재 입력된 기록 기준으로 자동 배정할 미완료 학습은 없습니다.");
        if (!carryover.unrecordedDates().isEmpty()) {
            result.put("rescheduleNotice", result.get("rescheduleNotice")
                    + " 공부 기록 확인 대상은 " + carryover.unrecordedDates().size() + "일입니다.");
        }
        if (!carryover.planReviewDates().isEmpty()) {
            result.put("rescheduleNotice", result.get("rescheduleNotice")
                    + " 계획 자료 확인 대상은 " + carryover.planReviewDates().size() + "일입니다.");
        }
        result.put("scheduleStart", timeLabel(windowStart));
        result.put("scheduleEnd", timeLabel(windowEnd));
        result.put("availableWindowMinutes", windowMinutes);
        result.put("blockedMinutes", occupiedMinutes);
        result.put("effectiveAvailableMinutes", effectiveDailyMinutes);
        result.put("timeWindowNotice", effectiveDailyMinutes < dailyStudyTime
                ? "설정된 시간대와 다른 일정을 고려하여 오늘 계획을 " + effectiveDailyMinutes + "분으로 축소했습니다."
                : "설정된 공부 시간대 안에 학습을 배치했습니다.");
        result.put("timeSlots", timeSlots);
        result.put("hasConflict", hasConflict);
        result.put("isScheduleValid", isScheduleValid);
        result.put("scheduleValidationMessage", isScheduleValid
                ? "정상: 모든 학습/휴식 시간이 공부 시간대 안에 배치됐으며 겹치는 일정이 없습니다."
                : "오류: 시간대 배치 또는 일정 충돌을 확인하세요.");
        result.put("breakMinutes", breakMinutes);
        result.put("totalPlannedMinutes", actualAssigned + breakMinutes);
        result.put("isWithinDailyLimit", withinLimit);
        result.put("subjectPlans", subjectPlans);
        result.put("unassignedSubjectCount", prioritized.size() - assigned.size());
        result.put("studyOrder", String.join(" → ", orderParts));
        result.put("timePlan", String.join("\n", timeParts) + "\n휴식: " + breakMinutes + "분");
        result.put("reason", (carryoverMinutes > 0
                        ? "미완료 학습을 우선 배치한 후 남은 시간을 새 학습에 배분했습니다. " : "")
                + "중요도·난이도·자신감을 고려하여 과목별 시간을 나눴습니다. "
                + deadlineStatus + " " + timeNotice + " "
                + (effectiveDailyMinutes < dailyStudyTime
                    ? "사용 가능한 시간대 때문에 공부 시간을 줄였습니다. " : "") + skipped);
        result.put("validationMessage", withinLimit
                ? "정상: 공부 " + actualAssigned + "분 + 휴식 " + breakMinutes
                  + "분 = " + (actualAssigned + breakMinutes) + "분 (가용 " + dailyStudyTime + "분 이하)"
                : "오류: 하루 공부 가능 시간을 초과했습니다.");

        // 실제 시간표에 이월 학습이 반영되었으면 문구도 동일한 계획을 가리키도록 합니다.
        if (carryoverMinutes > 0) {
            result.put("needReschedule", true);
            result.put("adjustmentMessage", (String) result.get("rescheduleNotice"));
            result.put("adjustedPlan", "실제 시간표에 미완료 학습 보충 " + carryoverMinutes
                    + "분을 먼저 배치했습니다. 신규 학습 " + newStudyMinutes + "분, 휴식 "
                    + breakMinutes + "분입니다.");
            return result;
        }

        // 종료 시각 전에는 오늘의 공부 부족분을 미달로 확정하지 않습니다.
        // 기존의 문구 기반 재조정 권장 로직도 이 시간 경계를 따르게 합니다.
        if (!previewMode && today.equals(LocalDate.now(KOREA))
                && LocalDateTime.now(KOREA).isBefore(windowEnd)) {
            result.put("needReschedule", false);
            result.put("adjustmentMessage", "아직 공부 시간대가 끝나지 않았으므로 오늘의 미달 여부를 확정하지 않습니다.");
            result.put("adjustedPlan", "오늘의 시간표에 따라 계속 공부하고 기록을 입력해 주세요.");
            return result;
        }

        // 가장 최근 학습 기록을 날짜 기준으로 확인합니다 (조회 결과의 순서에 의존하지 않음).
        StudyLog latestLog = logs.stream()
                .filter(log -> log.getStudyDate() != null && !log.getStudyDate().isAfter(today))
                .max(Comparator.comparing(StudyLog::getStudyDate))
                .orElse(null);

        if (latestLog == null || latestLog.getStudyDate().isBefore(today.minusDays(7))) {
            result.put("needReschedule", false);
            result.put("adjustmentMessage", "최근 7일 이내 학습 기록이 없어 초기 계획을 유지합니다.");
            result.put("adjustedPlan", "오늘 계획에 따라 공부한 뒤 학습 기록을 남겨주세요.");
            return result;
        }

        // 같은 날짜의 기록이 여러 개면 시간을 합산해서 달성률을 판단합니다.
        LocalDate latestStudyDate = latestLog.getStudyDate();
        int latestDayMinutes = 0;
        boolean notCompleted = false;
        for (StudyLog log : logs) {
            if (!latestStudyDate.equals(log.getStudyDate())) continue;
            if (log.getStudyMinutes() != null && log.getStudyMinutes() > 0) {
                latestDayMinutes += log.getStudyMinutes();
            }
            if (log.getCompleted() != null && !"완료".equals(log.getCompleted())) {
                notCompleted = true;
            }
        }
        // 휴식 제외 학습 목표 시간의 70% 기준입니다.
        boolean lowStudyTime = latestDayMinutes < actualAssigned * 0.7;
        boolean needReschedule = notCompleted || lowStudyTime;
        result.put("lastStudyDate", latestStudyDate);
        result.put("lastStudyMinutes", latestDayMinutes);
        result.put("needReschedule", needReschedule);

        if (needReschedule) {
            int reducedMinutes = Math.max(1, (int) Math.round(actualAssigned * 0.8));
            int concept = reducedMinutes / 2;
            int problems = (reducedMinutes - concept) / 2;
            int review = reducedMinutes - concept - problems;
            result.put("adjustmentMessage",
                    "최근 학습 기록을 바탕으로 재조정이 권장됩니다. "
                    + "실제 시간표 자동 반영은 종료된 날의 저장된 시간표에서 계산한 미달분에 대해서만 적용됩니다.");
            result.put("adjustedPlan", "재조정 권장: " + activity("핵심 개념", concept,
                    "쉬운 문제", problems, "오답 정리", review)
                    + " (별도 권장안; 현재 시간표에는 적용되지 않음)");
        } else {
            int reviewMinutes = Math.min(20, Math.max(1, actualAssigned / 5));
            result.put("adjustmentMessage", "최근 목표 달성 상태가 양호해 총 공부 시간을 늘리지 않고 복습을 배치합니다.");
            result.put("adjustedPlan", "오늘 배정된 공부 " + actualAssigned
                    + "분 중 " + reviewMinutes + "분을 오답 복습에 사용하세요. 휴식 "
                    + breakMinutes + "분은 유지합니다.");
        }
        return result;
    }


    /**
     * 현재 목표의 과거 FINAL 계획을 날짜순으로 재계산합니다. 기록 없음은 실제 공부 0분과 다릅니다.
     * 시간 설정만 있는 과거 자료는 첫 기록 이후에 한해 기존 방식으로 추정합니다.
     * 보충 우선 수행은 기록 내용으로 확인할 수 없는 기존의 규칙 기반 가정입니다.
     */
    private Carryover calculateCarryover(LocalDate targetDate, Goal goal,
                                         List<StudyLog> logs, boolean previewMode, List<Subject> subjects) {
        List<String> unrecorded = new ArrayList<>();
        List<Map<String, Object>> unrecordedDays = new ArrayList<>();
        List<String> review = new ArrayList<>();
        List<Map<String, Object>> calculation = new ArrayList<>();
        Map<Long, Integer> balances = new java.util.TreeMap<>();
        List<Map<String, Object>> unrecordedSubjects = new ArrayList<>();
        List<String> attributionReview = new ArrayList<>();
        Map<Long, Subject> subjectById = new HashMap<>();
        for (Subject subject : subjects) subjectById.put(subject.getSubjectId(), subject);
        if (goal.getExamDate() != null && targetDate.isAfter(goal.getExamDate())) {
            return new Carryover(0, 0, 0, unrecorded, 0, review, calculation, unrecordedDays,
                    balances, 0, unrecordedSubjects, attributionReview);
        }
        LocalDate goalStart = goal.getCreatedAt() == null ? null : goal.getCreatedAt().toLocalDate();
        LocalDate firstLog = null;
        Map<LocalDate, Integer> actualByDate = new HashMap<>();
        Map<LocalDate, Map<Long, Integer>> actualBySubject = new HashMap<>();
        // 기록은 한 번만 집계합니다. 생성일이 없는 과거 목표도 목표/과목 ID 경계는 유지합니다.
        for (StudyLog log : logs) {
            LocalDate day = log.getStudyDate();
            if (day == null || !day.isBefore(targetDate)
                    || (goalStart != null && day.isBefore(goalStart))) continue;
            if (firstLog == null || day.isBefore(firstLog)) firstLog = day;
            Integer minutes = log.getStudyMinutes();
            if (minutes != null && minutes > 0) {
                actualByDate.merge(day, minutes,
                        (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
                actualBySubject.computeIfAbsent(day, ignored -> new HashMap<>())
                        .merge(log.getSubjectId(), minutes,
                                (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
            }
        }
        Map<LocalDate, Map<String, Object>> finals =
                savedPlanService.findFinalPlans(goal.getGoalId(), goalStart, targetDate);
        Map<LocalDate, Map<String, Object>> settings = new java.util.TreeMap<>();
        // 설정에는 목표 ID가 없으므로 해당 목표의 첫 기록보다 앞선 설정은 읽지 않습니다.
        if (firstLog != null) {
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "SELECT schedule_date, start_time, end_time, busy_start, busy_end "
                    + "FROM study_schedule_settings WHERE user_id = ? AND schedule_date >= ? "
                    + "AND schedule_date < ? ORDER BY schedule_date ASC",
                    USER_ID, Date.valueOf(firstLog), Date.valueOf(targetDate))) {
                LocalDate day = readSqlDate(row.get("schedule_date"));
                if (day != null && !day.isBefore(firstLog) && day.isBefore(targetDate)) settings.put(day, row);
            }
        }
        java.util.Set<LocalDate> days = new java.util.TreeSet<>(settings.keySet());
        days.addAll(finals.keySet());
        int unattributed = 0, sourceDays = 0, unrecordedMinutes = 0;
        LocalDateTime now = LocalDateTime.now(KOREA);
        int configured = goal.getDailyStudyTime() == null ? 90 : goal.getDailyStudyTime();
        int dailyLimit = configured <= 0 ? 90 : Math.min(configured, 1440);
        for (LocalDate day : days) {
            Map<String, Object> snapshot = finals.get(day);
            DayPlan plan;
            if (snapshot != null) {
                plan = finalDayPlan(day, snapshot);
                if (plan == null) {
                    review.add(day.toString());
                    continue; // 불완전한 FINAL을 현재 설정으로 대체하지 않습니다.
                }
            } else {
                // 시간 설정에는 goal_id가 없어 첫 기록 이전 설정을 현재 목표로 단정하지 않습니다.
                if (firstLog == null || day.isBefore(firstLog)) continue;
                plan = estimatedDayPlan(day, settings.get(day), dailyLimit,
                        unattributed + sumBalances(balances), goal.getExamDate());
                if (plan == null) {
                    review.add(day.toString());
                    continue;
                }
            }
            boolean finished = !now.isBefore(plan.end());
            boolean assumedEnd = previewMode && day.equals(now.toLocalDate());
            int actual = actualByDate.getOrDefault(day, 0);
            if (!finished && !assumedEnd) continue;
            if (actual == 0) {
                // 미리보기라도 종료 전의 무기록 날짜를 '확인 필요'나 미달로 만들지 않습니다.
                if (finished) {
                    unrecorded.add(day.toString());
                    unrecordedMinutes += plan.total();
                    unrecordedDays.add(Map.of("date", day.toString(),
                            "plannedStudyMinutes", plan.total(),
                            "basis", snapshot == null ? "SETTINGS_ESTIMATE" : "FINAL"));
                }
                continue;
            }
            SubjectEvidence evidence = subjectEvidence(snapshot, plan, subjectById);
            if ("REVIEW".equals(evidence.mode())) {
                attributionReview.add(day.toString());
                continue;
            }
            int before = unattributed + sumBalances(balances);
            int performedCatchup = 0, creditedCatchup = 0, doneNew = 0, missingNew = 0;
            List<Map<String, Object>> subjectSteps = new ArrayList<>();
            if ("SUBJECT".equals(evidence.mode())) {
                Map<Long, Integer> recorded = actualBySubject.getOrDefault(day, Map.of());
                for (SubjectDayPlan subjectPlan : evidence.plans()) {
                    int subjectActual = recorded.getOrDefault(subjectPlan.id(), 0);
                    if (subjectActual == 0) {
                        if (finished) unrecordedSubjects.add(Map.of("date", day.toString(),
                                "subjectId", subjectPlan.id(), "subjectName", Objects.toString(
                                        subjectById.get(subjectPlan.id()).getSubjectName(), "과목"),
                                "plannedStudyMinutes", subjectPlan.total(), "basis", "FINAL"));
                        continue; // 미입력 과목은 부족 확정과 기존 잔액 차감 모두 보류합니다.
                    }
                    int previous = balances.getOrDefault(subjectPlan.id(), 0);
                    LedgerStep change = advance(previous, subjectPlan.catchup(), subjectPlan.fresh(), subjectActual);
                    if (change.next() == 0) balances.remove(subjectPlan.id());
                    else balances.put(subjectPlan.id(), change.next());
                    performedCatchup += change.performed();
                    creditedCatchup += change.credited();
                    doneNew += change.doneNew();
                    missingNew += change.missing();
                    Map<String, Object> subjectStep = ledgerFields(previous, subjectPlan.total(),
                            subjectPlan.catchup(), subjectPlan.fresh(), subjectActual, change);
                    subjectStep.put("subjectId", subjectPlan.id());
                    subjectSteps.add(subjectStep);
                }
            } else {
                // 이 날짜는 전체 계산 경로로 딱 한 번 반영합니다. 과목별 계산에 다시 더하지 않습니다.
                LedgerStep change = advance(before, plan.catchup(), plan.newMinutes(), actual);
                performedCatchup = change.performed(); creditedCatchup = change.credited();
                doneNew = change.doneNew(); missingNew = change.missing();
                if (creditedCatchup > 0 && !balances.isEmpty()) {
                    // 상환 과목을 특정할 수 없으므로 남는 잔액 전체의 과목 연결을 보류합니다.
                    balances.clear();
                    unattributed = change.next();
                } else {
                    unattributed = unattributed - creditedCatchup + missingNew;
                }
                attributionReview.add(day.toString());
            }
            int backlog = unattributed + sumBalances(balances);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("date", day.toString());
            step.put("basis", snapshot == null ? "SETTINGS_ESTIMATE" : "FINAL");
            step.put("provisional", !finished);
            step.put("previousBacklogMinutes", before);
            step.put("plannedStudyMinutes", plan.total());
            step.put("plannedCatchupMinutes", plan.catchup());
            step.put("plannedNewMinutes", plan.newMinutes());
            step.put("actualStudyMinutes", actual);
            step.put("performedCatchupMinutes", performedCatchup);
            step.put("creditedCatchupMinutes", creditedCatchup);
            step.put("completedNewMinutes", doneNew);
            step.put("missingNewMinutes", missingNew);
            step.put("nextBacklogMinutes", backlog);
            step.put("calculationMode", evidence.mode());
            step.put("subjectCalculation", subjectSteps);
            step.put("nextUnattributedMinutes", unattributed);
            calculation.add(step);
            sourceDays++;
        }
        int known = sumBalances(balances);
        return new Carryover(unattributed + known, recommendedCatchup(known, targetDate, goal.getExamDate()),
                sourceDays, unrecorded, unrecordedMinutes, review, calculation, unrecordedDays,
                balances, unattributed, unrecordedSubjects, attributionReview);
    }

    // 하루 전체 FINAL은 이미 검증한 뒤 호출합니다. 필드 부재와 모순된 자료를 구분합니다.
    private SubjectEvidence subjectEvidence(Map<String, Object> snapshot, DayPlan day,
                                            Map<Long, Subject> subjects) {
        if (snapshot == null || snapshot.get("subjectPlans") == null)
            return new SubjectEvidence("LEGACY_TOTAL", List.of());
        if (!(snapshot.get("subjectPlans") instanceof List<?> items))
            return new SubjectEvidence("REVIEW", List.of());
        if (items.isEmpty()) return new SubjectEvidence("LEGACY_TOTAL", List.of());
        List<SubjectDayPlan> plans = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        boolean missingId = false, missingSplit = false;
        int total = 0, catchupTotal = 0, freshTotal = 0;
        for (Object value : items) {
            if (!(value instanceof Map<?, ?> item)) return new SubjectEvidence("REVIEW", List.of());
            Object rawId = item.get("subjectId");
            if (rawId == null) missingId = true;
            else if (!(rawId instanceof Number id) || id.longValue() <= 0
                    || id.doubleValue() != id.longValue() || !subjects.containsKey(id.longValue())
                    || !seen.add(id.longValue())) return new SubjectEvidence("REVIEW", List.of());
            Integer minutes = planMinutes(item.get("studyMinutes"));
            Integer catchup = planMinutes(item.get("carryoverMinutes"));
            Integer fresh = planMinutes(item.get("newStudyMinutes"));
            if (minutes == null) return new SubjectEvidence("REVIEW", List.of());
            if (catchup == null && item.get("carryoverMinutes") == null && fresh != null) catchup = minutes - fresh;
            if (fresh == null && item.get("newStudyMinutes") == null && catchup != null) fresh = minutes - catchup;
            if (catchup == null && fresh == null && item.get("carryoverMinutes") == null
                    && item.get("newStudyMinutes") == null) {
                missingSplit = true;
                total += minutes;
                continue;
            }
            if (catchup == null || fresh == null || catchup < 0 || fresh < 0 || catchup + fresh != minutes)
                return new SubjectEvidence("REVIEW", List.of());
            total += minutes; catchupTotal += catchup; freshTotal += fresh;
            if (rawId instanceof Number id && minutes > 0)
                plans.add(new SubjectDayPlan(id.longValue(), minutes, catchup, fresh));
        }
        if (total != day.total() || (!missingSplit
                && (catchupTotal != day.catchup() || freshTotal != day.newMinutes())))
            return new SubjectEvidence("REVIEW", List.of());
        return new SubjectEvidence(missingId || missingSplit ? "LEGACY_TOTAL" : "SUBJECT", plans);
    }

    private LedgerStep advance(int before, int catchup, int fresh, int actual) {
        int performed = Math.min(catchup, actual);
        int credited = Math.min(before, performed);
        int done = Math.max(0, actual - performed);
        int missing = Math.max(0, fresh - done);
        return new LedgerStep(performed, credited, done, missing, before - credited + missing);
    }

    private Map<String, Object> ledgerFields(int before, int total, int catchup, int fresh,
                                            int actual, LedgerStep change) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("previousBacklogMinutes", before); result.put("plannedStudyMinutes", total);
        result.put("plannedCatchupMinutes", catchup); result.put("plannedNewMinutes", fresh);
        result.put("actualStudyMinutes", actual); result.put("performedCatchupMinutes", change.performed());
        result.put("creditedCatchupMinutes", change.credited()); result.put("completedNewMinutes", change.doneNew());
        result.put("missingNewMinutes", change.missing()); result.put("nextBacklogMinutes", change.next());
        return result;
    }

    private int sumBalances(Map<Long, Integer> balances) {
        return balances.values().stream().mapToInt(Integer::intValue).sum();
    }

    private record LedgerStep(int performed, int credited, int doneNew, int missing, int next) { }
    private record SubjectDayPlan(long id, int total, int catchup, int fresh) { }
    private record SubjectEvidence(String mode, List<SubjectDayPlan> plans) { }

    // 한쪽 내역만 누락되면 합계로 복원합니다. 둘 다 없으면 부족량을 추측하지 않습니다.
    private DayPlan finalDayPlan(LocalDate day, Map<String, Object> saved) {
        Integer total = planMinutes(saved.get("studyMinutes"));
        Integer catchup = planMinutes(saved.get("carryoverMinutes"));
        Integer fresh = planMinutes(saved.get("newStudyMinutes"));
        if (total == null || total <= 0 || !Boolean.TRUE.equals(saved.get("isScheduleValid"))
                || !Boolean.FALSE.equals(saved.get("hasConflict"))) return null;
        if (catchup == null && saved.get("carryoverMinutes") == null && fresh != null)
            catchup = total - fresh;
        if (fresh == null && saved.get("newStudyMinutes") == null && catchup != null)
            fresh = total - catchup;
        if (catchup == null || fresh == null || catchup < 0 || fresh < 0
                || catchup + fresh != total) return null;
        try {
            LocalDateTime end = LocalDateTime.parse(String.valueOf(saved.get("scheduleEnd")),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            if (!end.toLocalDate().equals(day)) return null;
            return new DayPlan(total, catchup, fresh, end);
        } catch (DateTimeException ex) {
            return null;
        }
    }

    private Integer planMinutes(Object value) {
        if (!(value instanceof Number number)) return null;
        double minutes = number.doubleValue();
        return Double.isFinite(minutes) && minutes >= 0 && minutes <= 1440
                && minutes == Math.floor(minutes) ? (int) minutes : null;
    }

    private DayPlan estimatedDayPlan(LocalDate day, Map<String, Object> row,
                                     int limit, int backlog, LocalDate examDate) {
        if (row == null) return null;
        LocalTime start = readSqlTime(row.get("start_time")), end = readSqlTime(row.get("end_time"));
        LocalTime busyStart = readSqlTime(row.get("busy_start")), busyEnd = readSqlTime(row.get("busy_end"));
        if (start == null || end == null || !start.isBefore(end)
                || (busyStart == null) != (busyEnd == null)) return null;
        if (busyStart != null && (!busyStart.isBefore(busyEnd)
                || busyStart.isBefore(start) || busyEnd.isAfter(end))) return null;
        int usable = (int) ChronoUnit.MINUTES.between(start, end)
                - (busyStart == null ? 0 : (int) ChronoUnit.MINUTES.between(busyStart, busyEnd));
        int effective = Math.min(limit, usable);
        int total = effective - (effective / 60) * 10;
        if (total <= 0) return null;
        int catchup = Math.min(total, recommendedCatchup(backlog, day, examDate));
        return new DayPlan(total, catchup, total - catchup, LocalDateTime.of(day, end));
    }

    private record DayPlan(int total, int catchup, int newMinutes, LocalDateTime end) { }

    private int recommendedCatchup(int pending, LocalDate date, LocalDate examDate) {
        if (pending <= 0) return 0;
        long daysRemaining = examDate == null ? 7
                : Math.max(1, ChronoUnit.DAYS.between(date, examDate));
        int spreadDays = (int) Math.min(7, daysRemaining);
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                (pending + (long) spreadDays - 1) / spreadDays));
    }

    private LocalDate readSqlDate(Object value) {
        if (value instanceof Date d) return d.toLocalDate();
        if (value instanceof LocalDate d) return d;
        try { return value == null ? null : LocalDate.parse(value.toString().substring(0, 10)); }
        catch (RuntimeException ex) { return null; }
    }

    private LocalTime readSqlTime(Object value) {
        if (value instanceof java.sql.Time t) return t.toLocalTime();
        if (value instanceof LocalTime t) return t;
        try { return value == null ? null : LocalTime.parse(value.toString()); }
        catch (RuntimeException ex) { return null; }
    }

    private record Carryover(int totalPendingMinutes, int todayRecommendedMinutes, int sourceDays,
                             List<String> unrecordedDates, int unrecordedPlannedMinutes,
                             List<String> planReviewDates, List<Map<String, Object>> calculation,
                              List<Map<String, Object>> unrecordedDays, Map<Long, Integer> bySubject,
                              int unattributed, List<Map<String, Object>> unrecordedSubjects,
                              List<String> attributionReviewDates) { }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String timeLabel(LocalDateTime t) {
        return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    // 50분 학습을 넘기지 않도록 중간에 휴식 10분을 삽입합니다.
    private List<Session> insertBreaks(List<Session> sessions, int breakMinutes) {
        List<Session> ordered = new ArrayList<>();
        int sinceRest = 0;
        int restLeft = breakMinutes;
        for (Session task : sessions) {
            int left = task.minutes;
            while (left > 0) {
                if (sinceRest >= 50 && restLeft > 0) {
                    int rest = Math.min(10, restLeft);
                    ordered.add(new Session("휴식", null, "", "휴식", "REST", rest));
                    restLeft -= rest;
                    sinceRest = 0;
                }
                int chunk = restLeft > 0 ? Math.min(left, 50 - sinceRest) : left;
                ordered.add(new Session("공부", task.subjectId, task.subject, task.activity, task.allocationType, chunk));
                sinceRest += chunk;
                left -= chunk;
            }
        }
        // 60분 단위마다 확보한 휴식을 포함하므로 마지막에 남을 수도 있습니다.
        while (restLeft > 0) {
            int rest = Math.min(10, restLeft);
            ordered.add(new Session("휴식", null, "", "휴식", "REST", rest));
            restLeft -= rest;
        }
        return ordered;
    }

    // 다른 일정이 있다면 이를 건너뛰며 일정이 겹치지 않게 배치합니다.
    // 같은 작업이 막힌 시간대를 사이에 두고 둘로 나뉠 수 있습니다.
    private boolean placeIntoWindow(List<Session> sessions,
                                    LocalDateTime start, LocalDateTime end,
                                    LocalDateTime busyStart, LocalDateTime busyEnd,
                                    List<Map<String, Object>> slots) {
        LocalDateTime current = start;
        for (Session session : sessions) {
            int left = session.minutes;
            while (left > 0) {
                if (busyStart != null && !current.isBefore(busyStart) && current.isBefore(busyEnd)) {
                    current = busyEnd;
                }
                LocalDateTime freeEnd = (busyStart != null && current.isBefore(busyStart))
                        ? (busyStart.isBefore(end) ? busyStart : end) : end;
                long freeMinutes = ChronoUnit.MINUTES.between(current, freeEnd);
                if (freeMinutes <= 0) return false;
                int take = (int) Math.min(left, freeMinutes);
                LocalDateTime finish = current.plusMinutes(take);
                Map<String, Object> slot = new LinkedHashMap<>();
                slot.put("type", session.type);
                slot.put("subjectName", session.subject.isEmpty() ? null : session.subject);
                slot.put("subjectId", session.subjectId);
                slot.put("allocationType", session.allocationType);
                slot.put("activity", session.activity);
                slot.put("startTime", timeLabel(current));
                slot.put("endTime", timeLabel(finish));
                slot.put("minutes", take);
                slots.add(slot);
                current = finish;
                left -= take;
            }
        }
        return true;
    }

    private boolean hasOverlaps(List<Map<String, Object>> slots,
                                LocalDateTime busyStart, LocalDateTime busyEnd,
                                LocalDateTime windowStart, LocalDateTime windowEnd) {
        LocalDateTime previousEnd = windowStart;
        for (Map<String, Object> slot : slots) {
            LocalDateTime start = LocalDateTime.parse((String) slot.get("startTime"),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            LocalDateTime end = LocalDateTime.parse((String) slot.get("endTime"),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            if (start.isBefore(previousEnd) || !end.isAfter(start) || end.isAfter(windowEnd)) return true;
            if (busyStart != null && start.isBefore(busyEnd) && end.isAfter(busyStart)) return true;
            previousEnd = end;
        }
        return false;
    }

    private int sumMinutesByType(List<Map<String, Object>> slots, String type) {
        int minutes = 0;
        for (Map<String, Object> slot : slots) {
            if (type.equals(slot.get("type"))) minutes += (Integer) slot.get("minutes");
        }
        return minutes;
    }

    private static class Session {
        final String type;
        final Long subjectId;
        final String subject;
        final String activity;
        final String allocationType;
        final int minutes;
        Session(String type, Long subjectId, String subject, String activity, String allocationType, int minutes) {
            this.type = type;
            this.subjectId = subjectId;
            this.subject = Objects.toString(subject, "과목");
            this.activity = activity;
            this.allocationType = allocationType;
            this.minutes = minutes;
        }
    }

    private Map<Long, Integer> allocateCatchup(Map<Long, Integer> pending, int budget) {
        Map<Long, Integer> assigned = new HashMap<>();
        int total = sumBalances(pending);
        if (budget <= 0 || total <= 0) return assigned;
        int used = 0;
        for (var entry : pending.entrySet()) {
            int minutes = (int) ((long) budget * entry.getValue() / total);
            assigned.put(entry.getKey(), minutes); used += minutes;
        }
        List<Long> order = pending.keySet().stream().sorted(
                Comparator.<Long>comparingLong(id -> (long) budget * pending.get(id) % total)
                        .reversed().thenComparingLong(Long::longValue)).toList();
        for (Long id : order) {
            if (used == budget) break;
            if (assigned.get(id) < pending.get(id)) {
                assigned.merge(id, 1, Integer::sum); used++;
            }
        }
        return assigned;
    }

    private boolean subjectTotalsMatch(List<Map<String, Object>> plans, List<Map<String, Object>> slots,
                                        int study, int catchup, int fresh) {
        Map<Long, int[]> expected = new HashMap<>(), actual = new HashMap<>();
        int totalStudy = 0, totalCatchup = 0, totalFresh = 0;
        for (Map<String, Object> plan : plans) {
            Long id = (Long) plan.get("subjectId");
            int s = (Integer) plan.get("studyMinutes"), c = (Integer) plan.get("carryoverMinutes"),
                    n = (Integer) plan.get("newStudyMinutes");
            if (s != c + n || expected.put(id, new int[]{s, c, n}) != null) return false;
            totalStudy += s; totalCatchup += c; totalFresh += n;
        }
        for (Map<String, Object> slot : slots) {
            if (!"공부".equals(slot.get("type"))) continue;
            Long id = (Long) slot.get("subjectId");
            if (!expected.containsKey(id)) return false;
            String kind = (String) slot.get("allocationType");
            if (!"CATCHUP".equals(kind) && !"NEW".equals(kind)) return false;
            int minutes = (Integer) slot.get("minutes");
            int[] sum = actual.computeIfAbsent(id, ignored -> new int[3]);
            sum[0] += minutes; sum["CATCHUP".equals(kind) ? 1 : 2] += minutes;
        }
        return totalStudy == study && totalCatchup == catchup && totalFresh == fresh
                && expected.entrySet().stream().allMatch(entry ->
                java.util.Arrays.equals(entry.getValue(), actual.get(entry.getKey())));
    }

    private int priorityWeight(Subject subject) {
        int score = 1;
        String priority = String.valueOf(subject.getPriorityLevel());
        String difficulty = String.valueOf(subject.getDifficulty());
        String confidence = String.valueOf(subject.getCurrentConfidence());
        if (priority.contains("높") || priority.contains("상") || priority.contains("중요")) score += 4;
        if (difficulty.contains("어려") || difficulty.contains("높") || difficulty.contains("상")) score += 2;
        if (confidence.contains("낮") || confidence.contains("없") || confidence.contains("하")) score += 2;
        return score;
    }

    private boolean needsBasicFirst(Subject subject) {
        String difficulty = String.valueOf(subject.getDifficulty());
        String confidence = String.valueOf(subject.getCurrentConfidence());
        return difficulty.contains("어려") || difficulty.contains("높")
                || confidence.contains("낮") || confidence.contains("없");
    }

    private int[] allocateMinutes(List<Subject> subjects, int budget) {
        int[] minutes = new int[subjects.size()];
        if (subjects.isEmpty()) return minutes;
        if (subjects.size() == 1) {
            minutes[0] = budget;
            return minutes;
        }

        int minimum = 10;
        int remaining = budget - minimum * subjects.size();
        int totalWeight = 0;
        for (Subject subject : subjects) totalWeight += priorityWeight(subject);

        int allocated = 0;
        for (int i = 0; i < subjects.size(); i++) {
            minutes[i] = minimum + remaining * priorityWeight(subjects.get(i)) / totalWeight;
            allocated += minutes[i];
        }
        // 정수 나눗셈으로 남은 1~몇 분은 우선순위가 높은 순서대로 배정합니다.
        for (int i = 0; allocated < budget; i = (i + 1) % subjects.size()) {
            minutes[i]++;
            allocated++;
        }
        return minutes;
    }

    private String activity(String firstLabel, int first, String secondLabel, int second,
                            String thirdLabel, int third) {
        List<String> parts = new ArrayList<>();
        if (first > 0) parts.add(firstLabel + " " + first + "분");
        if (second > 0) parts.add(secondLabel + " " + second + "분");
        if (third > 0) parts.add(thirdLabel + " " + third + "분");
        return String.join(" → ", parts);
    }
}
