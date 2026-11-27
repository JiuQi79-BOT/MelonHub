package com.melonhub.rbac.api.identity;

import com.melonhub.rbac.api.identity.dto.IdentityLoginDTO;
import com.melonhub.rbac.api.identity.vo.CourseAuditEventVO;
import com.melonhub.rbac.api.identity.vo.IdentityApiPermissionCheckVO;
import com.melonhub.rbac.api.identity.vo.IdentityCurrentUserVO;
import com.melonhub.rbac.api.identity.vo.IdentityDepartmentUserVO;
import com.melonhub.rbac.api.identity.vo.IdentityDepartmentVO;
import com.melonhub.rbac.api.identity.vo.IdentityLoginVO;
import com.melonhub.rbac.api.identity.vo.IdentityPageVO;
import com.melonhub.rbac.api.identity.vo.IdentityPermissionCheckVO;
import com.melonhub.rbac.api.identity.vo.IdentityUserLookupVO;
import com.melonhub.rbac.service.port.error.ApiException;
import com.melonhub.rbac.service.port.identity.IdentityUseCase;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外提供课程管理系统使用的统一身份认证、组织查询与课程审计 API（{@code /auth/**}）。
 */
@RestController
@RequestMapping("/auth")
public class IdentityController {
    private final IdentityUseCase identityService;

    public IdentityController(IdentityUseCase identityService) {
        this.identityService = identityService;
    }

    @PostMapping("/login")
    public IdentityLoginVO login(@Valid @RequestBody IdentityLoginDTO body) {
        return IdentityVoMapper.toLoginVO(identityService.login(body.username(), body.password(), body.systems()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        identityService.logout(authorization);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public IdentityCurrentUserVO me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return IdentityVoMapper.toCurrentUserVO(identityService.currentUser(authorization));
    }

    @GetMapping("/audit-logs")
    public IdentityPageVO<CourseAuditEventVO> auditLogs(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw ApiException.bad("页码须大于 0，页大小须在 1 到 100 之间。");
        }
        if (actorId != null && actorId < 1) {
            throw ApiException.bad("操作人 ID 须大于 0。");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.bad("起始时间不能晚于结束时间。");
        }
        return IdentityVoMapper.toCourseAuditPageVO(
                identityService.auditLogs(authorization, actor, actorId, target, from, to, page, pageSize));
    }

    @GetMapping("/departments")
    public IdentityPageVO<IdentityDepartmentVO> departments(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return IdentityVoMapper.toDepartmentPageVO(identityService.departments(
                authorization, keyword, parentId, Math.max(1, page), Math.min(100, Math.max(1, pageSize))));
    }

    @GetMapping("/departments/{departmentId}/users")
    public IdentityPageVO<IdentityDepartmentUserVO> departmentUsers(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long departmentId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return IdentityVoMapper.toDepartmentUserPageVO(identityService.departmentUsers(
                authorization, departmentId, keyword, Math.max(1, page), Math.min(100, Math.max(1, pageSize))));
    }

    @GetMapping("/permissions/{permission}")
    public IdentityPermissionCheckVO permission(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String permission) {
        return IdentityVoMapper.toPermissionCheckVO(identityService.checkPermission(authorization, permission));
    }

    @GetMapping("/api-permissions/{operationId}")
    public IdentityApiPermissionCheckVO apiPermission(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String operationId) {
        return IdentityVoMapper.toApiPermissionCheckVO(identityService.checkApiPermission(authorization, operationId));
    }

    @GetMapping("/users/{userId}")
    public IdentityUserLookupVO user(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String userId) {
        return IdentityVoMapper.toUserLookupVO(identityService.lookupUser(authorization, userId));
    }
}
