package com.melonhub.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 上下文视图（issue/effective 返回）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContextView {

    private String contextToken;
    private Long userId;
    private Long departmentId;
    private Long applicationId;
    private Long contextVersion;
    private String expiresAt;
    private List<RoleInfo> roles;
    private List<String> permissions;

    public String getContextToken() { return contextToken; }
    public void setContextToken(String contextToken) { this.contextToken = contextToken; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long applicationId) { this.applicationId = applicationId; }
    public Long getContextVersion() { return contextVersion; }
    public void setContextVersion(Long contextVersion) { this.contextVersion = contextVersion; }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }
    public List<RoleInfo> getRoles() { return roles; }
    public void setRoles(List<RoleInfo> roles) { this.roles = roles; }
    public List<String> getPermissions() { return permissions; }
    public void setPermissions(List<String> permissions) { this.permissions = permissions; }
}
