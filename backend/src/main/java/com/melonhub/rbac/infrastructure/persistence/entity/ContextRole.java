package com.melonhub.rbac.infrastructure.persistence.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** A role activated in a permission context. */
@Entity
@Table(name = "context_roles")
public class ContextRole implements Persistable<ContextRoleId> {
    @EmbeddedId private ContextRoleId id;
    @MapsId("contextId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "context_id") private PermissionContext context;
    @MapsId("roleId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "role_id") private Role role;
    @Transient private boolean isNew = true;

    public ContextRole() {}
    @Override public ContextRoleId getId() { return id; }
    @Override public boolean isNew() { return isNew; }
    @PostLoad @PostPersist void markNotNew() { this.isNew = false; }
    public ContextRole setId(ContextRoleId id) { this.id = id; return this; }
    public PermissionContext getContext() { return context; }
    public ContextRole setContext(PermissionContext context) { this.context = context; return this; }
    public Role getRole() { return role; }
    public ContextRole setRole(Role role) { this.role = role; return this; }
}
