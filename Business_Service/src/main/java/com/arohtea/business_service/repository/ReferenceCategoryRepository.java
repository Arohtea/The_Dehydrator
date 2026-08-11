package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.ReferenceCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 参考资料分类数据访问接口。
 */
public interface ReferenceCategoryRepository extends JpaRepository<ReferenceCategory, String> {
    /** @param libraryId 资料库 ID @return 按创建时间升序的分类 */
    List<ReferenceCategory> findByLibraryIdOrderByCreatedAtAsc(String libraryId);

    /** @param libraryId 资料库 ID @param name 分类名称 @return 同库同名分类 */
    Optional<ReferenceCategory> findByLibraryIdAndName(String libraryId, String name);

    /** @param libraryId 资料库 ID */
    void deleteByLibraryId(String libraryId);
}
