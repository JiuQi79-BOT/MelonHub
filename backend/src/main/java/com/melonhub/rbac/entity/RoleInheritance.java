package com.melonhub.rbac.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/** A role inherits the permissions of each listed parent role in the same domain. */
@Entity
@Table(name = "role_inheritances")
public class RoleInheritance {
    @EmbeddedId private RoleInheritanceId id;
    @MapsId("childRoleId") @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "child_role_id") private Role childRole;
    @MapsId("parentRoleId") @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_role_id") private Role parentRole;
    @Column(name = "department_id", nullable = false) private Long departmentId;
    @Column(name = "application_id", nullable = false) private Long applicationId;

    public RoleInheritance() {}
    public RoleInheritanceId getId() { return id; }
    public RoleInheritance setId(RoleInheritanceId id) { this.id = id; return this; }
    public Role getChildRole() { return childRole; }
    public RoleInheritance setChildRole(Role childRole) { this.childRole = childRole; return this; }
    public Role getParentRole() { return parentRole; }
    public RoleInheritance setParentRole(Role parentRole) { this.parentRole = parentRole; return this; }
    public RoleInheritance setDepartmentId(Long departmentId) { this.departmentId = departmentId; return this; }
    public RoleInheritance setApplicationId(Long applicationId) { this.applicationId = applicationId; return this; }
}
