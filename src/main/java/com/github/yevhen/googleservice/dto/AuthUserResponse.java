package com.github.yevhen.googleservice.dto;

import java.util.UUID;

/** Matches UserResponse from auth_service. */
public record AuthUserResponse(
        UUID id,
        String email,
        String role,
        boolean mustChangePassword
) {}
