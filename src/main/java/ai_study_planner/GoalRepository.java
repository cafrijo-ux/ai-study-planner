package ai_study_planner;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalRepository extends JpaRepository<Goal, Long> {
    java.util.List<Goal> findByUserId(Long userId);
}
