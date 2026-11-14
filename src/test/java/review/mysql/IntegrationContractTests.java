package review.mysql;
import ai_study_planner.*;
import ai_study_planner.security.DemoOwnershipAdvice;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class IntegrationContractTests {
 @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
 @Test void repeatedGoalParameterIsAmbiguousAndRejected(){var r=new MockHttpServletRequest();r.addParameter("goalId","1","3");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));assertEquals(400,assertThrows(ResponseStatusException.class,GoalSelection::requestedId).getStatusCode().value());}
 @Test void selectedGoalMustMatchLogSubject(){
  var goals=mock(GoalRepository.class);var subjects=mock(SubjectRepository.class);var logs=mock(StudyLogRepository.class);var g=new Goal();g.setGoalId(3L);g.setUserId(1L);when(goals.findById(3L)).thenReturn(Optional.of(g));var s=new Subject();s.setSubjectId(10L);s.setUserId(1L);s.setGoalId(3L);when(subjects.findById(10L)).thenReturn(Optional.of(s));var r=new MockHttpServletRequest();r.addParameter("goalId","1");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));var l=new StudyLog();l.setUserId(1L);l.setSubjectId(10L);assertEquals(400,assertThrows(ResponseStatusException.class,()->new DemoOwnershipAdvice(goals,subjects,logs).afterBodyRead(l,null,null,null,null)).getStatusCode().value());
 }
 @Test void invalidPlanDateIsHttp400NotSuccessMessage()throws Exception{var c=new StudyPlanController(mock(GoalRepository.class),mock(SubjectRepository.class),mock(StudyLogRepository.class),mock(org.springframework.jdbc.core.JdbcTemplate.class),mock(StudySavedPlanService.class));MockMvcBuilders.standaloneSetup(c).build().perform(get("/study-plan").param("date","invalid")).andExpect(status().isBadRequest());}
}
