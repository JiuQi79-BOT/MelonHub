package com.melonhub.rbac.api.application;

import com.melonhub.rbac.api.application.vo.ApiKeyCreatedVO;
import com.melonhub.rbac.api.application.vo.ApiKeyVO;
import com.melonhub.rbac.api.application.vo.ApplicationCreatedVO;
import com.melonhub.rbac.api.application.vo.ApplicationVO;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApiKeyCreatedResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApiKeyResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApplicationCreatedResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApplicationResult;

/**
 * 业务软件模块应用层结果与 HTTP VO 映射器。
 */
public final class ApplicationVoMapper {
    private ApplicationVoMapper() {}

    public static ApplicationVO toApplicationVO(ApplicationResult r) {
        return new ApplicationVO(r.id(), r.name(), r.code(), r.description(), r.owner(), r.enabled(),
                r.rateLimit(), r.createdAt(), r.roleCount(), r.permissionCount(), r.activeKeyCount());
    }

    public static ApplicationCreatedVO toApplicationCreatedVO(ApplicationCreatedResult r) {
        return new ApplicationCreatedVO(r.id(), r.apiKey(), r.keyId());
    }

    public static ApiKeyVO toApiKeyVO(ApiKeyResult r) {
        return new ApiKeyVO(r.id(), r.name(), r.prefix(), r.createdBy(), r.createdAt(),
                r.expiresAt(), r.lastUsedAt(), r.revokedAt(), r.status());
    }

    public static ApiKeyCreatedVO toApiKeyCreatedVO(ApiKeyCreatedResult r) {
        return new ApiKeyCreatedVO(r.id(), r.name(), r.prefix(), r.apiKey(), r.expiresAt());
    }
}
