package com.arohtea.business_service.model;

/** 文档已有活动分析任务或正在删除时的可识别业务冲突。 */
public class AnalysisConflictException extends RuntimeException {

    public AnalysisConflictException(String message) {
        super(message);
    }
}
