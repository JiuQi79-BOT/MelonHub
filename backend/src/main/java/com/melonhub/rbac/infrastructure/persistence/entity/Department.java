package com.melonhub.rbac.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;

/** A department: one half of an isolation domain (department x application), with optional parent hierarchy. */
@Entity
@Table(name = "departments")
public class Department {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    private String code;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Department parent;
    /** Set by the database default; read back after insert. */
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;

    public Department() {}

    public Long getId() { return id; }
    public String getName() { return name; }
    public Department setName(String name) { this.name = name; return this; }
    public String getCode() { return code; }
    public Department setCode(String code) { this.code = code; return this; }
    public Department getParent() { return parent; }
    public Department setParent(Department parent) { this.parent = parent; return this; }
    public Instant getCreatedAt() { return createdAt; }
}
