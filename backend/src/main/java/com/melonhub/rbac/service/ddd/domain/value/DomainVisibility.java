package com.melonhub.rbac.service.ddd.domain.value;

import java.util.Collection;

/**
 * 领域层的可见范围值对象：{@code null} 表示不受限，空集合表示无可见对象。
 */
public record DomainVisibility(Collection<Long> applications, Collection<Long> departments) {
    public static final DomainVisibility ALL = new DomainVisibility(null, null);
}
