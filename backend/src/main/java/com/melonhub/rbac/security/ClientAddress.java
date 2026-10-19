package com.melonhub.rbac.security;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Caller address for audit records; Tomcat's RemoteIpValve already resolved trusted proxy headers. */
public final class ClientAddress {
    private ClientAddress() {}
    public static String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String address = attributes.getRequest().getRemoteAddr();
            return address == null ? "" : address.length() > 64 ? address.substring(0, 64) : address;
        }
        return "";
    }
}
