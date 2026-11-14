package ai_study_planner;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StudyProgressControllerTests {
    private static final LocalDate TODAY = LocalDate.of(2026,10,19);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private Map<String,Object> plan;
    private long actual, count;
    private MockedStatic<LocalDate> dates;
    private MockedStatic<LocalDateTime> times;

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        StudyPlanController plans = mock(StudyPlanController.class);
        plan = new LinkedHashMap<>(Map.of("studyMinutes",100,"isScheduleValid",true,
                "scheduleEnd",TODAY+" 20:00","examDate",TODAY.plusDays(30).toString()));
        when(plans.getStudyPlan(nullable(String.class),nullable(String.class),
                nullable(String.class),nullable(String.class))).thenAnswer(i -> plan);
        when(jdbc.queryForList(anyString(),eq(Long.class),any(Object[].class))).thenReturn(List.of(10L));
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class)))
                .thenAnswer(i -> ((String)i.getArgument(0)).contains("COUNT(*)") ? count : actual);
        mvc = MockMvcBuilders.standaloneSetup(new StudyProgressController(jdbc,plans)).build();
        dates = mockStatic(LocalDate.class,CALLS_REAL_METHODS);
        dates.when(() -> LocalDate.now(KOREA)).thenReturn(TODAY);
        times = mockStatic(LocalDateTime.class,CALLS_REAL_METHODS);
    }

    @AfterEach void tearDown() {
        if(times != null) times.close();
        if(dates != null) dates.close();
        verify(jdbc,never()).getDataSource();
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @ParameterizedTest
    @CsvSource({
            "19:59,0,0,학습 진행 중,IN_PROGRESS,0,false",
            "20:00,0,0,기록 확인 필요,UNRECORDED,0,true",
            "20:01,0,0,기록 확인 필요,UNRECORDED,0,true",
            "19:59,40,1,학습 진행 중,RECORDED,0,false",
            "20:00,40,1,목표 미달,RECORDED,60,true",
            "20:01,100,1,목표 달성,RECORDED,0,true",
            "20:01,120,2,목표 달성,RECORDED,0,true"
    })
    void progressDistinguishesMissingRecordsFromZeroStudy(String time,long minutes,long records,
            String statusValue,String recordStatus,int deficit,boolean ended) throws Exception {
        actual=minutes; count=records;
        at(time);
        mvc.perform(get("/study-progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(statusValue))
                .andExpect(jsonPath("$.recordStatus").value(recordStatus))
                .andExpect(jsonPath("$.hasStudyRecord").value(records>0))
                .andExpect(jsonPath("$.scheduleFinished").value(ended))
                .andExpect(jsonPath("$.plannedStudyMinutes").value(100))
                .andExpect(jsonPath("$.actualStudyMinutes").value((int)minutes))
                .andExpect(jsonPath("$.remainingStudyMinutes").value(Math.max(0,100-(int)minutes)))
                .andExpect(jsonPath("$.achievementRate").value((double)minutes))
                .andExpect(jsonPath("$.confirmedShortfallMinutes").value(deficit))
                .andExpect(jsonPath("$.suggestedNextDayPriorityMinutes").value(deficit==0?0:9));
    }

    @Test void savedStringExamDateUsesActualRemainingDays() throws Exception {
        actual=40; count=1; at("20:01");
        plan.put("examDate",TODAY.plusDays(2).toString());
        mvc.perform(get("/study-progress")).andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedNextDayPriorityMinutes").value(60));
    }

    @Test void missingEndInOldFinalReturnsGuidanceInsteadOfException() throws Exception {
        at("20:01"); plan.remove("scheduleEnd");
        mvc.perform(get("/study-progress")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("저장된 계획의 종료 시각을 확인해 주세요."));
    }

    private void at(String time) {
        LocalDateTime now=LocalDateTime.of(TODAY,LocalTime.parse(time));
        times.when(() -> LocalDateTime.now(KOREA)).thenReturn(now);
    }
}
