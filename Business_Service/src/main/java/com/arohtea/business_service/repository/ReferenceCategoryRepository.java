package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.ReferenceCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReferenceCategoryRepository extends JpaRepository<ReferenceCategory, String> {
    List<ReferenceCategory> findByLibraryIdOrderByCreatedAtAsc(String libraryId);

    Optional<ReferenceCategory> findByLibraryIdAndName(String libraryId, String name);

    void deleteByLibraryId(String libraryId);
}
