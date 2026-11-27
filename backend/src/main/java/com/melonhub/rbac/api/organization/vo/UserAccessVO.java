package com.melonhub.rbac.api.organization.vo;

import java.util.List;

/**
 * 用户跨部门跨软件授权全览响应 VO。
 */
public record UserAccessVO(UserVO user, List<AccessDomainVO> domains, List<ActiveContextVO> contexts) {}
