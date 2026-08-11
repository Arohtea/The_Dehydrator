package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

/**
 * 分析文档数据访问接口。
 *
 * <p>普通查询用于读取列表和详情；带悲观写锁的查询用于删除、向量回写和分析启动
 * 之间的互斥，保证文档的 deleting 标志和 aiDocId 不会被旧异步操作覆盖。</p>
 */
public interface DocumentRepository extends JpaRepository<Document, String> {

    /**
     * 加悲观写锁查询文档，串行化删除、向量回写和分析启动。
     *
     * @param id 文档 ID
     * @return 加锁后的文档
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id")
    Optional<Document> findByIdForUpdate(@Param("id") String id);
}
