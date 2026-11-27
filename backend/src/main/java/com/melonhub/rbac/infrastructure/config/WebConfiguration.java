package com.melonhub.rbac.infrastructure.config;

import com.melonhub.rbac.infrastructure.security.AdminFunctionGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebMvcConfigurer {
    private final AdminFunctionGuard functions;
    private final String allowedOrigins;

    /**
     * 创建 Web 配置。
     *
     * @param functions 管理员功能拦截器
     * @param allowedOrigins 允许的跨域来源，逗号分隔
     */
    public WebConfiguration(AdminFunctionGuard functions,
                            @Value("${melonhub.cors.allowed-origins:*}") String allowedOrigins) {
        this.functions = functions;
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * 注册管理员功能拦截器。
     *
     * @param registry 拦截器注册表
     */
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(functions).addPathPatterns("/api/admin", "/api/admin/**");
    }

    /**
     * 注册课程系统前端所需的跨域策略。
     *
     * @param registry CORS 注册表
     */
    @Override public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.isBlank() ? new String[]{"*"} : allowedOrigins.split(",");
        registry.addMapping("/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Request-Id", "Retry-After")
                .maxAge(3600);
    }
}
