package com.waibao.pingdian.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waibao.pingdian.common.ApiResponse;
import com.waibao.pingdian.common.ErrorCode;
import com.waibao.pingdian.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 两条过滤链：
 * 1) /api/admin/** 仅 ADMIN audience 可访问（登录接口放行），未登录/失效统一 401；
 * 2) 其余接口：公开白名单放行，其他要求用户登录；未携带 Authorization 返回 403，携带但无效返回 401。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private String allowedOrigins;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http.securityMatcher("/api/admin/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/auth/login").permitAll()
                        .anyRequest().hasAuthority("ADMIN"))
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint(authenticationEntryPoint(true))
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain userFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/sms/send").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // 仅 GET 公开；/api/companies/report 等写接口仍需登录。
                        .requestMatchers(HttpMethod.GET, "/api/companies/**").permitAll()
                        // 企业回应凭投诉工单提交，公开表单不需要登录（F26）。
                        .requestMatchers(HttpMethod.POST, "/api/companies/*/responses").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/rankings/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/reviews/mine").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/reviews/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/appeals", "/api/feedback").permitAll()
                        .requestMatchers("/api/track/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint(authenticationEntryPoint(false))
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Trace-Id"));
        configuration.setExposedHeaders(List.of("X-Trace-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * 用户链：未携带 Authorization 视为游客，返回 403 并提示先登录（AC-01）；
     * 携带但无效/过期/已失效则返回 401。管理端链始终 401。
     */
    private AuthenticationEntryPoint authenticationEntryPoint(boolean adminChain) {
        return (request, response, ex) -> {
            boolean hasAuthorization = request.getHeader("Authorization") != null;
            if (adminChain || hasAuthorization) {
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, ApiResponse.fail(ErrorCode.UNAUTHORIZED));
            } else {
                writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                        ApiResponse.fail(ErrorCode.FORBIDDEN, "请先登录后再操作"));
            }
        };
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                ApiResponse.fail(ErrorCode.FORBIDDEN));
    }

    private void writeJson(HttpServletResponse response, int status, ApiResponse<?> body) {
        try {
            response.setStatus(status);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), body);
        } catch (Exception ignored) {
            // 响应已中断时不再抛出，避免二次异常。
        }
    }
}
