package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.ReferenceDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReferenceDocumentRepository extends JpaRepository<ReferenceDocument, String> {
    List<ReferenceDocument> findByLibraryIdOrderByCreatedAtDesc(String libraryId);

    long countByLibraryId(String libraryId);
}
