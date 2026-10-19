package com.melonhub.client.dto;

import java.util.Map;

/**
 * 权限校验结果。
 *
 * @param allowed        全部请求的权限都允许时为 true
 * @param decisions      每个权限编码的逐项判定
 * @param contextVersion 判定时的上下文版本
 */
public record CheckResult(boolean allowed, Map<String, Boolean> decisions, long contextVersion) {
}
