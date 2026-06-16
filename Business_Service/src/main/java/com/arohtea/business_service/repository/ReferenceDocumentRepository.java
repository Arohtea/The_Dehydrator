package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.ReferenceDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReferenceDocumentRepository extends JpaRepository<ReferenceDocument, String> {
    List<ReferenceDocument> findByLibraryIdOrderByCreatedAtDesc(String libraryId);

    List<ReferenceDocument> findBySourceDocumentId(String sourceDocumentId);

    Optional<ReferenceDocument> findFirstBySourceDocumentId(String sourceDocumentId);

    long countByLibraryId(String libraryId);

    long countByFolderId(String folderId);

    long countByCategoryId(String categoryId);
}
