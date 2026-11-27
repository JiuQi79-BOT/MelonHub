package com.melonhub.rbac.service.port.maintenance;

/**
 * 后台周期性维护任务用例端口：到期角色回收、过期凭据与投递清理、审计前缀保留期清理。
 */
public interface MaintenanceUseCase {
    void expireAssignments();

    void purgeExpired();

    void purgeAudit();
}
