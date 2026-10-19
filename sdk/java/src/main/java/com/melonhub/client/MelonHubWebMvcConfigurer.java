package com.melonhub.client;

import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 将 {@link MelonHubPermissionInterceptor} 注册到全部路径（/**）。 */
public class MelonHubWebMvcConfigurer implements WebMvcConfigurer {

    private final MelonHubPermissionInterceptor interceptor;

    public MelonHubWebMvcConfigurer(MelonHubPermissionInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/**");
    }
}
