package com.arohtea.business_service.service;

import com.arohtea.business_service.model.Document;
import com.arohtea.business_service.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用文档行锁提交异步向量化结果，避免删除流程中的旧回写复活文档。
 *
 * <p>向量化在后台线程执行，完成时间可能晚于用户删除文档的时间。这个小服务把
 * 回写集中在事务和行锁中，并把“文档已不存在/正在删除”转换成调用方可识别的空结果，
 * 让上层负责回收已经在 AI Service 创建的向量。</p>
 */
@Service
@RequiredArgsConstructor
public class DocumentVectorizationService {

    private final DocumentRepository documentRepository;

    /**
     * 在删除状态检查通过后回写 AI 文档 ID。
     *
     * <p>只有文档仍存在且未标记删除，才把 AI Service 返回的 ID 写回数据库。返回
     * {@code null} 并不代表向量化失败，而是表示远程工作已经落后于删除流程；调用方
     * 必须把这时刚创建的 AI 文档清理掉。</p>
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
        // 行锁保护下写入向量 ID；从这一刻开始，分析任务才知道文档已经具备检索能力。
        current.setAiDocId(aiDocId);
        return documentRepository.save(current);
    }
}
