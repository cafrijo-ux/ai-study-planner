package ai_study_planner;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Not a JPA entity; this schema is never created by application startup. */
final class AiCostRequestRepository {
    final JdbcTemplate jdbc;
    AiCostRequestRepository(JdbcTemplate j){jdbc=j;}
    void lock(long user){if(jdbc.queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE",Long.class,user).size()!=1)throw new AiCostFailure(404,"AI_OWNER","사용자를 확인하세요.");}
    Map<String,Object> find(long user,String feature,String key){var rows=jdbc.query("SELECT goal_id,request_hash,request_state,dispatch_reserved,result_json FROM ai_generation_requests WHERE user_id=? AND feature=? AND request_key=?",(r,n)->{var m=new HashMap<String,Object>();m.put("goalId",r.getLong(1));m.put("hash",r.getString(2));m.put("state",r.getString(3));m.put("dispatchReserved",r.getBoolean(4));m.put("json",r.getString(5));return m;},user,feature,key);return rows.isEmpty()?null:rows.get(0);}
}
