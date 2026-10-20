package com.melonhub.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 用户在当前软件的授权视图（只读，不创建上下文）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserAccessView {

    private Long userId;
    private Long departmentId;
    private Long applicationId;
    private List<RoleInfo> roles;
    private List<String> permissions;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long applicationId) { this.applicationId = applicationId; }
    public List<RoleInfo> getRoles() { return roles; }
    public void setRoles(List<RoleInfo> roles) { this.roles = roles; }
    public List<String> getPermissions() { return permissions; }
    public void setPermissions(List<String> permissions) { this.permissions = permissions; }
}
