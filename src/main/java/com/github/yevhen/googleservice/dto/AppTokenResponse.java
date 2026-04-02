package com.github.yevhen.googleservice.dto;

public record AppTokenResponse(
        String accessToken,
        String refreshToken,
        long expiresIn
) {}
