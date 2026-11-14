package ai_study_planner;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Default OFF. Durable reservation before dispatch; NEVER retries an external request. */
@Service @ConditionalOnProperty(name="planner.ai-cost.enabled",havingValue="true")
public class AiCostProtection {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final ZoneId KOREA=ZoneId.of("Asia/Seoul");
    final AiCostRequestRepository rows;final GoalRepository goals;final TransactionTemplate tx;final Environment env;
    record Attempt(long user,long goal,String feature,String key,String model,int tokens){}
    private final ThreadLocal<Attempt> active=new ThreadLocal<>();
    public AiCostProtection(JdbcTemplate jdbc,GoalRepository goals,PlatformTransactionManager manager,Environment env){rows=new AiCostRequestRepository(jdbc);this.goals=goals;this.env=env;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
    static void feature(String f){if(!Set.of("DAILY_COACHING","STUDY_SUMMARY").contains(f))throw fail(400,"AI_FEATURE","일반 코칭 또는 공부 기록 요약만 지원합니다.");}
    static void key(String k){if(k==null||!k.matches("[a-z0-9][a-z0-9_-]{15,79}"))throw fail(400,"AI_REQUEST_KEY","유효한 요청 키가 필요합니다.");}
    static AiCostFailure fail(int s,String c,String m){return new AiCostFailure(s,c,m);}
    String prefix(String f){return "planner.ai-cost."+(f.equals("DAILY_COACHING")?"daily":"summary")+".";}
    public Map<String,Object> capabilities(){var features=new TreeMap<String,Object>();for(String f:List.of("DAILY_COACHING","STUDY_SUMMARY")){String p=prefix(f);int max=env.getProperty(p+"max-attempts",Integer.class,0),tokens=env.getProperty(p+"max-output-tokens",Integer.class,0);String model=env.getProperty(p+"approved-model","");features.put(f,Map.of("configured",max>0&&tokens>0&&tokens<=1000&&model.startsWith("gemini-"),"approvedModel",model,"maxAttempts",max,"maxOutputTokens",tokens));}return Map.of("enabled",true,"version",1,"generationMethod","POST","lookupPath","/ai-generation-requests","schemaVerified",false,"features",features);}
    long owner(){var a=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();if(a==null||!a.isAuthenticated()||a.getAuthorities().stream().noneMatch(x->x.getAuthority().equals("ROLE_DEMO_OWNER")))throw fail(401,"AI_AUTH","로그인이 필요합니다.");return 1;}
    void goal(long user,Long goal){if(goal==null||goals.findById(goal).filter(g->Objects.equals(g.getUserId(),user)).isEmpty())throw fail(404,"AI_GOAL","소유 목표를 선택하세요.");}
    public Map<String,Object> execute(String f,Long goal,String provider,String model,String k,String method,Map<String,Object> body,Supplier<Map<String,Object>> generate){return execute(owner(),f,goal,provider,model,k,method,body,generate);}
    // Package-level actor entry supports synthetic multi-user service tests; never an HTTP user parameter.
    Map<String,Object> execute(long user,String f,Long goal,String provider,String model,String k,String method,Map<String,Object> body,Supplier<Map<String,Object>> generate){
        feature(f);goal(user,goal);
        if(!"POST".equals(method))throw fail(405,"AI_EXPLICIT_POST_REQUIRED","비용 보호 모드에서는 명시적인 POST 생성만 허용합니다. 저장 결과는 GET으로 확인하세요.");
        key(k);if(body==null||!Boolean.TRUE.equals(body.get("confirmPaidCall"))||!Objects.equals(body.get("expectedModel"),model)||!body.keySet().equals(Set.of("confirmPaidCall","expectedModel")))throw fail(400,"AI_CONFIRMATION","비용 확인과 현재 모델을 명시해야 합니다.");
        String hash=sha(encode(new TreeMap<>(Map.of("version",1,"feature",f,"goalId",goal,"provider",provider,"model",model,"body",new TreeMap<>(body)))));
        try{
            var prior=tx.execute(s->{rows.lock(user);var p=rows.find(user,f,k);if(p!=null){if(!Objects.equals(p.get("hash"),hash)||!Objects.equals(p.get("goalId"),goal))throw fail(409,"AI_REQUEST_CONFLICT","같은 키의 목표·모델·입력이 다릅니다.");return p;}
                String base=prefix(f);int max=env.getProperty(base+"max-attempts",Integer.class,0),tokens=env.getProperty(base+"max-output-tokens",Integer.class,0);String approved=env.getProperty(base+"approved-model","");
                if(!"gemini".equals(provider)||!model.startsWith("gemini-")||!model.equals(approved)||max<=0||tokens<=0||tokens>1000)throw fail(503,"AI_POLICY_UNAPPROVED","기능별 모델·일일 시도·토큰 정책이 승인 설정되지 않아 생성하지 않습니다.");
                if(rows.jdbc.queryForObject("SELECT COUNT(*) FROM ai_generation_requests WHERE user_id=? AND feature=? AND request_state IN ('RESERVED','DISPATCH_RESERVED','UNKNOWN')",Integer.class,user,f)>0)throw fail(409,"AI_RESULT_UNCERTAIN","진행 중 또는 불확실한 요청이 있습니다. 새 호출 대신 GET으로 확인하세요.");
                LocalDate day=LocalDate.now(KOREA);int used=rows.jdbc.queryForObject("SELECT COUNT(*) FROM ai_generation_requests WHERE user_id=? AND feature=? AND policy_day=? AND model=?",Integer.class,user,f,java.sql.Date.valueOf(day),model);
                if(used>=max)throw fail(429,"AI_ATTEMPT_LIMIT","승인된 일일 예약 한도에 도달했습니다. Provider를 호출하지 않습니다.");
                rows.jdbc.update("INSERT INTO ai_generation_requests(user_id,feature,request_key,goal_id,request_hash,request_state,policy_day,provider,model,created_at,dispatch_reserved,provider_response_received,result_stored) VALUES(?,?,?,?,?,'RESERVED',?,?,?,CURRENT_TIMESTAMP,FALSE,FALSE,FALSE)",user,f,k,goal,hash,java.sql.Date.valueOf(day),provider,model);return null;
            });
            if(prior!=null){if("COMPLETED".equals(prior.get("state"))||"NO_SEND".equals(prior.get("state")))return decode((String)prior.get("json"));throw fail(409,"AI_RESULT_UNCERTAIN","이 요청은 처리 중 또는 불확실합니다. 자동 재호출하지 않고 GET으로 확인하세요.");}
            if(active.get()!=null)throw fail(409,"AI_REENTRY","중첩 생성 요청은 허용하지 않습니다.");
            int tokens=env.getProperty(prefix(f)+"max-output-tokens",Integer.class,0);active.set(new Attempt(user,goal,f,k,model,tokens));
            Map<String,Object> result;
            try{result=generate.get();}catch(Exception e){result=new LinkedHashMap<>();result.put("goalId",goal);result.put("model",model);result.put("provider",provider);result.put("responseSource","not_called");result.put("message","생성 결과 미확인. 새 요청을 보내지 말고 결과를 조회하세요.");}
            boolean sent=Boolean.TRUE.equals(rows.find(user,f,k).get("dispatchReserved"));
            boolean received=result.get("statusCode") instanceof Number;
            String field=f.equals("DAILY_COACHING")?"llmResponse":"summary";
            boolean answer=result.get(field) instanceof String text&&!text.isBlank();
            boolean valid=Objects.equals(goal,result.get("goalId"))&&Objects.equals(provider,result.get("provider"))&&Objects.equals(model,result.get("model"))&&"provider".equals(result.get("responseSource"))&&result.get("responseModel") instanceof String actual&&actual.startsWith(model);
            // A rule/demo reply is not a provider answer. The protected path never fabricates provider provenance.
            String state=!sent?"NO_SEND":answer&&valid?"COMPLETED":"UNKNOWN";
            if(sent&&!valid){result.remove(field);result.put("responseSource","unknown");result.put("message","모델·목표·출처를 확인하지 못했습니다. 새 호출 없이 상태를 확인하세요.");}
            result.remove("prompt");result.remove("rawResponse");
            result.put("costProtection",Map.of("enabled",true,"requestKey",k,"state",state,"dispatchReserved",sent,"providerResponseReceived",received,"resultStored",true,"estimatedCostKnown",false,"billingStatus","NOT_VERIFIED","providerProcessing",sent?"NOT_INDEPENDENTLY_VERIFIED":"NOT_DISPATCHED"));
            final var completed=result;String encoded=encode(result);
            beforeResultStore();
            tx.executeWithoutResult(s->{rows.lock(user);if(rows.jdbc.update("UPDATE ai_generation_requests SET request_state=?,provider_response_received=?,result_stored=TRUE,ai_response_saved=?,response_id=?,result_json=?,usage_json=?,completed_at=CURRENT_TIMESTAMP WHERE user_id=? AND feature=? AND request_key=? AND request_state IN ('RESERVED','DISPATCH_RESERVED')",state,received,completed.get("responseSaved") instanceof Boolean b?b:null,completed.get("responseId"),encoded,completed.get("usage")==null?null:encode(completed.get("usage")),user,f,k)!=1)throw new IllegalStateException();});
            return decode(encoded); // First response and replay use exactly the persisted date/string representation.
        }catch(AiCostFailure e){throw e;}catch(Exception e){throw fail(503,"AI_LEDGER_UNAVAILABLE","비용 원장의 결과를 확인하지 못했습니다. 재호출하지 말고 저장 이력을 확인하세요.");}finally{active.remove();}
    }
    protected void beforeResultStore(){} // Offline subclass fault injection only; never an HTTP switch.
    public boolean canRecord(Map<String,Object> result){var a=active.get();if(a==null)return false;if("rule".equals(result.get("responseSource")))return Objects.equals(a.goal,result.get("goalId"));return Objects.equals(a.goal,result.get("goalId"))&&"gemini".equals(result.get("provider"))&&Objects.equals(a.model,result.get("model"))&&"provider".equals(result.get("responseSource"))&&result.get("responseModel") instanceof String m&&m.startsWith(a.model);}
    /** Must run immediately before the ONE HttpClient.send, after request construction. */
    public void beforeSend(String prompt){var a=active.get();if(a==null)throw fail(503,"AI_DISPATCH_WITHOUT_RESERVATION","승인된 호출 예약이 없습니다.");tx.executeWithoutResult(s->{rows.lock(a.user);if(rows.jdbc.update("UPDATE ai_generation_requests SET request_state='DISPATCH_RESERVED',dispatch_reserved=TRUE,prompt_sha256=?,dispatch_at=CURRENT_TIMESTAMP WHERE user_id=? AND feature=? AND request_key=? AND request_state='RESERVED'",sha(prompt),a.user,a.feature,a.key)!=1)throw fail(409,"AI_ALREADY_DISPATCHED","이미 전송 예약된 요청입니다. 재호출하지 않습니다.");});}
    public int outputTokens(int legacy){var a=active.get();return a==null?legacy:Math.min(legacy,a.tokens);}
    public Map<String,Object> lookup(String feature,Long goal,String key){return lookup(owner(),feature,goal,key);}
    Map<String,Object> lookup(long user,String f,Long goal,String k){feature(f);key(k);goal(user,goal);try{var row=rows.find(user,f,k);var result=new LinkedHashMap<String,Object>();result.put("feature",f);result.put("goalId",goal);result.put("requestKey",k);result.put("enabled",true);if(row==null){result.put("state","NOT_CONFIRMED");return result;}if(!Objects.equals(row.get("goalId"),goal))throw fail(409,"AI_REQUEST_GOAL","요청 키의 목표가 다릅니다.");result.put("state",row.get("state"));result.put("dispatchReserved",row.get("dispatchReserved"));if(row.get("json") instanceof String json)result.put("result",decode(json));return result;}catch(AiCostFailure e){throw e;}catch(Exception e){throw fail(503,"AI_LOOKUP_UNAVAILABLE","결과 미확인. 자동 생성 요청을 보내지 마세요.");}}
    static Object normalized(Object o){if(o instanceof java.time.temporal.TemporalAccessor)return o.toString();if(o instanceof Map<?,?> m){var v=new TreeMap<String,Object>();m.forEach((k,x)->v.put(k.toString(),normalized(x)));return v;}if(o instanceof Collection<?> c)return c.stream().map(AiCostProtection::normalized).toList();return o;}
    static String encode(Object o){try{return JSON.writeValueAsString(normalized(o));}catch(Exception e){throw new IllegalStateException("AI snapshot unavailable");}}
    static Map<String,Object> decode(String s){try{return JSON.readValue(s,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw fail(503,"AI_SNAPSHOT_INVALID","저장 결과를 읽을 수 없습니다. 새 요청을 보내지 마세요.");}}
    static String sha(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException();}}
}
