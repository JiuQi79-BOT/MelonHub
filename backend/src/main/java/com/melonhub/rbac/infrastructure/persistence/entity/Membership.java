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

/** A user's membership of a department. */
@Entity
@Table(name = "memberships")
public class Membership implements Persistable<MembershipId> {
    @EmbeddedId private MembershipId id;
    @MapsId("userId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private User user;
    @MapsId("departmentId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "department_id") private Department department;
    @Transient private boolean isNew = true;

    public Membership() {}
    @Override public MembershipId getId() { return id; }
    @Override public boolean isNew() { return isNew; }
    @PostLoad @PostPersist void markNotNew() { this.isNew = false; }
    public Membership setId(MembershipId id) { this.id = id; return this; }
    public User getUser() { return user; }
    public Membership setUser(User user) { this.user = user; return this; }
    public Department getDepartment() { return department; }
    public Membership setDepartment(Department department) { this.department = department; return this; }
}
