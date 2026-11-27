package com.melonhub.rbac.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.springframework.data.domain.Persistable;

/**
 * 统一身份登录凭据，密码只保存为 BCrypt 摘要。
 */
@Entity
@Table(name = "identity_credentials")
public class IdentityCredential implements Persistable<Long> {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @Column(nullable = false, unique = true, length = 128)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled;

    @Generated
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    public IdentityCredential() {
    }

    /**
     * 创建统一身份凭据。
     *
     * @param userId 用户主键
     * @param username 登录名
     * @param passwordHash BCrypt 密码摘要
     * @param enabled 是否启用
     */
    public IdentityCredential(Long userId, String username, String passwordHash, boolean enabled) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
    }

    @Override
    public Long getId() {
        return userId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public IdentityCredential setUsername(String username) {
        this.username = username;
        return this;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public IdentityCredential setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public IdentityCredential setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }
}
