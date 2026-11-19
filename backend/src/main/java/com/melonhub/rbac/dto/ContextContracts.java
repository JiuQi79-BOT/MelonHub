package com.melonhub.rbac.dto;

import cn.edu.xmu.clonefactory.CopyFrom;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/** Request and response mappings for the business-system authorization context API. */
public final class ContextContracts {
    private ContextContracts() {}

    @Getter @Setter
    @CopyFrom(Requests.Context.class)
    public static class IssueCommand {
        private String externalId;
        private long departmentId;
        private List<Long> activeRoleIds;
        private Boolean switchDepartment;
    }

    public record Role(long id, String name, String code) {}

    public record ContextResult(String contextToken, long userId, long departmentId, long applicationId,
                                long contextVersion, Instant expiresAt, List<Role> roles, List<String> permissions) {}

    public record CheckResult(boolean allowed, Map<String,Boolean> decisions, long contextVersion) {}

    @Getter @Setter
    @CopyFrom(ContextResult.class)
    public static class ContextResponse {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String contextToken;
        private long userId;
        private long departmentId;
        private long applicationId;
        private long contextVersion;
        private Instant expiresAt;
        private List<Role> roles;
        private List<String> permissions;
    }

    @Getter @Setter
    @CopyFrom(CheckResult.class)
    public static class CheckResponse {
        private boolean allowed;
        private Map<String,Boolean> decisions;
        private long contextVersion;
    }
}
