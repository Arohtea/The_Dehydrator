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

/**
 * 参考资料库、目录和参考文档的 HTTP 接口。
 *
 * <p>控制器统一把目录冲突映射为 409，把输入校验错误映射为 400，并在上传入口
 * 使用共享限流器保护对象存储和向量化资源。上传只返回元数据，向量化状态由
 * {@link ReferenceLibraryService} 异步回写。</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReferenceLibraryController {

    private final ReferenceLibraryService referenceLibraryService;
    private final RequestRateLimiter requestRateLimiter;

    /**
     * 创建参考资料库。
     *
     * @param body 包含 name 的请求体
     * @return 新资料库或参数错误响应
     */
    @PostMapping("/reference-libraries")
    public ResponseEntity<?> createLibrary(@RequestBody Map<String, String> body) {
        try {
            // 服务层负责名称清洗和数据库保存，控制器只把结果包装为 HTTP 响应。
            ReferenceLibrary library = referenceLibraryService.createLibrary(body.get("name"));
            return ResponseEntity.ok(library);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列出全部参考资料库。
     *
     * @return 资料库列表
     */
    @GetMapping("/reference-libraries")
    public ResponseEntity<List<ReferenceLibrary>> listLibraries() {
        return ResponseEntity.ok(referenceLibraryService.listLibraries());
    }

    /**
     * 删除空的普通资料库。
     *
     * @param id 资料库 ID
     * @return 删除结果；系统库或非空资料库返回 409
     */
    @DeleteMapping("/reference-libraries/{id}")
    public ResponseEntity<?> deleteLibrary(@PathVariable("id") String id) {
        try {
            referenceLibraryService.deleteLibrary(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列出指定资料库中的参考文档。
     *
     * @param id 资料库 ID
     * @return 文档列表；资料库不存在时返回 404
     */
    @GetMapping("/reference-libraries/{id}/documents")
    public ResponseEntity<?> listDocuments(@PathVariable("id") String id) {
        // 先明确区分“资料库不存在”和“资料库为空”，避免返回一个看似成功的空列表。
        if (referenceLibraryService.getLibrary(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(referenceLibraryService.listDocuments(id));
    }

    /**
     * 列出指定资料库中的文件夹。
     *
     * @param id 资料库 ID
     * @return 文件夹列表；资料库不存在时返回 404
     */
    @GetMapping("/reference-libraries/{id}/folders")
    public ResponseEntity<?> listFolders(@PathVariable("id") String id) {
        if (referenceLibraryService.getLibrary(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(referenceLibraryService.listFolders(id));
    }

    /**
     * 创建资料库文件夹。
     *
     * @param id 资料库 ID
     * @param body 包含 name 的请求体
     * @return 文件夹或参数错误响应
     */
    @PostMapping("/reference-libraries/{id}/folders")
    public ResponseEntity<?> createFolder(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        try {
            ReferenceFolder folder = referenceLibraryService.createFolder(id, body.get("name"));
            return ResponseEntity.ok(folder);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 重命名文件夹。
     *
     * @param id 文件夹 ID
     * @param body 包含新 name 的请求体
     * @return 更新后的文件夹、404 或重名错误响应
     */
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

    /**
     * 删除未被文档引用的文件夹。
     *
     * @param id 文件夹 ID
     * @return 删除结果；仍被引用时返回 409
     */
    @DeleteMapping("/reference-folders/{id}")
    public ResponseEntity<?> deleteFolder(@PathVariable("id") String id) {
        try {
            referenceLibraryService.deleteFolder(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列出指定资料库中的分类。
     *
     * @param id 资料库 ID
     * @return 分类列表；资料库不存在时返回 404
     */
    @GetMapping("/reference-libraries/{id}/categories")
    public ResponseEntity<?> listCategories(@PathVariable("id") String id) {
        if (referenceLibraryService.getLibrary(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(referenceLibraryService.listCategories(id));
    }

    /**
     * 创建资料库分类。
     *
     * @param id 资料库 ID
     * @param body 包含 name 的请求体
     * @return 分类或参数错误响应
     */
    @PostMapping("/reference-libraries/{id}/categories")
    public ResponseEntity<?> createCategory(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        try {
            ReferenceCategory category = referenceLibraryService.createCategory(id, body.get("name"));
            return ResponseEntity.ok(category);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 重命名分类。
     *
     * @param id 分类 ID
     * @param body 包含新 name 的请求体
     * @return 更新后的分类、404 或重名错误响应
     */
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

    /**
     * 删除未被文档引用的分类。
     *
     * @param id 分类 ID
     * @return 删除结果；仍被引用时返回 409
     */
    @DeleteMapping("/reference-categories/{id}")
    public ResponseEntity<?> deleteCategory(@PathVariable("id") String id) {
        try {
            referenceLibraryService.deleteCategory(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 上传参考文档并启动异步向量化。
     *
     * @param id 资料库 ID
     * @param file 上传文件
     * @return 已保存的参考文档元数据；限流或参数错误时返回对应响应
     * @throws Exception 对象存储或元数据保存失败
     */
    @PostMapping("/reference-libraries/{id}/documents/upload")
    public ResponseEntity<?> uploadDocument(
            @PathVariable("id") String id,
            @RequestParam("file") MultipartFile file) throws Exception {
        // 上传前限流，拒绝请求时不会读取文件或写入 MinIO。
        if (!requestRateLimiter.allowUpload()) {
            return ResponseEntity.status(429).body(Map.of("error", "上传请求过于频繁"));
        }
        try {
            // 同步返回未完成向量化的元数据；前端后续根据 aiDocId/状态刷新。
            ReferenceDocument document = referenceLibraryService.uploadDocument(id, file);
            return ResponseEntity.ok(document);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 更新参考文档展示名称、文件夹和分类。
     *
     * @param id 参考文档 ID
     * @param body 包含 displayName、folderId 和 categoryId 的请求体
     * @return 更新后的文档、404 或目录校验错误响应
     */
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

    /**
     * 删除参考文档及其向量和对象；自动归档镜像必须从原始文档入口删除。
     *
     * @param id 参考文档 ID
     * @return 删除成功时返回 204，镜像删除冲突时返回 409
     * @throws Exception 外部资源删除失败
     */
    @DeleteMapping("/reference-documents/{id}")
    public ResponseEntity<?> deleteDocument(@PathVariable("id") String id) throws Exception {
        try {
            // 删除是否为自动归档镜像由服务层判断，控制器不直接删除共享对象。
            referenceLibraryService.deleteDocument(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(409).body(Map.of("error", exception.getMessage()));
        }
    }
}
