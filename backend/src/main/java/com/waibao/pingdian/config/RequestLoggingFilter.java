package com.waibao.pingdian.config;

import com.waibao.pingdian.common.SensitiveDataMasker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求日志脱敏骨架：只记录方法、路径、脱敏后的查询串与状态码，永不记录请求体。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            String query = request.getQueryString();
            String queryPart = query == null ? "" : "?" + SensitiveDataMasker.mask(query);
            log.info("{} {}{} -> {} ({} ms)",
                    request.getMethod(), request.getRequestURI(), queryPart,
                    response.getStatus(), System.currentTimeMillis() - start);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/api/health".equals(request.getRequestURI());
    }
}
