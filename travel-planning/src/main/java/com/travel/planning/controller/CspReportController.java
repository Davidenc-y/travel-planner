package com.travel.planning.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * D-3a（E-19③）：CSP violation 公网上报 collector（Report-Only 观察期证据通道）。
 *
 * <p>语义：POST /api/v1/csp-report（Content-Type application/csp-report 或
 * application/reports+json），原文接收仅记 {@code [CspReport]} 日志行（INFO），不落库不回显；
 * 超长（&gt;64KB）直接丢弃记 WARN；响应恒 204。JWT 拦截器对该路径 exclude（公网匿名可达；
 * 限频为 /api/** 全局粒度保留，匿名限流面覆盖）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@RestController
public class CspReportController {

    /** 超长丢弃阈值（64KB） */
    static final int MAX_BODY_LENGTH = 64 * 1024;

    /**
     * CSP violation 上报 collector（浏览器 report-uri 报告；原文仅日志留痕，恒 204）。
     *
     * @param body 报告 JSON 原文（@RequestBody 直收，不反序列化）
     */
    @PostMapping(path = "/api/v1/csp-report",
            consumes = {"application/csp-report", "application/reports+json"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void collect(@RequestBody String body) {
        if (body == null || body.length() > MAX_BODY_LENGTH) {
            log.warn("[CspReport] 超长报告丢弃: len={}", body == null ? 0 : body.length());
            return;
        }
        log.info("[CspReport] len={} uri={}", body.length(), extractDocumentUri(body));
    }

    /** 从报告 JSON 提取 document-uri 首个字段值（截断 200 字符；缺失/畸形给占位不抛错） */
    private String extractDocumentUri(String body) {
        String key = "\"document-uri\"";
        int k = body.indexOf(key);
        if (k < 0) {
            return "<no-document-uri>";
        }
        int colon = body.indexOf(':', k + key.length());
        if (colon < 0) {
            return "<malformed>";
        }
        String rest = body.substring(colon + 1).trim();
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            String value = end > 0 ? rest.substring(1, end) : rest.substring(1);
            return truncate(value);
        }
        return truncate(rest);
    }

    private String truncate(String value) {
        return value.length() > 200 ? value.substring(0, 200) : value;
    }
}
