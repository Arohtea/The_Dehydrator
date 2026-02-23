package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.AnalysisTask;
import com.arohtea.business_service.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, String> {
    List<AnalysisTask> findByDocumentId(String documentId);
    List<AnalysisTask> findByStatusInAndCreatedAtBefore(List<TaskStatus> statuses, LocalDateTime before);
}
