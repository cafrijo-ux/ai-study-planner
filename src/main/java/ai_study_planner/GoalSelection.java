package ai_study_planner;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Objects;

/** 요청에 명시한 목표만 사용합니다. 선택 정보는 DB나 전역 변수에 저장하지 않습니다. */
public final class GoalSelection {
    private GoalSelection() {}

    public static Long requestedId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        String[] values = attributes.getRequest().getParameterValues("goalId");
        if (values == null) return null; // 기존 API 호출의 기본 동작은 유지합니다.
        if (values.length != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "목표 번호는 하나만 지정하세요.");
        String value = values[0];
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {}
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "목표 번호가 올바르지 않습니다.");
    }

    public static List<Goal> goals(List<Goal> goals) {
        Long id = requestedId();
        goals = goals.stream().filter(g -> Objects.equals(g.getUserId(), 1L)).toList();
        if (id == null) return goals;
        List<Goal> selected = goals.stream().filter(g -> Objects.equals(g.getUserId(), 1L)
                && Objects.equals(g.getGoalId(), id)).toList();
        if (selected.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "선택한 목표를 찾을 수 없습니다.");
        return selected;
    }

    public static List<Long> goalIds(JdbcTemplate jdbc, long userId) {
        Long id = requestedId();
        if (id == null) return jdbc.queryForList(
                "SELECT goal_id FROM goals WHERE user_id = ? ORDER BY goal_id DESC LIMIT 1", Long.class, userId);
        List<Long> ids = jdbc.queryForList("SELECT goal_id FROM goals WHERE user_id = ? AND goal_id = ?", Long.class, userId, id);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "선택한 목표를 찾을 수 없습니다.");
        return ids;
    }

    public static List<Subject> subjects(List<Subject> subjects) {
        Long id = requestedId();
        subjects = subjects.stream().filter(s -> Objects.equals(s.getUserId(), 1L)).toList();
        return id == null ? subjects : subjects.stream().filter(s -> Objects.equals(s.getUserId(), 1L)
                && Objects.equals(s.getGoalId(), id)).toList();
    }

    public static List<StudyLog> logs(List<StudyLog> logs, List<Subject> subjects) {
        logs = logs.stream().filter(l -> Objects.equals(l.getUserId(), 1L)).toList();
        if (requestedId() == null) return logs;
        var ids = subjects(subjects).stream().map(Subject::getSubjectId).collect(java.util.stream.Collectors.toSet());
        return logs.stream().filter(l -> Objects.equals(l.getUserId(), 1L) && ids.contains(l.getSubjectId())).toList();
    }
}
