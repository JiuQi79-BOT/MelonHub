package com.melonhub.rbac.api.shared.vo;

/**
 * 通用成功确认响应 VO。
 */
public record OkVO(boolean ok) {
    public static final OkVO OK = new OkVO(true);
}
