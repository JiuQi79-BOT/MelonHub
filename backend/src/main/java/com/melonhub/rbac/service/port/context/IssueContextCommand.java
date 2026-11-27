package com.melonhub.rbac.service.port.context;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 业务系统签发权限上下文命令对象，配合编译期 CloneFactory 从 API DTO 拷贝字段。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IssueContextCommand {
    private String externalId;
    private long departmentId;
    private List<Long> activeRoleIds;
    private Boolean switchDepartment;

    public IssueContextCommand(String externalId, long departmentId, List<Long> activeRoleIds) {
        this(externalId, departmentId, activeRoleIds, null);
    }
}
