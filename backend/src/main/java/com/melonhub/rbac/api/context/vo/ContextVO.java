package com.melonhub.rbac.api.context.vo;

import cn.edu.xmu.clonefactory.CopyFrom;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.melonhub.rbac.service.port.context.ContextModels.ContextResult;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 权限上下文签发与生效权限查询响应 VO（{@code expiresAt} 保持 ISO-8601 UTC 格式）。
 */
@Getter
@Setter
@NoArgsConstructor
@CopyFrom(ContextResult.class)
public class ContextVO {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String contextToken;
    private long userId;
    private long departmentId;
    private long applicationId;
    private long contextVersion;
    private Instant expiresAt;

    @CopyFrom.Exclude({ContextResult.class})
    private List<ContextRoleVO> roles;

    private List<String> permissions;
}
