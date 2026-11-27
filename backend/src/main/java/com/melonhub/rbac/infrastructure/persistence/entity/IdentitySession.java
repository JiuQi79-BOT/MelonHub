package com.melonhub.rbac.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;

/**
 * 统一身份 JWT 会话索引，用于退出登录后立即撤销令牌。
 */
@Entity
@Table(name = "identity_sessions")
public class IdentitySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 128)
    private String audience;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Generated
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public IdentitySession() {
    }

    /**
     * 创建令牌会话索引。
     *
     * @param tokenHash JWT 摘要
     * @param user 用户
     * @param audience 目标系统
     * @param expiresAt 过期时间
     */
    public IdentitySession(String tokenHash, User user, String audience, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.user = user;
        this.audience = audience;
        this.expiresAt = expiresAt;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public User getUser() {
        return user;
    }

    public String getAudience() {
        return audience;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public IdentitySession setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
        return this;
    }
}
