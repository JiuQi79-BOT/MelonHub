package com.melonhub.rbac.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** Console session; only the SHA-256 of the bearer token is stored. */
@Entity
@Table(name = "admin_sessions")
public class AdminSession implements Persistable<String> {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 64) private String tokenHash;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "account_id") private AdminAccount account;
    private Instant expiresAt;
    /** Set by the database default; read back after insert. */
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;
    private Instant lastSeenAt;
    private String sourceIp;
    /** The account's credential version when the session was created; any credential change makes it stale. */
    private long credentialVersion;
    @Transient private boolean isNew = true;

    protected AdminSession() {}
    public AdminSession(String tokenHash, AdminAccount account, Instant expiresAt, Instant lastSeenAt, String sourceIp, long credentialVersion) {
        this.tokenHash = tokenHash;
        this.account = account;
        this.expiresAt = expiresAt;
        this.lastSeenAt = lastSeenAt;
        this.sourceIp = sourceIp;
        this.credentialVersion = credentialVersion;
    }

    @Override public String getId() { return tokenHash; }
    @Override public boolean isNew() { return isNew; }
    @PostLoad @PostPersist void markNotNew() { this.isNew = false; }
}
