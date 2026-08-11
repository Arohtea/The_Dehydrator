package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.ReferenceDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 参考资料文档数据访问接口。
 */
public interface ReferenceDocumentRepository extends JpaRepository<ReferenceDocument, String> {
    /**
     * 查询资料库页面展示的参考文档。
     *
     * @param libraryId 资料库 ID
     * @return 按创建时间倒序的参考文档
     */
    List<ReferenceDocument> findByLibraryIdOrderByCreatedAtDesc(String libraryId);

    /**
     * 查询原始分析文档对应的全部自动归档镜像。
     *
     * @param sourceDocumentId 原始分析文档 ID
     * @return 自动归档镜像
     */
    List<ReferenceDocument> findBySourceDocumentId(String sourceDocumentId);

    /**
     * 获取原始文档的首个镜像，用于幂等创建和向量化完成后的回写。
     *
     * @param sourceDocumentId 原始分析文档 ID
     * @return 首个自动归档镜像
     */
    Optional<ReferenceDocument> findFirstBySourceDocumentId(String sourceDocumentId);

    /** @param libraryId 资料库 ID @return 资料数量 */
    long countByLibraryId(String libraryId);

    /** @param folderId 文件夹 ID @return 引用该文件夹的资料数量 */
    long countByFolderId(String folderId);

    /** @param categoryId 分类 ID @return 引用该分类的资料数量 */
    long countByCategoryId(String categoryId);
}
