package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.AnalysisTask;
import com.arohtea.business_service.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, String> {
    List<AnalysisTask> findByDocumentIdOrderByCreatedAtAsc(String documentId);
    List<AnalysisTask> findByStatusInAndCreatedAtBefore(List<TaskStatus> statuses, LocalDateTime before);
    long countByStatusIn(List<TaskStatus> statuses);

    List<AnalysisTask> findByDocumentIdAndStatusIn(String documentId, List<TaskStatus> statuses);

    Optional<AnalysisTask> findFirstByDocumentIdOrderByCreatedAtDesc(String documentId);

    long deleteByDocumentId(String documentId);
}
