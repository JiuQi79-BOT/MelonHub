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

/** An issued permission context; only the SHA-256 of its token is stored. */
@Entity
@Table(name = "permission_contexts")
public class PermissionContext implements Persistable<String> {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 64) private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private User user;
    @Column(name = "department_id") private Long departmentId;
    @Column(name = "application_id") private Long applicationId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "department_id", insertable = false, updatable = false) private Department department;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "application_id", insertable = false, updatable = false) private Application application;
    private long contextVersion;
    private Instant expiresAt;
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;
    @Transient private boolean isNew = true;

    public PermissionContext() {}
    @Override public String getId() { return id; }
    @Override public boolean isNew() { return isNew; }
    @PostLoad @PostPersist void markNotNew() { this.isNew = false; }
    public PermissionContext setId(String id) { this.id = id; return this; }
    public User getUser() { return user; }
    public PermissionContext setUser(User user) { this.user = user; return this; }
    public Long getDepartmentId() { return departmentId; }
    public PermissionContext setDepartmentId(Long departmentId) { this.departmentId = departmentId; return this; }
    public Long getApplicationId() { return applicationId; }
    public PermissionContext setApplicationId(Long applicationId) { this.applicationId = applicationId; return this; }
    public Department getDepartment() { return department; }
    public Application getApplication() { return application; }
    public long getContextVersion() { return contextVersion; }
    public PermissionContext setContextVersion(long contextVersion) { this.contextVersion = contextVersion; return this; }
    public Instant getExpiresAt() { return expiresAt; }
    public PermissionContext setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }
    public Instant getCreatedAt() { return createdAt; }
}
