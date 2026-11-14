package ai_study_planner;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StudyLogRepository extends JpaRepository<StudyLog, Long> {
}