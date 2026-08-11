package com.arohtea.business_service.config;

import com.arohtea.business_service.model.AnalysisConflictException;
import com.arohtea.business_service.model.DocumentDeletionInProgressException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * 将常见参数、上传和业务冲突异常统一转换为前端约定的 HTTP 错误结构。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 将 Bean Validation 错误统一映射为 422。
     *
     * @param exception 参数校验异常
     * @return 不包含内部异常细节的错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " 参数无效")
                .orElse("请求参数无效");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", message));
    }

    /**
     * 将 Spring multipart 大小限制错误映射为 413。
     *
     * @return 上传过大的错误响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleUploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "文件或请求体超过上传限制"));
    }

    /**
     * 将业务配置错误统一映射为 422，避免上传接口返回无上下文的 500。
     *
     * @param exception 配置或业务参数异常
     * @return 面向用户的错误消息
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", exception.getMessage() == null ? "请求参数无效" : exception.getMessage()));
    }

    /**
     * 将分析任务并发冲突映射为 409。
     *
     * @param exception 分析冲突异常
     * @return 冲突错误响应
     */
    @ExceptionHandler(AnalysisConflictException.class)
    public ResponseEntity<Map<String, String>> handleAnalysisConflict(AnalysisConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", exception.getMessage()));
    }

    /**
     * 将文档删除等待超时映射为 409，提示调用方稍后重试。
     *
     * @param exception 删除流程超时异常
     * @return 删除尚未完成的错误响应
     */
    @ExceptionHandler(DocumentDeletionInProgressException.class)
    public ResponseEntity<Map<String, String>> handleDeletionTimeout(DocumentDeletionInProgressException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", exception.getMessage()));
    }
}
