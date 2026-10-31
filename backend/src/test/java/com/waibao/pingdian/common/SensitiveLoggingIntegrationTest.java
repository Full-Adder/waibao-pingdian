package com.waibao.pingdian.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.waibao.pingdian.AbstractIntegrationTest;
import com.waibao.pingdian.config.RequestLoggingFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SensitiveLoggingIntegrationTest extends AbstractIntegrationTest {

    @Test
    void requestLogMasksSensitiveQueryParams() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mockMvc.perform(get("/api/companies/search")
                            .queryParam("keyword", "测试")
                            .queryParam("phone", "13800138000")
                            .queryParam("idNumber", "110101199001010015"))
                    .andExpect(status().isOk());

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertThat(logs)
                    .doesNotContain("13800138000")
                    .doesNotContain("110101199001010015")
                    .contains("138****8000")
                    .contains("110101********0015");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void validationErrorResponseHasNoPlaintextOrStacktrace() throws Exception {
        String token = registerAndLogin(uniquePhone(), uniqueIp()).path("token").asText();
        MvcResult result = mockMvc.perform(post("/api/users/me/realname")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "张三", "idNumber", "123456"))))
                .andExpect(status().isBadRequest())
                .andReturn();
        JsonNode body = body(result);
        String text = result.getResponse().getContentAsString();
        assertThat(body.path("message").asText()).doesNotContain("123456");
        assertThat(text)
                .doesNotContain("123456")
                .doesNotContain("Exception")
                .doesNotContain("at com.waibao");
    }
}
