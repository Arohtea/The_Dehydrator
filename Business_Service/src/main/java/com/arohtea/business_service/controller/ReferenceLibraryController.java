package com.arohtea.business_service.controller;

import com.arohtea.business_service.model.ReferenceDocument;
import com.arohtea.business_service.model.ReferenceLibrary;
import com.arohtea.business_service.service.ReferenceLibraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ReferenceLibraryController {

    private final ReferenceLibraryService referenceLibraryService;

    @PostMapping("/reference-libraries")
    public ResponseEntity<?> createLibrary(@RequestBody Map<String, String> body) {
        try {
            ReferenceLibrary library = referenceLibraryService.createLibrary(body.get("name"));
            return ResponseEntity.ok(library);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/reference-libraries")
    public ResponseEntity<List<ReferenceLibrary>> listLibraries() {
        return ResponseEntity.ok(referenceLibraryService.listLibraries());
    }

    @DeleteMapping("/reference-libraries/{id}")
    public ResponseEntity<?> deleteLibrary(@PathVariable("id") String id) {
        try {
            referenceLibraryService.deleteLibrary(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/reference-libraries/{id}/documents")
    public ResponseEntity<?> listDocuments(@PathVariable("id") String id) {
        if (referenceLibraryService.getLibrary(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(referenceLibraryService.listDocuments(id));
    }

    @PostMapping("/reference-libraries/{id}/documents/upload")
    public ResponseEntity<?> uploadDocument(
            @PathVariable("id") String id,
            @RequestParam("file") MultipartFile file) throws Exception {
        try {
            ReferenceDocument document = referenceLibraryService.uploadDocument(id, file);
            return ResponseEntity.ok(document);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/reference-documents/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable("id") String id) throws Exception {
        referenceLibraryService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }
}
