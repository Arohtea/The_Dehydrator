package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.ReferenceDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 参考资料文档数据访问接口。
 */
public interface ReferenceDocumentRepository extends JpaRepository<ReferenceDocument, String> {
    /** @param libraryId 资料库 ID @return 按创建时间倒序的参考文档 */
    List<ReferenceDocument> findByLibraryIdOrderByCreatedAtDesc(String libraryId);

    /** @param sourceDocumentId 原始分析文档 ID @return 自动归档镜像 */
    List<ReferenceDocument> findBySourceDocumentId(String sourceDocumentId);

    /** @param sourceDocumentId 原始分析文档 ID @return 首个自动归档镜像 */
    Optional<ReferenceDocument> findFirstBySourceDocumentId(String sourceDocumentId);

    /** @param libraryId 资料库 ID @return 资料数量 */
    long countByLibraryId(String libraryId);

    /** @param folderId 文件夹 ID @return 引用该文件夹的资料数量 */
    long countByFolderId(String folderId);

    /** @param categoryId 分类 ID @return 引用该分类的资料数量 */
    long countByCategoryId(String categoryId);
}
