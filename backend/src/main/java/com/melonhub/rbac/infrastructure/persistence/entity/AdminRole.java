package com.melonhub.rbac.infrastructure.persistence.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.hibernate.annotations.Generated;
import org.springframework.data.domain.Persistable;

/** An administrator role: a named set of console functions. Built-in roles are created by V6 and cannot be changed. */
@Entity
@Table(name = "admin_roles")
public class AdminRole implements Persistable<String> {
    @Id private String code;
    private String name;
    private String description;
    private boolean builtin;
    /** Set by the database default; read back after insert. */
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;
    /** Codes of the console functions (AdminFunction) this role grants. */
    @ElementCollection
    @CollectionTable(name = "admin_role_functions", joinColumns = @JoinColumn(name = "role_code"))
    @Column(name = "function_code")
    private Set<String> functions = new HashSet<>();
    @Transient private boolean isNew = true;

    protected AdminRole() {}
    public AdminRole(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }

    @Override public String getId() { return code; }
    @Override public boolean isNew() { return isNew; }
    @PostLoad @PostPersist void markNotNew() { this.isNew = false; }

    public String getCode() { return code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isBuiltin() { return builtin; }
    public Instant getCreatedAt() { return createdAt; }
    public Set<String> getFunctions() { return functions; }
}
