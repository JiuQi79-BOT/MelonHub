package com.melonhub.rbac.service.port.console;

import com.melonhub.rbac.service.port.console.ConsoleModels.HealthResult;
import com.melonhub.rbac.service.port.console.ConsoleModels.SearchResultsResult;
import com.melonhub.rbac.service.port.console.ConsoleModels.SystemStatusResult;
import com.melonhub.rbac.service.port.security.Visibility;

/**
 * 健康探针、控制台全局搜索与降级容错系统状态看板用例端口。
 */
public interface ConsoleUseCase {
    HealthResult health();

    SearchResultsResult search(String keyword, Visibility visible);

    SystemStatusResult status(Visibility visible);
}
