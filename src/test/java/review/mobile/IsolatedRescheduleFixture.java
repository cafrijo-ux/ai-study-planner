package review.mobile;

import ai_study_planner.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ConfigurableApplicationContext;
import java.time.*;
import java.util.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;

/** 승인된 새 메모리 H2에만 생성하는 재조정 전용 가상 데이터. */
public final class IsolatedRescheduleFixture {
    public static final ZoneId KOREA=ZoneId.of("Asia/Seoul");
    public record Seed(long goalA,long goalB,long subjectA,long subjectB,LocalDate today){
        public LocalDate target(){return today.plusDays(1);}
    }
    public record Baseline(List<Map<String,Object>> finals,List<Map<String,Object>> logs,List<Map<String,Object>> subjects,List<Map<String,Object>> goals,List<Map<String,Object>> settings){}
    public static Baseline baseline(ConfigurableApplicationContext ctx){
        var j=ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        return new Baseline(j.queryForList("SELECT * FROM study_saved_plans ORDER BY goal_id,plan_date"),j.queryForList("SELECT * FROM study_logs ORDER BY log_id"),j.queryForList("SELECT * FROM subjects ORDER BY subject_id"),j.queryForList("SELECT * FROM goals ORDER BY goal_id"),j.queryForList("SELECT * FROM study_schedule_settings ORDER BY schedule_date"));
    }
    public static Map<String,Object> preservation(ConfigurableApplicationContext ctx,Seed seed,Baseline before){
        var now=baseline(ctx);
        var protectedPlans=now.finals().stream().filter(r->!(((Number)r.get("goal_id")).longValue()==seed.goalA()&&r.get("plan_date").toString().equals(seed.target().toString()))).toList();
        var protectedSettings=now.settings().stream().filter(r->r.get("schedule_date").toString().equals(seed.today().toString())).toList();
        boolean preserved=before.finals().equals(protectedPlans)&&before.logs().equals(now.logs())&&before.subjects().equals(now.subjects())&&before.goals().equals(now.goals())&&before.settings().equals(protectedSettings);
        return Map.of("preserved",preserved,"originalFinalCount",before.finals().size(),"currentFinalCount",now.finals().size(),"studyLogCount",now.logs().size());
    }
    public static void verifyPreview(ConfigurableApplicationContext ctx,Seed seed){
        var before=baseline(ctx);
        var request=new MockHttpServletRequest();request.setParameter("goalId",""+seed.goalA());
        var previous=RequestContextHolder.getRequestAttributes();
        try{
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            var plan=ctx.getBean(StudyPlanController.class).previewTomorrowPlan();
            if(!seed.target().toString().equals(String.valueOf(plan.get("scheduleDate")))||!Integer.valueOf(60).equals(plan.get("carryoverTotalPendingMinutes"))||!Integer.valueOf(9).equals(plan.get("carryoverMinutes"))||!Integer.valueOf(91).equals(plan.get("newStudyMinutes"))||!Boolean.TRUE.equals(plan.get("isScheduleValid")))throw new IllegalStateException("Fixture preview does not match 60/9/91");
            if(!before.equals(baseline(ctx)))throw new IllegalStateException("Preview changed fixture");
        }finally{if(previous==null)RequestContextHolder.resetRequestAttributes();else RequestContextHolder.setRequestAttributes(previous);}
    }
    public static Seed seed(ConfigurableApplicationContext ctx,String expected)throws Exception{
        var jdbc=IsolatedStudyLogFixture.prepareEmptyDatabase(ctx,expected);
        LocalDate today=LocalDate.now(KOREA);
        var goals=ctx.getBean(GoalRepository.class);var subjects=ctx.getBean(SubjectRepository.class);
        Goal a=goal(goals,"[격리 재조정] 목표 A",today),b=goal(goals,"[격리 재조정] 목표 B",today);
        Subject sa=subject(subjects,a.getGoalId(),"[가상] 운영체제 A"),sb=subject(subjects,b.getGoalId(),"[가상] 네트워크 B");
        for(var entry:List.of(Map.entry(a,sa),Map.entry(b,sb))){
            LocalDate yesterday=today.minusDays(1);
            String json=snapshot(entry.getKey().getGoalId(),entry.getValue(),yesterday);
            jdbc.update("INSERT INTO study_saved_plans(user_id,goal_id,plan_date,source_date,source_end_time,plan_status,start_time,end_time,plan_json) VALUES(1,?,?,?,'21:00:00','FINAL','19:00:00','21:00:00',?)",entry.getKey().getGoalId(),java.sql.Date.valueOf(yesterday),java.sql.Date.valueOf(yesterday.minusDays(1)),json);
            StudyLog log=new StudyLog();log.setUserId(1L);log.setSubjectId(entry.getValue().getSubjectId());log.setStudyDate(yesterday);
            log.setStudyMinutes(entry.getKey()==a?40:100);log.setStudyContent("[가상] 재조정 검증 기록");log.setCompleted(entry.getKey()==a?"미완료":"완료");log.setFocusScore(3);log.setUnderstandingScore(3);log.setFatigueScore(2);
            ctx.getBean(StudyLogRepository.class).save(log);
        }
        // 목표 B의 내일 FINAL은 목표 A 저장 후에도 그대로 보존해야 한다.
        jdbc.update("INSERT INTO study_saved_plans(user_id,goal_id,plan_date,source_date,source_end_time,plan_status,start_time,end_time,plan_json) VALUES(1,?,?,?,'21:00:00','FINAL','19:00:00','21:00:00',?)",b.getGoalId(),java.sql.Date.valueOf(today.plusDays(1)),java.sql.Date.valueOf(today),snapshot(b.getGoalId(),sb,today.plusDays(1)));
        jdbc.update("INSERT INTO study_schedule_settings(user_id,schedule_date,start_time,end_time) VALUES(1,?,'19:00:00','21:00:00')",java.sql.Date.valueOf(today));
        // 승인된 내일 A 행과 미리 만든 원본 세 행 이외 날짜/목표 쓰기는 H2에서도 거부한다.
        String allowed="(user_id=1 AND ((goal_id="+a.getGoalId()+" AND plan_date IN (DATE '"+today.minusDays(1)+"',DATE '"+today.plusDays(1)+"')) OR (goal_id="+b.getGoalId()+" AND plan_date IN (DATE '"+today.minusDays(1)+"',DATE '"+today.plusDays(1)+"'))))";
        jdbc.execute("ALTER TABLE study_saved_plans ADD CONSTRAINT isolated_fixture_scope CHECK "+allowed);
        return new Seed(a.getGoalId(),b.getGoalId(),sa.getSubjectId(),sb.getSubjectId(),today);
    }
    private static Goal goal(GoalRepository repo,String name,LocalDate day){var g=new Goal();g.setUserId(1L);g.setGoalName(name);g.setExamDate(day.plusDays(30));g.setDailyStudyTime(120);g.setCreatedAt(day.minusDays(2).atStartOfDay());return repo.save(g);}
    private static Subject subject(SubjectRepository repo,long goal,String name){var s=new Subject();s.setUserId(1L);s.setGoalId(goal);s.setSubjectName(name);s.setDifficulty("보통");s.setPriorityLevel("높음");s.setCurrentConfidence("보통");return repo.save(s);}
    private static String snapshot(long goal,Subject subject,LocalDate day)throws Exception{
        var p=new LinkedHashMap<String,Object>();p.put("goalId",goal);p.put("scheduleDate",day.toString());p.put("planStatus","FINAL");p.put("fixtureSource","SYNTHETIC_H2_RESCHEDULE_ONLY");
        p.put("studyMinutes",100);p.put("carryoverMinutes",0);p.put("newStudyMinutes",100);p.put("breakMinutes",20);
        p.put("scheduleStart",day+" 19:00");p.put("scheduleEnd",day+" 21:00");p.put("isScheduleValid",true);p.put("hasConflict",false);
        p.put("subjectPlans",List.of(Map.of("subjectId",subject.getSubjectId(),"subjectName",subject.getSubjectName(),"studyMinutes",100,"carryoverMinutes",0,"newStudyMinutes",100)));
        p.put("timeSlots",List.of(slot(day,"19:00","19:50",50,subject),Map.of("startTime",day+" 19:50","endTime",day+" 20:00","minutes",10,"type","휴식"),slot(day,"20:00","20:50",50,subject),Map.of("startTime",day+" 20:50","endTime",day+" 21:00","minutes",10,"type","휴식")));
        return new ObjectMapper().writeValueAsString(p);
    }
    private static Map<String,Object> slot(LocalDate day,String start,String end,int minutes,Subject s){return Map.of("startTime",day+" "+start,"endTime",day+" "+end,"minutes",minutes,"type","공부","subjectId",s.getSubjectId(),"subjectName",s.getSubjectName(),"activity","가상 신규 학습","allocationType","NEW");}
}
