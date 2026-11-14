package ai_study_planner;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;

/** Actual loopback HTTP: requires a separate user approval and explicit opt-in.
 * Never included in final-audit.gradle or ordinary offline runs.
 */
@EnabledIfSystemProperty(named="question.browser.enabled",matches="true")
@SpringBootTest(classes=AiStudyPlannerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudyQuestionHttpBrowserTests {
 @DynamicPropertySource static void configure(DynamicPropertyRegistry r){StudyQuestionFlowTests.configuration(r);}
 @Autowired WebApplicationContext context;
 @AfterAll static void restore(){StudyQuestionFlowTests.restoreGuard();}
 @Test void explicitHttpBrowserOnly()throws Exception{
  var fixture=new StudyQuestionFlowTests();context.getAutowireCapableBeanFactory().autowireBean(fixture);fixture.setup();fixture.liveBrowserUsesActualSpringSecurityAndH2();
 }
}
