package ai_study_planner;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Explicit SQL only. Not an Entity: JPA cannot create/migrate this table implicitly. */
final class StudyLogRequestRepository {
    static final String OP="STUDY_LOG_SAVE";
    private final JdbcTemplate jdbc;
    StudyLogRequestRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    void lockOwner(long user){if(jdbc.queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE",Long.class,user).size()!=1)throw new StudyLogIdempotencyFailure(404,"OWNER_NOT_FOUND","사용자를 확인하세요.");}
    Map<String,Object> find(long user,String key){var rows=jdbc.query("SELECT goal_id,request_hash,request_state,response_json,log_id FROM study_log_requests WHERE user_id=? AND operation=? AND request_key=?",(rs,n)->{Map<String,Object> m=new HashMap<>();m.put("goal_id",rs.getLong("goal_id"));m.put("request_hash",rs.getString("request_hash"));m.put("request_state",rs.getString("request_state"));m.put("response_json",rs.getString("response_json"));m.put("log_id",rs.getObject("log_id"));return m;},user,OP,key);return rows.isEmpty()?null:rows.get(0);}
    void reserve(long user,long goal,String key,String hash){jdbc.update("INSERT INTO study_log_requests(user_id,operation,request_key,goal_id,request_hash,request_state,created_at) VALUES(?,?,?,?,?,'PROCESSING',CURRENT_TIMESTAMP)",user,OP,key,goal,hash);}
    void complete(long user,String key,long logId,String json){if(jdbc.update("UPDATE study_log_requests SET request_state='COMPLETED',log_id=?,response_json=?,completed_at=CURRENT_TIMESTAMP WHERE user_id=? AND operation=? AND request_key=? AND request_state='PROCESSING'",logId,json,user,OP,key)!=1)throw new IllegalStateException("Ledger transition failed");}
}
