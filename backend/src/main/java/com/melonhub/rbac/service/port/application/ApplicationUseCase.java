package com.melonhub.rbac.service.port.application;

import com.melonhub.rbac.service.port.application.ApplicationModels.ApiKeyCreatedResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApiKeyResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApplicationCreatedResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.ApplicationResult;
import com.melonhub.rbac.service.port.application.ApplicationModels.CreateApiKeyCommand;
import com.melonhub.rbac.service.port.application.ApplicationModels.SaveApplicationCommand;
import java.util.Collection;
import java.util.List;

/**
 * 业务软件接入、多 API Key 凭据与软件限流用例端口。
 */
public interface ApplicationUseCase {
    List<ApplicationResult> applications(Collection<Long> visible);

    ApplicationCreatedResult application(SaveApplicationCommand body, String actor);

    long updateApplication(long id, SaveApplicationCommand body, String actor);

    void applicationStatus(long id, boolean enabled, String actor);

    void deleteApplication(long id, String actor);

    List<ApiKeyResult> keys(long app);

    ApiKeyCreatedResult createKey(long app, CreateApiKeyCommand body, String actor);

    void revokeKey(long app, long key, String actor);

    String rotateKey(long app, String actor);

    int applicationRateLimit(long id, int fallback);

    void applicationRateLimit(long id, Integer perSecond, String actor);
}
