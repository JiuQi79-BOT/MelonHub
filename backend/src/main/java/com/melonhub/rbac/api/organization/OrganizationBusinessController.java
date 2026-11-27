package com.melonhub.rbac.api.organization;

import com.melonhub.rbac.api.organization.vo.DepartmentUserVO;
import com.melonhub.rbac.api.organization.vo.DepartmentVO;
import com.melonhub.rbac.infrastructure.security.ApplicationAccess;
import com.melonhub.rbac.service.port.organization.OrganizationUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务系统组织架构只读查询接口（{@code /api/v1/departments/**}）。
 */
@RestController
@RequestMapping("/api/v1")
public class OrganizationBusinessController {
    private final OrganizationUseCase organization;

    public OrganizationBusinessController(OrganizationUseCase organization) {
        this.organization = organization;
    }

    @GetMapping("/departments")
    public List<DepartmentVO> departments(HttpServletRequest req) {
        ApplicationAccess.of(req);
        return organization.departments(null).stream().map(OrganizationVoMapper::toDepartmentVO).toList();
    }

    @GetMapping("/departments/{dept}/users")
    public List<DepartmentUserVO> departmentUsers(@PathVariable long dept, HttpServletRequest req) {
        ApplicationAccess.of(req);
        return organization.departmentUsers(dept).stream().map(OrganizationVoMapper::toDepartmentUserVO).toList();
    }
}
