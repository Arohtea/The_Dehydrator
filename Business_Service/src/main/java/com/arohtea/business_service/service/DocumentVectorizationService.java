package com.arohtea.business_service.service;

import com.arohtea.business_service.model.Document;
import com.arohtea.business_service.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 使用文档行锁提交异步向量化结果，避免删除流程中的旧回写复活文档。 */
@Service
@RequiredArgsConstructor
public class DocumentVectorizationService {

    private final DocumentRepository documentRepository;

    /**
     * 在删除状态检查通过后回写 AI 文档 ID。
     *
     * @param documentId 业务文档 ID
     * @param aiDocId AI Service 文档 ID
     * @return 已更新文档；文档不存在或已进入删除流程时返回 null
     */
    @Transactional
    public Document complete(String documentId, String aiDocId) {
        // 删除流程中的旧异步回调必须被忽略，不能把已经标记删除的文档重新变成可分析。
        Document current = documentRepository.findByIdForUpdate(documentId).orElse(null);
        if (current == null || current.isDeleting()) {
            return null;
        }
        current.setAiDocId(aiDocId);
        return documentRepository.save(current);
    }
}
