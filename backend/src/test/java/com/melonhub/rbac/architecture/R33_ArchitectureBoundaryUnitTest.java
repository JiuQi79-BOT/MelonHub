package com.melonhub.rbac.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 架构边界与三层按业务分包守护测试（对齐 CourseProjectMgt 规范，覆盖 R33）。
 */
class R33_ArchitectureBoundaryUnitTest {

    // =========================================================================
    // 统一测试参数区（目录根路径、允许的顶层包集合、各层禁止导入前缀与最小文件数基线）
    // =========================================================================
    private static final Path MAIN_ROOT = resolveMainJavaRoot();
    private static final Set<String> EXPECTED_TOP_LEVEL_PACKAGES = Set.of("api", "infrastructure", "service");
    private static final Set<String> EXPECTED_ROOT_FILES = Set.of("RbacApplication.java");
    private static final List<String> FORBIDDEN_LEGACY_PACKAGES = List.of(
            "dao", "controller", "dto", "cache", "config", "security", "identity", "entity", "repository", "exception"
    );
    private static final List<String> API_FORBIDDEN_IMPORTS = List.of(
            "com.melonhub.rbac.service.structured.",
            "com.melonhub.rbac.service.ddd.",
            "com.melonhub.rbac.infrastructure.persistence.",
            "com.melonhub.rbac.infrastructure.cache.",
            "com.melonhub.rbac.dao.",
            "jakarta.persistence.",
            "org.springframework.data."
    );
    private static final List<String> PORT_FORBIDDEN_IMPORTS = List.of(
            "com.melonhub.rbac.api.",
            "com.melonhub.rbac.infrastructure.",
            "com.melonhub.rbac.service.structured.",
            "com.melonhub.rbac.service.ddd.",
            "com.melonhub.rbac.dao.",
            "com.fasterxml.jackson.",
            "jakarta.validation.",
            "jakarta.persistence.",
            "org.springframework.data.",
            "org.springframework.stereotype."
    );
    private static final List<String> STRUCTURED_FORBIDDEN_IMPORTS = List.of(
            "com.melonhub.rbac.api.",
            "com.melonhub.rbac.service.ddd.",
            "com.melonhub.rbac.dao.",
            "jakarta.validation."
    );
    private static final List<String> DDD_APP_FORBIDDEN_IMPORTS = List.of(
            "com.melonhub.rbac.api.",
            "com.melonhub.rbac.infrastructure.",
            "com.melonhub.rbac.service.structured.",
            "com.melonhub.rbac.dao.",
            "jakarta.persistence.",
            "jakarta.validation.",
            "org.springframework.data.",
            "org.springframework.web.",
            "org.springframework.http."
    );
    private static final List<String> INFRA_FORBIDDEN_IMPORTS = List.of(
            "com.melonhub.rbac.api.",
            "com.melonhub.rbac.service.structured.",
            "com.melonhub.rbac.service.ddd.application.",
            "com.melonhub.rbac.dao."
    );
    private static final int MIN_CONTROLLER_COUNT = 16;
    private static final int MIN_STRUCTURED_SERVICE_COUNT = 13;
    private static final int MIN_DDD_APPLICATION_SERVICE_COUNT = 13;
    private static final int MIN_DDD_DOMAIN_FILES = 20;
    private static final int MIN_JPA_GATEWAY_ADAPTER_COUNT = 9;

    private static Path resolveMainJavaRoot() {
        Path direct = Path.of("src/main/java/com/melonhub/rbac");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        return Path.of("backend/src/main/java/com/melonhub/rbac");
    }

