package ai_study_planner;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubjectRepository extends JpaRepository<Subject, Long> {
    java.util.List<Subject> findByUserIdAndGoalId(Long userId, Long goalId);
}
