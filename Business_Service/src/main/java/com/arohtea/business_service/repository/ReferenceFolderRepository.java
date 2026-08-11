package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.ReferenceFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 参考资料文件夹数据访问接口。
 */
public interface ReferenceFolderRepository extends JpaRepository<ReferenceFolder, String> {
    /** @param libraryId 资料库 ID @return 按创建时间升序的文件夹 */
    List<ReferenceFolder> findByLibraryIdOrderByCreatedAtAsc(String libraryId);

    /** @param libraryId 资料库 ID @param name 文件夹名称 @return 同库同名文件夹 */
    Optional<ReferenceFolder> findByLibraryIdAndName(String libraryId, String name);

    /** @param libraryId 资料库 ID */
    void deleteByLibraryId(String libraryId);
}
