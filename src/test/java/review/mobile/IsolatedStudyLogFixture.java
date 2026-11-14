package review.mobile;

import ai_study_planner.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Synthetic memory-only fixture; never reads an external configuration or database. */
public final class IsolatedStudyLogFixture {
    public static void assertH2(JdbcTemplate jdbc,String expected)throws Exception{
        if(!expected.matches("jdbc:h2:mem:mobile_study_[0-9a-f-]{36};MODE=MySQL;DB_CLOSE_DELAY=-1"))throw new IllegalStateException("Not isolated H2 URL");
        try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){
            if(!c.getMetaData().getURL().equals(expected.split(";")[0])||!c.getMetaData().getDriverName().equals("H2 JDBC Driver"))throw new IllegalStateException("H2 connection proof failed");
        }
    }
    public record Seed(long goalA,long goalB,long subjectA,long subjectB,String date,String finalJson){}
    public static JdbcTemplate prepareEmptyDatabase(ConfigurableApplicationContext ctx,String expected)throws Exception{
        var jdbc=ctx.getBean(JdbcTemplate.class);assertH2(jdbc,expected);
        // Refuse to merge with existing rows, even in another in-memory test session.
        for(String table:List.of("users","goals","subjects","study_logs","study_questions","ai_responses")){
            if(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)!=0)throw new IllegalStateException("Fixture requires empty H2");
        }
        jdbc.execute("CREATE TABLE study_schedule_settings(schedule_id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,schedule_date DATE NOT NULL,start_time TIME NOT NULL,end_time TIME NOT NULL,busy_start TIME,busy_end TIME,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(user_id,schedule_date))");
        jdbc.execute("CREATE TABLE study_saved_plans(plan_id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,goal_id BIGINT NOT NULL,plan_date DATE NOT NULL,source_date DATE NOT NULL,source_end_time TIME NOT NULL,plan_status VARCHAR(10) NOT NULL,start_time TIME NOT NULL,end_time TIME NOT NULL,busy_start TIME,busy_end TIME,plan_json CLOB NOT NULL,saved_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(user_id,goal_id,plan_date))");
        jdbc.update("INSERT INTO users(user_id,name,email) VALUES(1,?,?)","[격리 H2] 가상 사용자","mobile-h2@example.invalid");
        return jdbc;
    }
    public static Seed seed(ConfigurableApplicationContext ctx,String expected)throws Exception{
        var jdbc=prepareEmptyDatabase(ctx,expected);
        LocalDate date=LocalDate.now(ZoneId.of("Asia/Seoul"));
        var goals=ctx.getBean(GoalRepository.class);var subjects=ctx.getBean(SubjectRepository.class);
        Goal a=goal(goals,"[격리 H2] 목표 A",date),b=goal(goals,"[격리 H2] 목표 B",date);
        Subject sa=subject(subjects,a.getGoalId(),"[가상] 운영체제"),sb=subject(subjects,b.getGoalId(),"[가상] 네트워크");
        var plan=new LinkedHashMap<String,Object>();
        plan.put("goalId",a.getGoalId());plan.put("scheduleDate",date.toString());plan.put("planStatus","FINAL");
        plan.put("planTitle","[격리 H2] 가상 FINAL 스냅샷");plan.put("fixtureSource","SYNTHETIC_H2_ONLY");
        plan.put("studyMinutes",100);plan.put("carryoverMinutes",9);plan.put("newStudyMinutes",91);plan.put("breakMinutes",20);
        plan.put("subjectPlans",List.of(Map.of("subjectId",sa.getSubjectId(),"subjectName",sa.getSubjectName(),"studyMinutes",100,"carryoverMinutes",9,"newStudyMinutes",91)));
        plan.put("timeSlots",List.of(slot(date,"10:00","10:09",9,sa,"가상 보충 학습"),slot(date,"10:09","11:40",91,sa,"가상 신규 학습"),Map.of("startTime",date+" 11:40","endTime",date+" 12:00","minutes",20,"type","휴식","activity","가상 휴식")));
        String json=new ObjectMapper().writeValueAsString(plan);
        jdbc.update("INSERT INTO study_saved_plans(user_id,goal_id,plan_date,source_date,source_end_time,plan_status,start_time,end_time,plan_json) VALUES(1,?,?,?,'23:59:00','FINAL','10:00:00','12:00:00',?)",a.getGoalId(),java.sql.Date.valueOf(date),java.sql.Date.valueOf(date.minusDays(1)),json);
        if(ctx.getBean(StudyLogRepository.class).count()!=0||jdbc.queryForObject("SELECT COUNT(*) FROM study_saved_plans",Integer.class)!=1)throw new IllegalStateException("Fixture counts mismatch");
        return new Seed(a.getGoalId(),b.getGoalId(),sa.getSubjectId(),sb.getSubjectId(),date.toString(),json);
    }
    private static Goal goal(GoalRepository repo,String name,LocalDate date){Goal g=new Goal();g.setUserId(1L);g.setGoalName(name);g.setExamDate(date.plusDays(30));g.setDailyStudyTime(120);return repo.save(g);}
    private static Subject subject(SubjectRepository repo,long goal,String name){Subject s=new Subject();s.setUserId(1L);s.setGoalId(goal);s.setSubjectName(name);s.setDifficulty("보통");s.setPriorityLevel("높음");s.setCurrentConfidence("보통");return repo.save(s);}
    private static Map<String,Object> slot(LocalDate d,String start,String end,int min,Subject subject,String activity){return Map.of("startTime",d+" "+start,"endTime",d+" "+end,"minutes",min,"type","공부","subjectId",subject.getSubjectId(),"subjectName",subject.getSubjectName(),"activity",activity);}
}
