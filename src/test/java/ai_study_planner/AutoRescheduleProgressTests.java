package ai_study_planner;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Reproduce ordering, split-day and cross-goal progress defects without DB/network. */
class AutoRescheduleProgressTests {
    final LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
    final GoalRepository goals=mock(GoalRepository.class);
    final SubjectRepository subjects=mock(SubjectRepository.class);
    final StudyLogRepository logs=mock(StudyLogRepository.class);
    final AutoRescheduleController controller=new AutoRescheduleController(goals,logs,subjects);
    Goal goal(long id){var g=new Goal();g.setGoalId(id);g.setUserId(1L);g.setGoalName("가상 목표 "+id);g.setDailyStudyTime(120);return g;}
    Subject subject(long id,long goal){var s=new Subject();s.setSubjectId(id);s.setGoalId(goal);s.setUserId(1L);return s;}
    StudyLog log(long id,long subject,int age,int minutes){var l=new StudyLog();l.setLogId(id);l.setUserId(1L);l.setSubjectId(subject);l.setStudyDate(today.minusDays(age));l.setStudyMinutes(minutes);l.setCompleted("완료");l.setUnderstandingScore(3);l.setFocusScore(3);return l;}
    @BeforeEach void setup(){when(goals.findAll()).thenReturn(List.of(goal(10)));when(subjects.findAll()).thenReturn(List.of(subject(100,10)));}
    @AfterEach void cleanup(){RequestContextHolder.resetRequestAttributes();}
    @Test void latestDayDoesNotDependOnRepositoryOrder(){
        when(logs.findAll()).thenReturn(List.of(log(2,100,0,100),log(1,100,1,40)));
        var result=controller.getAutoReschedule();assertEquals(100,result.get("actualStudyTime"));assertEquals(false,result.get("needReschedule"));
    }
    @Test void splitRecordsOnLatestDayAreSummed(){
        when(logs.findAll()).thenReturn(List.of(log(1,100,0,60),log(2,100,0,40)));
        var result=controller.getAutoReschedule();assertEquals(100,result.get("actualStudyTime"));assertEquals(false,result.get("needReschedule"));
    }
    @Test void legacyLatestGoalIsSelectedByIdRatherThanRepositoryOrder(){
        when(goals.findAll()).thenReturn(List.of(goal(20),goal(10)));when(subjects.findAll()).thenReturn(List.of(subject(100,10),subject(200,20)));
        when(logs.findAll()).thenReturn(List.of(log(1,200,0,100)));
        assertEquals("가상 목표 20",controller.getAutoReschedule().get("goalName"));
    }
    @Test void omittedGoalDoesNotUseAnotherGoalsRecords(){
        when(goals.findAll()).thenReturn(List.of(goal(10),goal(20)));when(subjects.findAll()).thenReturn(List.of(subject(100,10),subject(200,20)));
        when(logs.findAll()).thenReturn(List.of(log(1,100,0,40)));
        assertTrue(controller.getAutoReschedule().containsKey("message"));
    }
    @Test void explicitGoalStillUsesOnlyItsOwnRecords(){
        var request=new MockHttpServletRequest();request.setParameter("goalId","10");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(goals.findAll()).thenReturn(List.of(goal(10),goal(20)));when(subjects.findAll()).thenReturn(List.of(subject(100,10),subject(200,20)));
        when(logs.findAll()).thenReturn(List.of(log(1,100,0,100),log(2,200,0,40)));
        var result=controller.getAutoReschedule();assertEquals("가상 목표 10",result.get("goalName"));assertEquals(100,result.get("actualStudyTime"));assertEquals(false,result.get("needReschedule"));
    }
}
