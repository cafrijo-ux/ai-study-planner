package ai_study_planner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTests {

    private UserRepository userRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // No Spring application context, datasource, or real repository is created.
        userRepository = mock(UserRepository.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userRepository)).build();
    }

    @Test
    void getUsersOmitsPasswordsAndPreservesOtherFields() throws Exception {
        User first = user(1L, "Test User", "first@example.invalid");
        User second = user(2L, "Other User", "second@example.invalid");
        when(userRepository.findAll()).thenReturn(List.of(first, second));

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(1))
                .andExpect(jsonPath("$[0].name").value("Test User"))
                .andExpect(jsonPath("$[0].email").value("first@example.invalid"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-10-19T12:00:00"))
                .andExpect(jsonPath("$[0].password").doesNotHaveJsonPath());

        verify(userRepository).findAll();
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void getUsersPreservesEmptyListResponse() throws Exception {
        when(userRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(userRepository).findAll();
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void passwordRemainsAcceptedWhenReadingUserJson() {
        // This is a synthetic test value; application configuration is never read.
        User parsed = JsonMapper.builder().build().readValue(
                "{\"password\":\"synthetic-test-value\"}", User.class);

        assertEquals("synthetic-test-value", parsed.getPassword());
    }

    private User user(long id, String name, String email) {
        User user = new User();
        user.setUserId(id);
        user.setName(name);
        user.setEmail(email);
        user.setPassword("synthetic-test-value");
        user.setCreatedAt(LocalDateTime.of(2026, 10, 19, 12, 0));
        return user;
    }
}
