package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.ReferenceLibrary;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferenceLibraryRepository extends JpaRepository<ReferenceLibrary, String> {
}
