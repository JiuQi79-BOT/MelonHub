package com.melonhub.rbac.r23;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.melonhub.rbac.api.admin.dto.AdminRoleCreateDTO;
import com.melonhub.rbac.api.identity.dto.IdentityLoginDTO;
import com.melonhub.rbac.api.organization.dto.IdentityPasswordDTO;
import com.melonhub.rbac.api.organization.dto.UserCreateDTO;
import com.melonhub.rbac.api.organization.dto.UserDTO;
import com.melonhub.rbac.api.shared.dto.EnabledDTO;
import com.melonhub.rbac.service.port.security.AdminPrincipal;
import com.melonhub.rbac.shared.RealDbControllerSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * R23 统一身份登录、退出与密码设置（含重置密码踢会话与边界校验） —— 真实 MySQL Controller 层双策略全量测试。
 */
abstract class R23_IdentityAuthControllerContractSupport extends RealDbControllerSupport {

    // =========================================================================
    // 统一测试参数区（路径、目标系统、合法/边界/非法密码与预期错误码）
    // =========================================================================
    private static final String PATH_ID_LOGIN = "/auth/login";
    private static final String PATH_ID_LOGOUT = "/auth/logout";
    private static final String PATH_ID_ME = "/auth/me";
    private static final String PATH_USERS = "/api/admin/users";
    private static final String PATH_USER_BY_ID = "/api/admin/users/{id}";
    private static final String PATH_SET_PASSWORD = "/api/admin/users/{id}/identity-password";
    private static final String PATH_USER_STATUS = "/api/admin/users/{id}/status";
    private static final String PATH_ADMIN_ROLES = "/api/admin/admin-roles";

    private static final String SYSTEM_COURSE = "course-project-management";
    private static final String SYSTEM_UNKNOWN = "unsupported-system";
    private static final String VALID_PASSWORD = "CourseUser!2026";
    private static final String NEW_PASSWORD = "RotatedUser!2026";
    private static final String BOUNDARY_PASS_8 = "Pass1234";
    private static final String BOUNDARY_PASS_72 = "U".repeat(72);
    private static final String WRONG_PASSWORD = "WrongCoursePass";
    private static final String INVALID_SHORT_7 = "1234567";
    private static final String INVALID_UTF8_75 = "密".repeat(25);
    private static final String TAMPER_SUFFIX = "x";
    private static final long MISSING_USER_ID = 9_999_999L;

    private static final String ERR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERR_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";

    @Test
    @DisplayName("R23-01 设置统一身份密码、登录签发 JWT、退出注销会话、改密踢旧会话、停用拒绝与全量非法边界校验")
    void identityLoginLogoutPasswordResetAndValidation() throws Exception {
        long dept = createDepartment("课程系", unique("cid"));
        String ext = unique("T2026");
        long userId = createUser("张老师", ext, "t@edu.cn", List.of(dept));

        // 1. 设置统一身份密码：正常 200 + 短密码/超72字节 UTF-8 400 + 不存在用户 404 + 缺 user.credentials 403
        mvc.perform(put(PATH_SET_PASSWORD, userId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdentityPasswordDTO(VALID_PASSWORD))))
                .andExpect(status().isOk());
        for (String badPass : List.of(" ", INVALID_SHORT_7, INVALID_UTF8_75)) {
            mvc.perform(put(PATH_SET_PASSWORD, userId).header("Authorization", superBearer)
                            .contentType(MediaType.APPLICATION_JSON).content(json(new IdentityPasswordDTO(badPass))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ERR_INVALID_REQUEST));
        }
        mvc.perform(put(PATH_SET_PASSWORD, MISSING_USER_ID).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdentityPasswordDTO(VALID_PASSWORD))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ERR_NOT_FOUND));

        String noCredRole = ("NOCRED_" + tag).toUpperCase();
        mvc.perform(post(PATH_ADMIN_ROLES).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AdminRoleCreateDTO(noCredRole, "无凭据", "", List.of("org.view", "user.status")))))
                .andExpect(status().isOk());
        var noCredAdmin = createAdminAndLogin("nocred", noCredRole, AdminPrincipal.MANAGER, null, null);
        mvc.perform(put(PATH_SET_PASSWORD, userId).header("Authorization", noCredAdmin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdentityPasswordDTO(VALID_PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ERR_FORBIDDEN));

        // 2. 登录成功返回 Bearer JWT（可调 /auth/me）；错密码 401、未知账号 401、非法目标系统 400、空字段 400
        String token1 = parseJson(mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(ext, VALID_PASSWORD, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(get(PATH_ID_ME).header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(ext));
        // 篡改签名 JWT -> 401
        mvc.perform(get(PATH_ID_ME).header("Authorization", "Bearer " + token1 + TAMPER_SUFFIX))
                .andExpect(status().isUnauthorized());

        // 2.5 新建用户附带 8 字符边界初始密码即可直接登录；72 字节边界改密亦允许
        String initExt = unique("INIT");
        mvc.perform(post(PATH_USERS).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserCreateDTO("初密教师", initExt, "", List.of(dept), BOUNDARY_PASS_8))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(initExt, BOUNDARY_PASS_8, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_SET_PASSWORD, userId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdentityPasswordDTO(BOUNDARY_PASS_72))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH_SET_PASSWORD, userId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdentityPasswordDTO(VALID_PASSWORD))))
                .andExpect(status().isOk());
        // 改密会踢掉之前的 token1，为后续退出测试重新登录签发 token1
        token1 = parseJson(mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(ext, VALID_PASSWORD, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();

        mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(ext, WRONG_PASSWORD, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO("ghost-user", VALID_PASSWORD, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(ext, VALID_PASSWORD, List.of(SYSTEM_UNKNOWN)))))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(" ", VALID_PASSWORD, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isBadRequest());

        // 3. 退出登录返回 204，且 token1 调 /auth/me 立即 401
        mvc.perform(post(PATH_ID_LOGOUT).header("Authorization", "Bearer " + token1))
                .andExpect(status().isNoContent());
        mvc.perform(get(PATH_ID_ME).header("Authorization", "Bearer " + token1))
                .andExpect(status().isUnauthorized());

        // 4. 重置密码后旧 JWT 会话全部失效；停用用户后拒绝登录
        String token2 = parseJson(mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(ext, VALID_PASSWORD, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
        mvc.perform(put(PATH_SET_PASSWORD, userId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new IdentityPasswordDTO(NEW_PASSWORD))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_ID_ME).header("Authorization", "Bearer " + token2))
                .andExpect(status().isUnauthorized());

        // 4.5 修改用户 externalId -> 撤销全部统一身份 JWT 会话并同步登录名（旧登录名 401，新登录名 200）
        String tokenBeforeRename = parseJson(mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(ext, NEW_PASSWORD, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
        String renamedExt = unique("T2026R");
        mvc.perform(put(PATH_USER_BY_ID, userId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UserDTO("张老师更名", renamedExt, "t@edu.cn", List.of(dept)))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH_ID_ME).header("Authorization", "Bearer " + tokenBeforeRename))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(ext, NEW_PASSWORD, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(renamedExt, NEW_PASSWORD, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isOk());

        mvc.perform(patch(PATH_USER_STATUS, userId).header("Authorization", superBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnabledDTO(false))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH_ID_LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new IdentityLoginDTO(renamedExt, NEW_PASSWORD, List.of(SYSTEM_COURSE)))))
                .andExpect(status().isUnauthorized());
    }
}
