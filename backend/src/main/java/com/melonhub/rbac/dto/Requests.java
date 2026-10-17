package com.melonhub.rbac.dto;

import java.util.List;
import jakarta.validation.constraints.*;

public final class Requests {
    public record Login(@NotBlank @Size(max=100) String username, @NotBlank @Size(max=100) String password) {}
    public record Department(@NotBlank @Size(max=100) String name, @NotBlank @Pattern(regexp="[a-zA-Z0-9_.-]{1,64}") String code) {}
    public record Application(@NotBlank @Size(max=100) String name, @NotBlank @Pattern(regexp="[a-zA-Z0-9_.-]{1,64}") String code, @Size(max=500) String description) {}
    public record User(@NotBlank @Size(max=100) String name, @NotBlank @Size(max=100) String externalId, @Size(max=150) String email, @NotNull @Size(max=100) List<@NotNull @Positive Long> departmentIds) {}
    public record Role(@NotBlank @Size(max=100) String name, @NotBlank @Pattern(regexp="[a-zA-Z0-9_.:-]{1,100}") String code, @Size(max=500) String description) {}
    public record Permission(@NotBlank @Size(max=100) String name, @NotBlank @Pattern(regexp="[a-zA-Z0-9_.:-]{1,100}") String code, @NotBlank @Size(max=100) String resource, @NotBlank @Size(max=64) String action) {}
    public record Ids(@NotNull @Size(max=1000) List<@NotNull @Positive Long> ids) {}
    public record Enabled(@NotNull Boolean enabled) {}
    public record Context(@NotBlank @Size(max=100) String externalId, @Positive long departmentId, @Size(max=1000) List<@NotNull @Positive Long> activeRoleIds) {}
    public record Check(@NotNull @Size(min=1,max=100) List<@NotBlank @Size(max=100) String> permissions) {}
}
