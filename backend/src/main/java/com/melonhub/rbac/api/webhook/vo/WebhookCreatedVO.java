package com.melonhub.rbac.api.webhook.vo;

/**
 * 创建 Webhook 一次性返回签名密钥响应 VO。
 */
public record WebhookCreatedVO(long id, String secret) {}
