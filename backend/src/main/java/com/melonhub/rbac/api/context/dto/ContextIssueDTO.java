package com.melonhub.rbac.api.context.dto;

import cn.edu.xmu.clonefactory.CopyTo;
import com.melonhub.rbac.service.port.context.IssueContextCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 业务系统签发在线权限上下文请求 DTO，使用编译期 CloneFactory 拷贝为 {@link IssueContextCommand}。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@CopyTo(IssueContextCommand.class)
public class ContextIssueDTO {
    @NotBlank
    @Size(max = 100)
    private String externalId;

    @Positive
    private long departmentId;

    @Size(max = 1000)
    private List<@NotNull @Positive Long> activeRoleIds;

    private Boolean switchDepartment;

    public ContextIssueDTO(String externalId, long departmentId, List<Long> activeRoleIds) {
        this(externalId, departmentId, activeRoleIds, null);
    }
}
