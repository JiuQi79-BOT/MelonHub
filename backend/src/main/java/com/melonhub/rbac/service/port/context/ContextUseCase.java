package com.melonhub.rbac.service.port.context;

import com.melonhub.rbac.service.port.context.ContextModels.CheckResult;
import com.melonhub.rbac.service.port.context.ContextModels.ContextResult;
import java.util.List;

/**
 * 业务系统授权上下文签发、生效权限查询、批量鉴权与撤销用例端口。
 */
public interface ContextUseCase {
    long CONTEXT_SECONDS = 3600;

    ContextResult issueContext(long app, IssueContextCommand input, String actor);

    ContextResult effective(long app, String token);

    CheckResult check(long app, String token, List<String> requested);

    void revoke(long app, String token, String actor);
}