    private static List<String> importsOf(Path file) throws IOException {
        List<String> imports = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("import ")) {
                imports.add(trimmed.substring(7).replace("static ", "").replace(";", "").strip());
            }
        }
        return imports;
    }

    private static List<Path> javaFilesUnder(String relative) throws IOException {
        Path dir = relative.isEmpty() ? MAIN_ROOT : MAIN_ROOT.resolve(relative);
        assertThat(Files.isDirectory(dir)).as("目录存在: " + dir).isTrue();
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    @DisplayName("ARCH-01 顶层仅保留 api/service/infrastructure 三大业务分层包与 RbacApplication，所有旧扁平包已彻底移除")
    void topLevelPackageStructureStrictlyMatchesThreeLayerLayout() throws IOException {
        Set<String> dirs = new TreeSet<>();
        Set<String> files = new TreeSet<>();
        try (Stream<Path> children = Files.list(MAIN_ROOT)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child)) {
                    dirs.add(child.getFileName().toString());
                } else {
                    files.add(child.getFileName().toString());
                }
            }
        }
        assertThat(dirs).containsExactlyInAnyOrderElementsOf(EXPECTED_TOP_LEVEL_PACKAGES);
        assertThat(files).containsExactlyInAnyOrderElementsOf(EXPECTED_ROOT_FILES);
        for (String legacy : FORBIDDEN_LEGACY_PACKAGES) {
            assertThat(Files.exists(MAIN_ROOT.resolve(legacy))).as("遗留包不应存在: " + legacy).isFalse();
        }
        assertThat(Files.exists(MAIN_ROOT.resolve("service/ddd/infrastructure"))).isFalse();
    }

    @Test
    @DisplayName("ARCH-02 api 层控制器、DTO、VO 与异常映射严禁穿透访问 structured/ddd/JPA/Cache/DAO")
    void apiLayerDependsOnlyOnServicePortsAndSecurityGuards() throws IOException {
        List<Path> files = javaFilesUnder("api");
        long controllerCount = files.stream().filter(p -> p.getFileName().toString().endsWith("Controller.java")).count();
        assertThat(controllerCount).isGreaterThanOrEqualTo(MIN_CONTROLLER_COUNT);
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            for (String imp : importsOf(file)) {
                if (API_FORBIDDEN_IMPORTS.stream().anyMatch(imp::startsWith)) {
                    violations.add(MAIN_ROOT.relativize(file) + " -> " + imp);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("ARCH-03 所有 Controller 的 @RequestBody 参数都必须显式声明 @Valid")
    void controllerRequestBodiesAlwaysDeclareValid() throws IOException {
        List<Path> controllers = javaFilesUnder("api").stream()
                .filter(p -> p.getFileName().toString().endsWith("Controller.java")).toList();
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (Path controller : controllers) {
            List<String> lines = Files.readAllLines(controller);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.contains("@RequestBody")) {
                    continue;
                }
                checked++;
                boolean hasValidSameLine = line.contains("@Valid");
                boolean hasValidPrevLine = i > 0 && lines.get(i - 1).contains("@Valid");
                if (!hasValidSameLine && !hasValidPrevLine) {
                    violations.add(controller.getFileName() + ":" + (i + 1) + " -> " + line.strip());
                }
            }
        }
        assertThat(checked).isGreaterThan(30);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("ARCH-04 service.port 用例端口严禁泄露 api/infrastructure/Jackson/BeanValidation/JPA/Spring组件")
    void servicePortsRemainPureApplicationContracts() throws IOException {
        List<Path> files = javaFilesUnder("service/port");
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            for (String imp : importsOf(file)) {
                if (PORT_FORBIDDEN_IMPORTS.stream().anyMatch(imp::startsWith)) {
                    violations.add(MAIN_ROOT.relativize(file) + " -> " + imp);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("ARCH-05 service.structured 结构化服务严禁引用 api/ddd/dao/jakarta.validation")
    void structuredServicesAreStrictlyIsolatedFromDddAndHttp() throws IOException {
        List<Path> files = javaFilesUnder("service/structured");
        long serviceCount = files.stream().filter(p -> p.getFileName().toString().endsWith("Service.java")).count();
        assertThat(serviceCount).isGreaterThanOrEqualTo(MIN_STRUCTURED_SERVICE_COUNT);
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            for (String imp : importsOf(file)) {
                if (STRUCTURED_FORBIDDEN_IMPORTS.stream().anyMatch(imp::startsWith)) {
                    violations.add(MAIN_ROOT.relativize(file) + " -> " + imp);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("ARCH-06 service.ddd.domain 保持 100% 零框架纯 Java（仅允许 java.*/javax.crypto.*/domain）")
    void dddDomainLayerIsPureJava() throws IOException {
        List<Path> files = javaFilesUnder("service/ddd/domain");
        assertThat(files).hasSizeGreaterThanOrEqualTo(MIN_DDD_DOMAIN_FILES);
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            for (String imp : importsOf(file)) {
                boolean allowed = imp.startsWith("java.")
                        || imp.startsWith("javax.crypto.")
                        || imp.startsWith("com.melonhub.rbac.service.ddd.domain.");
                if (!allowed) {
                    violations.add(MAIN_ROOT.relativize(file) + " -> " + imp);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("ARCH-07 service.ddd.application 严禁直接依赖 infrastructure/api/structured/JPA/Web/HTTP/Validation")
    void dddApplicationLayerNeverDependsOnInfrastructureOrWeb() throws IOException {
        List<Path> files = javaFilesUnder("service/ddd/application");
        long serviceCount = files.stream().filter(p -> p.getFileName().toString().endsWith("Service.java")).count();
        assertThat(serviceCount).isGreaterThanOrEqualTo(MIN_DDD_APPLICATION_SERVICE_COUNT);
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            for (String imp : importsOf(file)) {
                if (DDD_APP_FORBIDDEN_IMPORTS.stream().anyMatch(imp::startsWith)) {
                    violations.add(MAIN_ROOT.relativize(file) + " -> " + imp);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("ARCH-08 infrastructure 层严禁反向依赖 api/structured/ddd.application，且 JPA 网关适配器收口在 persistence.gateway")
    void infrastructureLayerNeverImportsApiOrConcreteServices() throws IOException {
        List<Path> files = javaFilesUnder("infrastructure");
        List<Path> gateways = javaFilesUnder("infrastructure/persistence/gateway");
        assertThat(gateways).hasSizeGreaterThanOrEqualTo(MIN_JPA_GATEWAY_ADAPTER_COUNT);
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            for (String imp : importsOf(file)) {
                if (INFRA_FORBIDDEN_IMPORTS.stream().anyMatch(imp::startsWith)) {
                    violations.add(MAIN_ROOT.relativize(file) + " -> " + imp);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("ARCH-09 持久层全面使用纯 Spring Data JPA，严禁直接使用 EntityManager、自定义 *RepositoryImpl 或 JdbcTemplate")
    void persistenceLayerUsesPureSpringDataJpaWithoutEntityManager() throws IOException {
        List<Path> allMainFiles = javaFilesUnder("");
        List<String> violations = new ArrayList<>();
        for (Path file : allMainFiles) {
            String name = file.getFileName().toString();
            if (name.endsWith("RepositoryCustom.java") || name.endsWith("RepositoryImpl.java") || name.equals("JpaQuery.java")) {
                violations.add("legacy custom fragment: " + MAIN_ROOT.relativize(file));
            }
            for (String imp : importsOf(file)) {
                if (imp.equals("jakarta.persistence.EntityManager")
                        || imp.equals("jakarta.persistence.PersistenceContext")
                        || imp.equals("org.springframework.jdbc.core.JdbcTemplate")
                        || imp.equals("org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")) {
                    violations.add(MAIN_ROOT.relativize(file) + " -> " + imp);
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
