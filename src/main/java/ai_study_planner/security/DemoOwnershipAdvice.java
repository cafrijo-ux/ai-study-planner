package ai_study_planner.security;
import ai_study_planner.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.springframework.web.server.ResponseStatusException;
import java.lang.reflect.Type;
import java.util.Objects;
/** Protect legacy entity-based writes, including IDs that would otherwise trigger JPA merge. */
@ControllerAdvice
public class DemoOwnershipAdvice extends RequestBodyAdviceAdapter {
    private final GoalRepository goals; private final SubjectRepository subjects; private final StudyLogRepository logs;
    public DemoOwnershipAdvice(GoalRepository g,SubjectRepository s,StudyLogRepository l){goals=g;subjects=s;logs=l;}
    @Override public boolean supports(MethodParameter p,Type t,Class<? extends HttpMessageConverter<?>> c){return true;}
    private static void owner(Long id){if(!Objects.equals(id,1L))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"이 데이터에 접근할 수 없습니다.");}
    private Goal goal(Long id){return goals.findById(id).filter(g->Objects.equals(g.getUserId(),1L)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"목표를 찾을 수 없습니다."));}
    @Override public Object afterBodyRead(Object b,HttpInputMessage m,MethodParameter p,Type t,Class<? extends HttpMessageConverter<?>> c){
        if(b instanceof Goal g){owner(g.getUserId());if(g.getGoalId()!=null)goal(g.getGoalId());}
        if(b instanceof Subject s){
            // The integrated registration service sets optional userId itself and checks its path goal.
            if(p.getContainingClass()==GoalRegistrationController.class)return b;
            owner(s.getUserId());if(s.getGoalId()!=null)goal(s.getGoalId());
            if(s.getSubjectId()!=null)owner(subjects.findById(s.getSubjectId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)).getUserId());
        }
        if(b instanceof StudyLog l){
            owner(l.getUserId());
            Long selected=GoalSelection.requestedId();
            Subject target=null;
            if(l.getSubjectId()!=null){
                target=subjects.findById(l.getSubjectId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
                owner(target.getUserId());goal(target.getGoalId());
                if(selected!=null&&!selected.equals(target.getGoalId()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"선택 목표의 과목만 저장할 수 있습니다.");
            }
            if(l.getLogId()!=null){
                StudyLog existing=logs.findById(l.getLogId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
                owner(existing.getUserId());
                if(target!=null||selected!=null){
                    Subject previous=subjects.findById(existing.getSubjectId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
                    // A missing goalId must not allow an existing record to move between goals.
                    if((target!=null&&!Objects.equals(previous.getGoalId(),target.getGoalId()))
                            ||(selected!=null&&!selected.equals(previous.getGoalId())))
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"다른 목표의 기록을 변경할 수 없습니다.");
                }
            }
        }
        return b;
    }
}
