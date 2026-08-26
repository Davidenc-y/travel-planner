package com.travel.webflux.web;

import com.travel.common.exception.BusinessException;
import com.travel.common.result.R;
import com.travel.planning.stream.StreamErrorMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * M6-30：WebFlux 全局异常映射（对应 MVC GlobalExceptionHandler 语义）。
 *
 * <p>BusinessException → StreamErrorMapper 映射 HTTP 状态 + R.fail 体；
 * ResponseStatusException（如流式端点未启用 404）→ 原状态；
 * 其余 → 500 + 通用文案。</p>
 */
@Slf4j
@RestControllerAdvice
public class ReactiveGlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusiness(BusinessException e) {
        int status = StreamErrorMapper.httpStatus(e.getCode());
        return ResponseEntity.status(status).body(R.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<R<Void>> handleStatus(ResponseStatusException e) {
        int status = e.getStatusCode().value();
        String reason = e.getReason() == null ? "请求失败" : e.getReason();
        return ResponseEntity.status(status).body(R.fail(status, reason));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception e) {
        log.error("[WebFlux] 未处理异常", e);
        return ResponseEntity.status(500).body(R.fail(50000, "服务繁忙，请稍后重试"));
    }
}
