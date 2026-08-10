package com.arohtea.business_service.controller;

import com.arohtea.business_service.model.ReferenceCategory;
import com.arohtea.business_service.model.ReferenceDocument;
import com.arohtea.business_service.model.ReferenceFolder;
import com.arohtea.business_service.model.ReferenceLibrary;
import com.arohtea.business_service.service.ReferenceLibraryService;
import com.arohtea.business_service.service.RequestRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
public class ReferenceLibraryController {

    private final ReferenceLibraryService referenceLibraryService;
    private final RequestRateLimiter requestRateLimiter;

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

    @GetMapping("/reference-libraries/{id}/folders")
    public ResponseEntity<?> listFolders(@PathVariable("id") String id) {
        if (referenceLibraryService.getLibrary(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(referenceLibraryService.listFolders(id));
    }

    @PostMapping("/reference-libraries/{id}/folders")
    public ResponseEntity<?> createFolder(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        try {
            ReferenceFolder folder = referenceLibraryService.createFolder(id, body.get("name"));
            return ResponseEntity.ok(folder);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/reference-folders/{id}")
    public ResponseEntity<?> renameFolder(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        try {
            ReferenceFolder folder = referenceLibraryService.renameFolder(id, body.get("name"));
            if (folder == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(folder);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/reference-folders/{id}")
    public ResponseEntity<?> deleteFolder(@PathVariable("id") String id) {
        try {
            referenceLibraryService.deleteFolder(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/reference-libraries/{id}/categories")
    public ResponseEntity<?> listCategories(@PathVariable("id") String id) {
        if (referenceLibraryService.getLibrary(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(referenceLibraryService.listCategories(id));
    }

    @PostMapping("/reference-libraries/{id}/categories")
    public ResponseEntity<?> createCategory(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        try {
            ReferenceCategory category = referenceLibraryService.createCategory(id, body.get("name"));
            return ResponseEntity.ok(category);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/reference-categories/{id}")
    public ResponseEntity<?> renameCategory(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        try {
            ReferenceCategory category = referenceLibraryService.renameCategory(id, body.get("name"));
            if (category == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(category);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/reference-categories/{id}")
    public ResponseEntity<?> deleteCategory(@PathVariable("id") String id) {
        try {
            referenceLibraryService.deleteCategory(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/reference-libraries/{id}/documents/upload")
    public ResponseEntity<?> uploadDocument(
            @PathVariable("id") String id,
            @RequestParam("file") MultipartFile file) throws Exception {
        if (!requestRateLimiter.allowUpload()) {
            return ResponseEntity.status(429).body(Map.of("error", "上传请求过于频繁"));
        }
        try {
            ReferenceDocument document = referenceLibraryService.uploadDocument(id, file);
            return ResponseEntity.ok(document);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/reference-documents/{id}")
    public ResponseEntity<?> updateDocument(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        try {
            ReferenceDocument document = referenceLibraryService.updateDocument(
                    id,
                    body.get("displayName"),
                    body.get("folderId"),
                    body.get("categoryId")
            );
            if (document == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(document);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/reference-documents/{id}")
    public ResponseEntity<?> deleteDocument(@PathVariable("id") String id) throws Exception {
        try {
            referenceLibraryService.deleteDocument(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(409).body(Map.of("error", exception.getMessage()));
        }
    }
}
