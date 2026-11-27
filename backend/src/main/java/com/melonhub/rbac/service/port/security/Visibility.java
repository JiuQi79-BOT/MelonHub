package com.melonhub.rbac.service.port.security;

import java.util.Collection;

/**
 * 控制台管理员的数据可见范围：分别限定可见软件与可见部门，{@code null} 表示不限，空集合表示无可见对象。
 */
public record Visibility(Collection<Long> applications, Collection<Long> departments) {
    public static final Visibility ALL = new Visibility(null, null);

    public boolean application(long id) {
        return applications == null || applications.contains(id);
    }

    public boolean department(Long id) {
        return departments == null || (id != null && departments.contains(id));
    }
}
