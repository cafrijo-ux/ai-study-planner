package ai_study_planner;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SavedPlanGoalReadH2Tests {
    JdbcTemplate jdbc;
    MockMvc mvc;
    String original;
    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:plan_read_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try(var c=ds.getConnection()){assertTrue(c.getMetaData().getURL().startsWith("jdbc:h2:mem:"));}
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE goals(goal_id BIGINT PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE study_saved_plans(user_id BIGINT,goal_id BIGINT,plan_date DATE,plan_json TEXT,source_date DATE,source_end_time TIME,plan_status VARCHAR(10),start_time TIME,end_time TIME,busy_start TIME,busy_end TIME,saved_at TIMESTAMP)");
        jdbc.update("INSERT INTO goals VALUES(1,1),(3,1),(4,2)");
        original="""
            {"goalId":1,"scheduleDate":"2026-10-20","studyMinutes":100,"carryoverMinutes":9,"newStudyMinutes":91,"timeSlots":[{"minutes":9,"subjectName":"운영체제","activity":"보충"}]}
            """;
        jdbc.update("INSERT INTO study_saved_plans VALUES(1,1,'2026-10-20',?,'2026-10-19','23:00:00','FINAL','10:00:00','12:00:00',NULL,NULL,'2026-10-19 12:00:00')",original);
        mvc=MockMvcBuilders.standaloneSetup(new StudyRescheduleSaveController(jdbc,mock(StudyPlanController.class),new StudySavedPlanService(jdbc))).build();
    }
    @AfterEach void preserve(){
        assertEquals(original,jdbc.queryForObject("SELECT plan_json FROM study_saved_plans WHERE goal_id=1",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM study_saved_plans",Integer.class));
        jdbc.execute("SHUTDOWN");
    }
    @Test void explicitGoalReturnsStoredFinalUnchanged() throws Exception {
        mvc.perform(get("/study-reschedule-saved").param("goalId","1").param("date","2026-10-20"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.goalId").value(1))
            .andExpect(jsonPath("$.planStatus").value("FINAL")).andExpect(jsonPath("$.studyMinutes").value(100))
            .andExpect(jsonPath("$.carryoverMinutes").value(9)).andExpect(jsonPath("$.newStudyMinutes").value(91))
            .andExpect(jsonPath("$.timeSlots[0].minutes").value(9));
    }
    @Test void otherOwnedGoalIsEmpty() throws Exception {
        mvc.perform(get("/study-reschedule-saved").param("goalId","3").param("date","2026-10-20"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(false));
    }
    @Test void missingGoalParameterIsRejectedWithoutReadingAnotherGoalsPlan() throws Exception {
        mvc.perform(get("/study-reschedule-saved").param("date","2026-10-20"))
            .andExpect(status().isBadRequest());
    }
    @Test void foreignOwnedGoalIsRejected() throws Exception {
        mvc.perform(get("/study-reschedule-saved").param("goalId","4").param("date","2026-10-20"))
            .andExpect(status().isNotFound());
    }
    @Test void missingDateIsEmptyAndInvalidDateRejected() throws Exception {
        mvc.perform(get("/study-reschedule-saved").param("goalId","1").param("date","2026-10-21"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(false));
        mvc.perform(get("/study-reschedule-saved").param("goalId","1").param("date","bad"))
            .andExpect(status().isBadRequest());
    }
}
