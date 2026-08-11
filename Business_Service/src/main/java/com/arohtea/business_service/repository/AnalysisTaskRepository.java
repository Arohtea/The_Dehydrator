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

/**
 * 分析任务数据访问接口。
 *
 * <p>带 `ForUpdate` 的查询使用悲观写锁，供状态迁移、取消和删除协同使用。</p>
 */
public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, String> {
    /**
     * 按任务 ID 加悲观写锁查询。
     *
     * @param id 任务 ID
     * @return 加锁后的任务
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AnalysisTask t where t.id = :id")
    Optional<AnalysisTask> findByIdForUpdate(@Param("id") String id);

    /** @param documentId 文档 ID @return 按创建时间升序排列的任务 */
    List<AnalysisTask> findByDocumentIdOrderByCreatedAtAsc(String documentId);

    /** @param statuses 待匹配状态 @param before 创建时间上限 @return 超时候选任务 */
    List<AnalysisTask> findByStatusInAndCreatedAtBefore(List<TaskStatus> statuses, LocalDateTime before);

    /** @param statuses 活动状态集合 @return 当前活动任务数 */
    long countByStatusIn(List<TaskStatus> statuses);

    /** @param documentId 文档 ID @param statuses 活动状态集合 @return 文档活动任务 */
    List<AnalysisTask> findByDocumentIdAndStatusIn(String documentId, List<TaskStatus> statuses);

    /**
     * 锁定文档下全部指定状态的任务。
     *
     * @param documentId 文档 ID
     * @param statuses 待匹配状态
     * @return 加锁后的任务列表
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AnalysisTask t where t.documentId = :documentId and t.status in :statuses")
    List<AnalysisTask> findByDocumentIdAndStatusInForUpdate(
            @Param("documentId") String documentId,
            @Param("statuses") List<TaskStatus> statuses);

    /** @param documentId 文档 ID @return 最新任务 */
    Optional<AnalysisTask> findFirstByDocumentIdOrderByCreatedAtDesc(String documentId);

    /** @param documentId 文档 ID @return 被删除的任务数量 */
    long deleteByDocumentId(String documentId);
}
