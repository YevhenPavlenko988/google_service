package com.github.yevhen.googleservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GoogleUserInfo(
        String sub,
        String email,
        @JsonProperty("email_verified") boolean emailVerified,
        String name,
        String picture
) {}
