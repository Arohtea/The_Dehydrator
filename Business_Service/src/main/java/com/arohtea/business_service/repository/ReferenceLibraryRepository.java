package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.ReferenceLibrary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 参考资料库数据访问接口。
 */
public interface ReferenceLibraryRepository extends JpaRepository<ReferenceLibrary, String> {
    /** @param systemKey 系统资料库标识 @return 对应系统资料库 */
    Optional<ReferenceLibrary> findBySystemKey(String systemKey);
}
