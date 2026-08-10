package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.AnalysisTask;
import com.arohtea.business_service.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AnalysisTask t where t.id = :id")
    Optional<AnalysisTask> findByIdForUpdate(@Param("id") String id);

    List<AnalysisTask> findByDocumentIdOrderByCreatedAtAsc(String documentId);
    List<AnalysisTask> findByStatusInAndCreatedAtBefore(List<TaskStatus> statuses, LocalDateTime before);
    long countByStatusIn(List<TaskStatus> statuses);

    List<AnalysisTask> findByDocumentIdAndStatusIn(String documentId, List<TaskStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AnalysisTask t where t.documentId = :documentId and t.status in :statuses")
    List<AnalysisTask> findByDocumentIdAndStatusInForUpdate(
            @Param("documentId") String documentId,
            @Param("statuses") List<TaskStatus> statuses);

    Optional<AnalysisTask> findFirstByDocumentIdOrderByCreatedAtDesc(String documentId);

    long deleteByDocumentId(String documentId);
}
