package ai_study_planner;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class FinalAuditAnalysisTests {
 final StudyLogRepository logs=mock(StudyLogRepository.class);
 final GoalRepository goals=mock(GoalRepository.class);
 final SubjectRepository subjects=mock(SubjectRepository.class);
 @BeforeEach void fixtures(){
  var g=new Goal();g.setGoalId(1L);g.setUserId(1L);when(goals.findAll()).thenReturn(List.of(g));
  var s=new Subject();s.setSubjectId(1L);s.setGoalId(1L);s.setUserId(1L);when(subjects.findAll()).thenReturn(List.of(s));
 }
 StudyLog log(Integer understanding,Integer focus){var l=new StudyLog();l.setUserId(1L);l.setSubjectId(1L);l.setStudyMinutes(40);l.setUnderstandingScore(understanding);l.setFocusScore(focus);return l;}
 @Test void summaryWithoutRecordsDoesNotClaimMeasuredZero(){when(logs.findAll()).thenReturn(List.of());var r=new StudyAnalysisController(logs,goals,subjects).getAnalysisSummary("current");assertEquals(0,r.get("recordCount"));assertNull(r.get("averageUnderstanding"));assertNull(r.get("averageFocus"));}
 @Test void summaryMissingAndInvalidScoresDoNotPolluteMeasuredAverages(){when(logs.findAll()).thenReturn(List.of(log(null,null),log(0,6),log(3,4)));var r=new StudyAnalysisController(logs,goals,subjects).getAnalysisSummary("current");assertEquals(120,r.get("totalStudyMinutes"));assertEquals(3.0,r.get("averageUnderstanding"));assertEquals(4.0,r.get("averageFocus"));}
 @Test void subjectWithTimeButNoScoresDoesNotClaimMeasuredZero(){when(logs.findAll()).thenReturn(List.of(log(null,null)));var r=new SubjectAnalysisController(logs,subjects,goals).getSubjectAnalysis();var row=(Map<?,?>)((List<?>)r.get("subjectAnalysis")).get(0);assertEquals(40,row.get("totalStudyMinutes"));assertNull(row.get("averageUnderstanding"));assertNull(row.get("averageFocus"));}
 @Test void subjectIgnoresInvalidHistoricalScores(){when(logs.findAll()).thenReturn(List.of(log(0,6),log(3,4)));var r=new SubjectAnalysisController(logs,subjects,goals).getSubjectAnalysis();var row=(Map<?,?>)((List<?>)r.get("subjectAnalysis")).get(0);assertEquals(3.0,row.get("averageUnderstanding"));assertEquals(4.0,row.get("averageFocus"));}
}
