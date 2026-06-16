package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.ReferenceFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReferenceFolderRepository extends JpaRepository<ReferenceFolder, String> {
    List<ReferenceFolder> findByLibraryIdOrderByCreatedAtAsc(String libraryId);

    Optional<ReferenceFolder> findByLibraryIdAndName(String libraryId, String name);

    void deleteByLibraryId(String libraryId);
}
