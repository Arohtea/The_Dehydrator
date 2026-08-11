package com.arohtea.business_service.model;

/** 分析服务未在规定时间内确认终止，资源不能被安全删除。 */
public class DocumentDeletionInProgressException extends RuntimeException {

    /**
     * 创建表示删除尚未安全完成的异常。
     *
     * @param message 面向调用方的重试说明
     */
    public DocumentDeletionInProgressException(String message) {
        super(message);
    }
}
