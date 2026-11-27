package com.melonhub.rbac.api.context;

import cn.edu.xmu.clonefactory.util.CloneFactory;
import com.melonhub.rbac.api.context.dto.ContextIssueDTO;
import com.melonhub.rbac.api.context.vo.CheckVO;
import com.melonhub.rbac.api.context.vo.ContextRoleVO;
import com.melonhub.rbac.api.context.vo.ContextVO;
import com.melonhub.rbac.service.port.context.ContextModels.CheckResult;
import com.melonhub.rbac.service.port.context.ContextModels.ContextResult;
import com.melonhub.rbac.service.port.context.IssueContextCommand;
import java.util.List;

/**
 * 权限上下文模块 DTO/Command 与 Result/VO 映射器（基于编译期 CloneFactory）。
 */
public final class ContextVoMapper {
    private ContextVoMapper() {}

    public static IssueContextCommand toCommand(ContextIssueDTO dto) {
        return CloneFactory.copy(new IssueContextCommand(), dto);
    }

    public static ContextVO toContextVO(ContextResult result) {
        ContextVO vo = CloneFactory.copy(new ContextVO(), result);
        vo.setRoles(result.roles() == null ? List.of() : result.roles().stream()
                .map(r -> new ContextRoleVO(r.id(), r.name(), r.code())).toList());
        return vo;
    }

    public static CheckVO toCheckVO(CheckResult result) {
        return CloneFactory.copy(new CheckVO(), result);
    }
}
