package com.melonhub.rbac.entity;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class RoleInheritanceId implements Serializable {
    private Long childRoleId;
    private Long parentRoleId;

    protected RoleInheritanceId() {}
    public RoleInheritanceId(Long childRoleId, Long parentRoleId) {
        this.childRoleId = childRoleId;
        this.parentRoleId = parentRoleId;
    }
    public Long getChildRoleId() { return childRoleId; }
    public Long getParentRoleId() { return parentRoleId; }
    @Override public boolean equals(Object other) {
        return other instanceof RoleInheritanceId id
                && Objects.equals(childRoleId, id.childRoleId)
                && Objects.equals(parentRoleId, id.parentRoleId);
    }
    @Override public int hashCode() { return Objects.hash(childRoleId, parentRoleId); }
}
