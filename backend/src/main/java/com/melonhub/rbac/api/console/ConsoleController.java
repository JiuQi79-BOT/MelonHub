package com.melonhub.rbac.api.console;

import static com.melonhub.rbac.service.port.security.AdminFunction.ORG_VIEW;
import static com.melonhub.rbac.service.port.security.AdminFunction.SYSTEM_VIEW;

import com.melonhub.rbac.api.console.vo.HealthVO;
import com.melonhub.rbac.api.console.vo.SearchResultsVO;
import com.melonhub.rbac.api.console.vo.SystemStatusVO;
import com.melonhub.rbac.infrastructure.security.AdminAccess;
import com.melonhub.rbac.infrastructure.security.RequiresFunction;
import com.melonhub.rbac.service.port.console.ConsoleUseCase;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台健康探针、控制台全局搜索与系统状态看板接口。
 */
@RestController
public class ConsoleController {
    private final ConsoleUseCase console;

    public ConsoleController(ConsoleUseCase console) {
        this.console = console;
    }

    @GetMapping("/api/health")
    public ResponseEntity<HealthVO> health() {
        HealthVO vo = ConsoleVoMapper.toHealthVO(console.health());
        return ResponseEntity.status("UP".equals(vo.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(vo);
    }

    @RequiresFunction(ORG_VIEW)
    @GetMapping("/api/admin/search")
    public SearchResultsVO search(@RequestParam String q, HttpServletRequest req) {
        return ConsoleVoMapper.toSearchResultsVO(console.search(q, AdminAccess.of(req).visibility()));
    }

    @RequiresFunction(SYSTEM_VIEW)
    @GetMapping("/api/admin/system/status")
    public SystemStatusVO status(HttpServletRequest req) {
        return ConsoleVoMapper.toSystemStatusVO(console.status(AdminAccess.of(req).visibility()));
    }
}
