package com.melonhub.rbac.api.context.vo;

import cn.edu.xmu.clonefactory.CopyFrom;
import com.melonhub.rbac.service.port.context.ContextModels.CheckResult;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 批量权限校验响应 VO。
 */
@Getter
@Setter
@NoArgsConstructor
@CopyFrom(CheckResult.class)
public class CheckVO {
    private boolean allowed;
    private Map<String, Boolean> decisions;
    private long contextVersion;
}
