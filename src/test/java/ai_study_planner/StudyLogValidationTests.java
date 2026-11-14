package ai_study_planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC serialization/validation with a Mock repository only. No Spring Boot, datasource or server. */
class StudyLogValidationTests {
    StudyLogRepository repository;
    MockMvc mvc;
    final ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void setup() {
        repository = mock(StudyLogRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new StudyLogController(repository)).build();
        when(repository.save(any())).thenAnswer(invocation -> {
            StudyLog row = invocation.getArgument(0); row.setLogId(10L); return row;
        });
    }
    Map<String,Object> valid() {
        var values = new LinkedHashMap<String,Object>();
        values.put("userId",1); values.put("subjectId",1); values.put("studyDate","2026-10-19");
        values.put("studyMinutes",40); values.put("studyContent","프로세스와 스레드 비교");
        values.put("understandingScore",3); values.put("focusScore",3); values.put("fatigueScore",3);
        values.put("totalQuestions",0); values.put("correctCount",0); values.put("wrongCount",0);
        return values;
    }
    @Test void validRecordReturnsFrontendContract() throws Exception {
        mvc.perform(post("/study-logs").contentType("application/json").content(mapper.writeValueAsString(valid())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.logId").value(10))
                .andExpect(jsonPath("$.subjectId").value(1)).andExpect(jsonPath("$.studyDate").value("2026-10-19"))
                .andExpect(jsonPath("$.studyMinutes").value(40)).andExpect(jsonPath("$.totalQuestions").value(0))
                .andExpect(jsonPath("$.studyContent").value("프로세스와 스레드 비교"));
        verify(repository,times(1)).save(any());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"   "})
    void missingContentNeverSaves(String content) throws Exception {
        var body=valid();body.put("studyContent",content);
        mvc.perform(post("/study-logs").contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("공부 내용을 입력해야 합니다."));
        verify(repository,never()).save(any());
    }
    @Test void questionAdditionCannotOverflowIntoAcceptedRecord() throws Exception {
        var body=valid();body.put("totalQuestions",Integer.MAX_VALUE);body.put("correctCount",Integer.MAX_VALUE);body.put("wrongCount",1);
        mvc.perform(post("/study-logs").contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());verify(repository,never()).save(any());
    }
    @Test void tenWrongAnswersRemainZeroCorrect() throws Exception {
        var body=valid();body.put("totalQuestions",10);body.put("wrongCount",10);
        mvc.perform(post("/study-logs").contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.correctCount").value(0))
                .andExpect(jsonPath("$.wrongCount").value(10));
    }
    @ParameterizedTest @ValueSource(strings={"studyMinutes","understandingScore","focusScore","fatigueScore"})
    void invalidMeasurementsKeepExistingRejection(String field) throws Exception {
        var body=valid();body.put(field,0);
        mvc.perform(post("/study-logs").contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());verify(repository,never()).save(any());
    }
}
