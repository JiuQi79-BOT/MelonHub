package com.melonhub.rbac.api.organization;

import static com.melonhub.rbac.service.port.security.AdminFunction.DEPARTMENT_MANAGE;
import static com.melonhub.rbac.service.port.security.AdminFunction.ORG_VIEW;

import com.melonhub.rbac.api.organization.dto.DepartmentDTO;
import com.melonhub.rbac.api.organization.vo.DepartmentVO;
import com.melonhub.rbac.api.shared.vo.IdVO;
import com.melonhub.rbac.api.shared.vo.OkVO;
import com.melonhub.rbac.infrastructure.security.AdminAccess;
import com.melonhub.rbac.infrastructure.security.RequiresFunction;
import com.melonhub.rbac.service.port.organization.OrganizationModels.SaveDepartmentCommand;
import com.melonhub.rbac.service.port.organization.OrganizationUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台部门管理接口。
 */
@RestController
@RequestMapping("/api/admin/departments")
public class DepartmentController {
    private final OrganizationUseCase organization;

    public DepartmentController(OrganizationUseCase organization) {
        this.organization = organization;
    }

    @RequiresFunction(ORG_VIEW)
    @GetMapping
    public List<DepartmentVO> list(HttpServletRequest req) {
        return organization.departments(AdminAccess.of(req).visibleDepartments()).stream()
                .map(OrganizationVoMapper::toDepartmentVO).toList();
    }

    @RequiresFunction(DEPARTMENT_MANAGE)
    @PostMapping
    public IdVO create(@Valid @RequestBody DepartmentDTO body, HttpServletRequest req) {
        return new IdVO(organization.department(new SaveDepartmentCommand(body.name(), body.code()),
                null, AdminAccess.of(req).actor()));
    }

    @RequiresFunction(DEPARTMENT_MANAGE)
    @PutMapping("/{id}")
    public IdVO update(@PathVariable long id, @Valid @RequestBody DepartmentDTO body, HttpServletRequest req) {
        return new IdVO(organization.department(new SaveDepartmentCommand(body.name(), body.code()),
                id, AdminAccess.of(req).actor()));
    }

    @RequiresFunction(DEPARTMENT_MANAGE)
    @DeleteMapping("/{id}")
    public OkVO delete(@PathVariable long id, HttpServletRequest req) {
        organization.deleteDepartment(id, AdminAccess.of(req).actor());
        return OkVO.OK;
    }
}
