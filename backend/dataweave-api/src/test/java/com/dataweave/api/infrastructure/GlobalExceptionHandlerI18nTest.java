package com.dataweave.api.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.dataweave.master.i18n.BizException;
import com.dataweave.master.i18n.Messages;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

/**
 * 验证 GlobalExceptionHandler 的 BizException 按 Accept-Language 本地化、回传 errorCode、插值参数。
 * 对应 internationalization spec「异常错误码本地化体系」「后端 locale 协商」requirement。
 */
class GlobalExceptionHandlerI18nTest {

    private GlobalExceptionHandler handler() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return new GlobalExceptionHandler(new Messages(ms));
    }

    private MockServerWebExchange exchange(String acceptLanguage) {
        HttpHeaders h = new HttpHeaders();
        if (acceptLanguage != null) {
            h.add("Accept-Language", acceptLanguage);
        }
        return MockServerWebExchange.from(MockServerHttpRequest.post("/").headers(h).build());
    }

    @Test
    void shouldLocalizeBizExceptionToChinese() {
        ApiResponse<Void> resp = handler().handleBiz(new BizException("workflow.not_online"), exchange("zh-CN")).getBody();
        assertThat(resp.message()).isEqualTo("工作流当前未上线");
        assertThat(resp.errorCode()).isEqualTo("workflow.not_online");
        assertThat(resp.code()).isEqualTo(400);
    }

    @Test
    void shouldLocalizeBizExceptionToEnglish() {
        ApiResponse<Void> resp = handler().handleBiz(new BizException("workflow.not_online"), exchange("en-US")).getBody();
        assertThat(resp.message()).isEqualTo("Workflow is not online");
    }

    @Test
    void shouldFallbackToChineseWhenNoAcceptLanguage() {
        ApiResponse<Void> resp = handler().handleBiz(new BizException("workflow.not_online"), exchange(null)).getBody();
        assertThat(resp.message()).isEqualTo("工作流当前未上线");
    }

    @Test
    void shouldInterpolateBizExceptionArgs() {
        ApiResponse<Void> resp = handler().handleBiz(new BizException("approval.not_found", "#42"), exchange("en-US")).getBody();
        assertThat(resp.message()).isEqualTo("Approval #42 not found");
    }

    @Test
    void shouldRespectCustomHttpStatus() {
        ApiResponse<Void> resp = handler().handleBiz(new BizException("approval.not_found", "#42").withHttpStatus(404), exchange("zh-CN")).getBody();
        assertThat(resp.code()).isEqualTo(404);
    }

    @Test
    void shouldLocalizeUnexpectedException() {
        ApiResponse<Void> resp = handler().handleUnexpected(new RuntimeException("boom"), exchange("en-US")).getBody();
        assertThat(resp.message()).isEqualTo("Internal server error");
        assertThat(resp.code()).isEqualTo(500);
    }

    @Test
    void shouldLocalizeResponseStatusFallbackReason() {
        // 无 reason 的 ResponseStatusException → 用本地化「请求处理失败」
        ApiResponse<Void> resp = handler()
                .handleResponseStatus(new ResponseStatusException(HttpStatusCode.valueOf(500)), exchange("en-US"))
                .getBody();
        assertThat(resp.message()).isEqualTo("Request failed");
    }
}
