package com.melonhub.rbac.infrastructure.security;

import com.melonhub.rbac.service.port.security.AdminFunction;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明控制台接口所需的管理功能项，由 {@link AdminFunctionGuard} 在路由处理前强制拦截。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresFunction {
    AdminFunction[] value();
}
