package com.arohtea.business_service.model;

/** 文档已有活动分析任务或正在删除时的可识别业务冲突。 */
public class AnalysisConflictException extends RuntimeException {

    /**
     * 创建可映射为 HTTP 409 的分析冲突异常。
     *
     * @param message 面向调用方的冲突说明
     */
    public AnalysisConflictException(String message) {
        super(message);
    }
}
