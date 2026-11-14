package ai_study_planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Default OFF. Enabling requires a separately approved schema and retention policy. */
@Service
@ConditionalOnProperty(name="planner.study-log.idempotency.enabled",havingValue="true")
public class StudyLogIdempotencyService {
    public record Result(String json,boolean replayed) {}
    private final StudyLogRepository logs;private final SubjectRepository subjects;private final GoalRepository goals;
    private final StudyLogRequestRepository requests;private final TransactionTemplate tx;private final boolean required;
    private static final ObjectMapper JSON=new ObjectMapper();
    public StudyLogIdempotencyService(JdbcTemplate jdbc,StudyLogRepository logs,SubjectRepository subjects,GoalRepository goals,PlatformTransactionManager manager,
        @Value("${planner.study-log.idempotency.require-key:false}")boolean required){this.logs=logs;this.subjects=subjects;this.goals=goals;requests=new StudyLogRequestRepository(jdbc);tx=new TransactionTemplate(manager);this.required=required;}
    public boolean requireKey(){return required;}
    static void key(String key){if(key==null||!key.matches("[a-z0-9][a-z0-9_-]{15,79}"))throw new StudyLogIdempotencyFailure(400,"IDEMPOTENCY_KEY_REQUIRED","16~80자의 유효한 Idempotency-Key가 필요합니다.");}
    private long goal(long actor,Long selected,StudyLog payload){
        if(!Objects.equals(payload.getUserId(),actor))throw new StudyLogIdempotencyFailure(403,"OWNER_MISMATCH","현재 사용자의 기록만 저장할 수 있습니다.");
        var subject=subjects.findById(payload.getSubjectId()).filter(s->Objects.equals(s.getUserId(),actor)).orElseThrow(()->new StudyLogIdempotencyFailure(404,"SUBJECT_NOT_FOUND","소유 과목을 확인하세요."));
        Long goalId=subject.getGoalId();if(goalId==null||selected!=null&&!selected.equals(goalId))throw new StudyLogIdempotencyFailure(400,"GOAL_SUBJECT_MISMATCH","선택 목표의 과목만 저장할 수 있습니다.");
        goals.findById(goalId).filter(g->Objects.equals(g.getUserId(),actor)).orElseThrow(()->new StudyLogIdempotencyFailure(404,"GOAL_NOT_FOUND","소유 목표를 확인하세요."));
        if(payload.getLogId()!=null){var original=logs.findById(payload.getLogId()).filter(l->Objects.equals(l.getUserId(),actor)).orElseThrow(()->new StudyLogIdempotencyFailure(404,"LOG_NOT_FOUND","기존 기록을 확인하세요."));var originalSubject=subjects.findById(original.getSubjectId()).orElseThrow();if(!Objects.equals(originalSubject.getGoalId(),goalId))throw new StudyLogIdempotencyFailure(400,"LOG_GOAL_MISMATCH","다른 목표의 기록을 이동할 수 없습니다.");}
        return goalId;
    }
    public Result save(long actor,Long selected,String key,StudyLog payload){
        key(key);String error=StudyLogWriteValidation.error(payload);if(error!=null)throw new StudyLogIdempotencyFailure(400,"INVALID_STUDY_LOG",error);
        try{return tx.execute(status->{
            requests.lockOwner(actor);long goal=goal(actor,selected,payload);String fingerprint=fingerprint(goal,payload);var prior=requests.find(actor,key);
            if(prior!=null){if(!fingerprint.equals(prior.get("request_hash")))throw new StudyLogIdempotencyFailure(409,"IDEMPOTENCY_CONFLICT","같은 요청 키에 다른 기록을 보낼 수 없습니다.");if(!"COMPLETED".equals(prior.get("request_state"))||!(prior.get("response_json") instanceof String json))throw new StudyLogIdempotencyFailure(409,"IDEMPOTENCY_IN_PROGRESS","저장 결과가 아직 확정되지 않았습니다. GET으로 확인하세요.");return new Result(json,true);}
            requests.reserve(actor,goal,key,fingerprint);beforePersist();var candidate=new StudyLog();org.springframework.beans.BeanUtils.copyProperties(payload,candidate);var saved=logs.saveAndFlush(candidate);afterPersist();
            var result=fields(saved);result.put("idempotency",Map.of("enabled",true,"state","COMPLETED","requestKey",key,"version",1));String json=encode(result);requests.complete(actor,key,saved.getLogId(),json);return new Result(json,false);
        });}catch(StudyLogIdempotencyFailure e){throw e;}catch(PessimisticLockingFailureException|QueryTimeoutException e){throw new StudyLogIdempotencyFailure(409,"IDEMPOTENCY_BUSY","처리 중이거나 DB 잠금 대기 한도를 초과했습니다. 자동 재전송하지 말고 GET으로 확인하세요.");}catch(DataAccessException e){throw new StudyLogIdempotencyFailure(503,"IDEMPOTENCY_STORAGE_UNAVAILABLE","저장 트랜잭션을 완료하지 못했습니다. 원장 설정과 저장 결과를 확인하세요.");}catch(RuntimeException e){throw new StudyLogIdempotencyFailure(503,"IDEMPOTENCY_TRANSACTION_FAILED","저장 결과를 확인하지 못했습니다. 자동 재전송하지 말고 GET으로 확인하세요.");}
    }
    // Test subclass hooks only: no HTTP fault-injection parameter or runtime policy toggle.
    protected void beforePersist(){}
    protected void afterPersist(){}
    public String lookup(long actor,Long selected,String key){
        key(key);if(selected==null||selected<=0)throw new StudyLogIdempotencyFailure(400,"GOAL_REQUIRED","결과를 조회할 목표를 선택하세요.");
        goals.findById(selected).filter(g->Objects.equals(g.getUserId(),actor)).orElseThrow(()->new StudyLogIdempotencyFailure(404,"GOAL_NOT_FOUND","소유 목표를 확인하세요."));
        try{var row=requests.find(actor,key);var answer=new TreeMap<String,Object>();answer.put("enabled",true);answer.put("requestKey",key);answer.put("goalId",selected);
            if(row==null||!"COMPLETED".equals(row.get("request_state"))){answer.put("state","NOT_CONFIRMED");answer.put("message","커밋된 결과를 확인하지 못했습니다. 진행 중 또는 롤백 여부를 단정하지 않으며 자동 재전송하지 않습니다.");}
            else{if(((Number)row.get("goal_id")).longValue()!=selected)throw new StudyLogIdempotencyFailure(409,"IDEMPOTENCY_GOAL_CONFLICT","요청 키와 목표가 일치하지 않습니다.");answer.put("state","COMPLETED");answer.put("result",JSON.readTree(row.get("response_json").toString()));}
            return encode(answer);
        }catch(StudyLogIdempotencyFailure e){throw e;}catch(Exception e){throw new StudyLogIdempotencyFailure(503,"IDEMPOTENCY_LOOKUP_UNAVAILABLE","저장 결과를 확인하지 못했습니다. 자동 재전송하지 마세요.");}
    }
    static String fingerprint(long goal,StudyLog payload){try{var data=new TreeMap<String,Object>();data.put("version",1);data.put("operation",StudyLogRequestRepository.OP);data.put("goalId",goal);data.put("record",fields(payload));return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(data).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Fingerprint failed");}}
    static String encode(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Snapshot encoding failed");}}
    static Map<String,Object> fields(StudyLog l){var m=new TreeMap<String,Object>();m.put("logId",l.getLogId());m.put("userId",l.getUserId());m.put("subjectId",l.getSubjectId());m.put("studyDate",l.getStudyDate()==null?null:l.getStudyDate().toString());m.put("studyMinutes",l.getStudyMinutes());m.put("studyContent",l.getStudyContent());m.put("understandingScore",l.getUnderstandingScore());m.put("focusScore",l.getFocusScore());m.put("fatigueScore",l.getFatigueScore());m.put("studyMethod",l.getStudyMethod());m.put("totalQuestions",l.getTotalQuestions());m.put("correctCount",l.getCorrectCount());m.put("wrongCount",l.getWrongCount());m.put("wrongReason",l.getWrongReason());m.put("difficultTopic",l.getDifficultTopic());m.put("completed",l.getCompleted());m.put("memo",l.getMemo());m.put("createdAt",l.getCreatedAt()==null?null:l.getCreatedAt().toString());return m;}
}
