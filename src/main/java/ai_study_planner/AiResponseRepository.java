package ai_study_planner;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface AiResponseRepository extends JpaRepository<AiResponse,Long> {
    @Query("select a from AiResponse a where a.userId=:userId " +
           "and (:goalId is null or a.goalId=:goalId) and (:feature is null or a.feature=:feature) " +
           "order by a.createdAt desc, a.responseId desc")
    List<AiResponse> history(Long userId, Long goalId, AiResponse.Feature feature, Pageable page);
}
