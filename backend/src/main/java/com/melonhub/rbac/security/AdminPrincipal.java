package com.melonhub.rbac.security;

import java.util.Set;

/** Authenticated console operator. Roles are platform-level and independent of the RBAC0 data being managed. */
public record AdminPrincipal(String username, String displayName, String role, Set<Long> applicationIds) {
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ADMIN = "ADMIN";
    public static final String APP_ADMIN = "APP_ADMIN";
    public static final String AUDITOR = "AUDITOR";
    public static final Set<String> ROLES = Set.of(SUPER_ADMIN, ADMIN, APP_ADMIN, AUDITOR);

    public boolean superAdmin() { return SUPER_ADMIN.equals(role); }
    public boolean platformWriter() { return superAdmin() || ADMIN.equals(role); }
    public boolean allApplications() { return !APP_ADMIN.equals(role); }
    public boolean canRead(long application) { return allApplications() || applicationIds.contains(application); }
    public boolean canWrite(long application) { return platformWriter() || APP_ADMIN.equals(role) && applicationIds.contains(application); }
    public String actor() { return username; }
}
