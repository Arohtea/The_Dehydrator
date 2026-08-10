package com.arohtea.business_service.model;

/** 分析服务未在规定时间内确认终止，资源不能被安全删除。 */
public class DocumentDeletionInProgressException extends RuntimeException {

    public DocumentDeletionInProgressException(String message) {
        super(message);
    }
}
