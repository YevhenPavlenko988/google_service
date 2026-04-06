package com.github.yevhen.googleservice.dto;

/** Sent to auth_service /internal/google-auth */
public record GoogleAuthInternalRequest(String googleId, String email) {}
