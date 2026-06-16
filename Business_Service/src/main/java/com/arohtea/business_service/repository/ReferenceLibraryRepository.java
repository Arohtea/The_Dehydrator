package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.ReferenceLibrary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReferenceLibraryRepository extends JpaRepository<ReferenceLibrary, String> {
    Optional<ReferenceLibrary> findBySystemKey(String systemKey);
}
