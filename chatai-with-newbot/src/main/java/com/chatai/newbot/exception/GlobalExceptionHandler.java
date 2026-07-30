package com.chatai.newbot.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器：将各类异常统一转为 { success: false, message: ... } JSON 响应，
 * 避免控制器内层层 try-catch，也防止未捕获异常将堆栈信息泄露给客户端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常（含 ForbiddenException）：按异常自带的状态码与提示返回 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(fail(e.getMessage()));
    }

    /** 请求体缺失或 JSON 格式非法 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(fail("请求体格式错误"));
    }

    /** 缺少必填请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(fail("缺少参数: " + e.getParameterName()));
    }

    /** 请求参数类型不匹配（如数字参数传了字符串） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(fail("参数格式错误: " + e.getName()));
    }

    /** 请求方法不支持（如对 GET 接口发 POST） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405).body(fail("请求方法不支持"));
    }

    /** 上传文件超过大小限制 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUpload(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(fail("上传文件过大"));
    }

    /** 静态资源/接口路径不存在：保持 404 语义，避免被兜底处理为 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(fail("资源不存在"));
    }

    /** 兜底：未预期的服务器异常，记录完整堆栈但不外泄细节 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("未处理的服务器异常", e);
        return ResponseEntity.status(500).body(fail("服务器内部错误"));
    }

    /** 构造统一失败响应体 */
    private Map<String, Object> fail(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }
}
