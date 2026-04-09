package com.github.yevhen.googleservice.service;

import com.github.yevhen.common.config.JwtProperties;
import com.github.yevhen.common.exception.ServiceException;
import com.github.yevhen.common.security.JwtHelper;
import com.github.yevhen.googleservice.config.GoogleProperties;
import com.github.yevhen.googleservice.dto.AppTokenResponse;
import com.github.yevhen.googleservice.dto.AuthUserResponse;
import com.github.yevhen.googleservice.dto.GoogleAuthInternalRequest;
import com.github.yevhen.googleservice.dto.GoogleTokenResponse;
import com.github.yevhen.googleservice.dto.GoogleUserInfo;
import com.github.yevhen.googleservice.model.GoogleToken;
import com.github.yevhen.googleservice.repository.GoogleTokenRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class GoogleAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);

    private static final String AUTH_SCOPE = "openid email profile";
    private static final String CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar";
    private static final long OAUTH_STATE_TTL_SECONDS = 600;

    private final GoogleProperties googleProps;
    private final JwtHelper jwtHelper;
    private final JwtProperties jwtProperties;
    private final RestClient restClient;
    private final GoogleTokenRepository googleTokenRepository;
    private final String authServiceUrl;
    private final String frontendUrl;

    public GoogleAuthService(
            GoogleProperties googleProps,
            JwtHelper jwtHelper,
            JwtProperties jwtProperties,
            RestClient restClient,
            GoogleTokenRepository googleTokenRepository,
            @Value("${app.auth-service-url}") String authServiceUrl,
            @Value("${app.frontend-url}") String frontendUrl
    ) {
        this.googleProps = googleProps;
        this.jwtHelper = jwtHelper;
        this.jwtProperties = jwtProperties;
        this.restClient = restClient;
        this.googleTokenRepository = googleTokenRepository;
        this.authServiceUrl = authServiceUrl;
        this.frontendUrl = frontendUrl;
    }

    // ─── Login OAuth flow ────────────────────────────────────────────────────

    public String buildAuthorizationUrl() {
        String state = generateStateToken("login", null);

        return UriComponentsBuilder.fromHttpUrl(googleProps.getAuthUri())
                .queryParam("client_id", googleProps.getClientId())
                .queryParam("redirect_uri", googleProps.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", URLEncoder.encode(AUTH_SCOPE, StandardCharsets.UTF_8))
                .queryParam("state", state)
                .queryParam("access_type", "offline")
                .build(true)
                .toUriString();
    }

    public String handleCallback(String code, String state) {
        log.info("Auth callback: state={}, code_prefix={}", state,
                code != null && code.length() > 8 ? code.substring(0, 8) : code);

        Claims claims = parseStateToken(state);
        String flow = claims.get("flow", String.class);
        if (!"login".equals(flow)) {
            throw new ServiceException("Invalid OAuth state flow", HttpStatus.BAD_REQUEST);
        }

        GoogleTokenResponse googleTokens = exchangeCodeForTokens(code, googleProps.getRedirectUri());
        GoogleUserInfo userInfo = fetchUserInfo(googleTokens.accessToken());

        if (!userInfo.emailVerified()) {
            throw new ServiceException("Google account email is not verified", HttpStatus.BAD_REQUEST);
        }

        AuthUserResponse user = findOrCreateUser(userInfo.sub(), userInfo.email());

        // Issue tokens via auth-service so the refresh token JTI is stored in Redis
        // (direct jwtHelper generation bypasses Redis and breaks /auth/refresh)
        AppTokenResponse appTokens = issueTokensViaAuthService(user.id());

        return UriComponentsBuilder.fromHttpUrl(frontendUrl)
                .fragment("access_token=" + appTokens.accessToken()
                        + "&refresh_token=" + appTokens.refreshToken()
                        + "&expires_in=" + appTokens.expiresIn())
                .build(true)
                .toUriString();
    }

    // ─── Calendar OAuth flow ─────────────────────────────────────────────────

    public String buildCalendarConnectUrl(String authorizationHeader) {
        UUID userId = jwtHelper.extractCallerInfo(authorizationHeader).id();
        String state = generateStateToken("calendar", userId);

        return UriComponentsBuilder.fromHttpUrl(googleProps.getAuthUri())
                .queryParam("client_id", googleProps.getClientId())
                .queryParam("redirect_uri", googleProps.getCalendarRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", URLEncoder.encode(CALENDAR_SCOPE, StandardCharsets.UTF_8))
                .queryParam("state", state)
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .build(true)
                .toUriString();
    }

    @Transactional
    public String handleCalendarCallback(String code, String state) {
        Claims claims = parseStateToken(state);
        String flow = claims.get("flow", String.class);
        if (!"calendar".equals(flow)) {
            throw new ServiceException("Invalid OAuth state flow", HttpStatus.BAD_REQUEST);
        }
        String userIdValue = claims.get("userId", String.class);
        if (userIdValue == null || userIdValue.isBlank()) {
            throw new ServiceException("Invalid OAuth state payload", HttpStatus.BAD_REQUEST);
        }
        UUID userId = UUID.fromString(userIdValue);

        GoogleTokenResponse tokens = exchangeCodeForTokens(code, googleProps.getCalendarRedirectUri());

        Instant expiresAt = Instant.now().plusSeconds(tokens.expiresIn() - 30);

        GoogleToken googleToken = googleTokenRepository.findByUserId(userId)
                .orElse(new GoogleToken(userId, tokens.accessToken(), tokens.refreshToken(), expiresAt));

        googleToken.setAccessToken(tokens.accessToken());
        if (tokens.refreshToken() != null) {
            googleToken.setRefreshToken(tokens.refreshToken());
        }
        googleToken.setExpiresAt(expiresAt);
        googleTokenRepository.save(googleToken);

        return UriComponentsBuilder.fromHttpUrl(frontendUrl)
                .fragment("calendar_connected=true")
                .build(true)
                .toUriString();
    }

    public boolean isCalendarConnected(String authorizationHeader) {
        UUID userId = jwtHelper.extractCallerInfo(authorizationHeader).id();
        return googleTokenRepository.findByUserId(userId).isPresent();
    }

    // ─── Shared helpers ───────────────────────────────────────────────────────

    GoogleTokenResponse exchangeCodeForTokens(String code, String redirectUri) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code", code);
        body.add("client_id", googleProps.getClientId());
        body.add("client_secret", googleProps.getClientSecret());
        body.add("redirect_uri", redirectUri);
        body.add("grant_type", "authorization_code");

        try {
            return restClient.post()
                    .uri(googleProps.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(GoogleTokenResponse.class);
        } catch (RestClientResponseException e) {
            log.error("Google token exchange failed: {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ServiceException("Failed to exchange Google authorization code: " + e.getResponseBodyAsString(), HttpStatus.BAD_GATEWAY);
        } catch (Exception e) {
            log.error("Google token exchange unexpected error: {}", e.getMessage(), e);
            throw new ServiceException("Failed to exchange Google authorization code", HttpStatus.BAD_GATEWAY);
        }
    }

    GoogleTokenResponse refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("refresh_token", refreshToken);
        body.add("client_id", googleProps.getClientId());
        body.add("client_secret", googleProps.getClientSecret());
        body.add("grant_type", "refresh_token");

        try {
            return restClient.post()
                    .uri(googleProps.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(GoogleTokenResponse.class);
        } catch (RestClientResponseException e) {
            log.error("Google token refresh failed: {}", e.getResponseBodyAsString());
            throw new ServiceException("Failed to refresh Google access token", HttpStatus.BAD_GATEWAY);
        }
    }

    private GoogleUserInfo fetchUserInfo(String accessToken) {
        try {
            return restClient.get()
                    .uri(googleProps.getUserinfoUri())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(GoogleUserInfo.class);
        } catch (RestClientResponseException e) {
            log.error("Google userinfo failed: {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ServiceException("Failed to fetch Google user info", HttpStatus.BAD_GATEWAY);
        } catch (Exception e) {
            log.error("Google userinfo unexpected error: {}", e.getMessage(), e);
            throw new ServiceException("Failed to fetch Google user info", HttpStatus.BAD_GATEWAY);
        }
    }

    private AuthUserResponse findOrCreateUser(String googleId, String email) {
        String primary = authServiceUrl + "/internal/google-auth";
        String fallback = "http://auth-service:8080/internal/google-auth";
        try {
            return restClient.post()
                    .uri(primary)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new GoogleAuthInternalRequest(googleId, email))
                    .retrieve()
                    .body(AuthUserResponse.class);
        } catch (RestClientResponseException e) {
            log.error("Auth service google-auth failed: url={}, status={}, body={}", primary, e.getStatusCode(), e.getResponseBodyAsString());
            if (!fallback.startsWith(authServiceUrl)) {
                try {
                    return restClient.post()
                            .uri(fallback)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(new GoogleAuthInternalRequest(googleId, email))
                            .retrieve()
                            .body(AuthUserResponse.class);
                } catch (RestClientResponseException ex) {
                    log.error("Auth service google-auth fallback failed: url={}, status={}, body={}", fallback, ex.getStatusCode(), ex.getResponseBodyAsString());
                } catch (Exception ex) {
                    log.error("Auth service google-auth fallback failed: url={}, message={}", fallback, ex.getMessage());
                }
            }
            throw new ServiceException("Failed to resolve user in auth service", HttpStatus.BAD_GATEWAY);
        } catch (Exception e) {
            log.error("Auth service google-auth failed: url={}, message={}", primary, e.getMessage());
            if (!fallback.startsWith(authServiceUrl)) {
                try {
                    return restClient.post()
                            .uri(fallback)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(new GoogleAuthInternalRequest(googleId, email))
                            .retrieve()
                            .body(AuthUserResponse.class);
                } catch (Exception ex) {
                    log.error("Auth service google-auth fallback failed: url={}, message={}", fallback, ex.getMessage());
                }
            }
            throw new ServiceException("Failed to resolve user in auth service", HttpStatus.BAD_GATEWAY);
        }
    }

    private AppTokenResponse issueTokensViaAuthService(java.util.UUID userId) {
        String primary = authServiceUrl + "/internal/issue-tokens";
        String fallback = "http://auth-service:8080/internal/issue-tokens";
        try {
            return restClient.post()
                    .uri(primary)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("userId", userId.toString()))
                    .retrieve()
                    .body(AppTokenResponse.class);
        } catch (RestClientResponseException e) {
            log.error("Auth service issue-tokens failed: url={}, status={}, body={}", primary, e.getStatusCode(), e.getResponseBodyAsString());
            if (!fallback.startsWith(authServiceUrl)) {
                try {
                    return restClient.post()
                            .uri(fallback)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("userId", userId.toString()))
                            .retrieve()
                            .body(AppTokenResponse.class);
                } catch (RestClientResponseException ex) {
                    log.error("Auth service issue-tokens fallback failed: url={}, status={}, body={}", fallback, ex.getStatusCode(), ex.getResponseBodyAsString());
                } catch (Exception ex) {
                    log.error("Auth service issue-tokens fallback failed: url={}, message={}", fallback, ex.getMessage());
                }
            }
            throw new ServiceException("Failed to issue tokens", HttpStatus.BAD_GATEWAY);
        } catch (Exception e) {
            log.error("Auth service issue-tokens failed: url={}, message={}", primary, e.getMessage());
            if (!fallback.startsWith(authServiceUrl)) {
                try {
                    return restClient.post()
                            .uri(fallback)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("userId", userId.toString()))
                            .retrieve()
                            .body(AppTokenResponse.class);
                } catch (Exception ex) {
                    log.error("Auth service issue-tokens fallback failed: url={}, message={}", fallback, ex.getMessage());
                }
            }
            throw new ServiceException("Failed to issue tokens", HttpStatus.BAD_GATEWAY);
        }
    }

    private String generateStateToken(String flow, UUID userId) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder()
                .claim("flow", flow)
                .issuedAt(new java.util.Date(now))
                .expiration(new java.util.Date(now + OAUTH_STATE_TTL_SECONDS * 1000))
                .signWith(jwtHelper.getKey());
        if (userId != null) {
            builder.claim("userId", userId.toString());
        }
        return builder.compact();
    }

    private Claims parseStateToken(String state) {
        try {
            return Jwts.parser()
                    .verifyWith(jwtHelper.getKey())
                    .build()
                    .parseSignedClaims(state)
                    .getPayload();
        } catch (Exception e) {
            throw new ServiceException("Invalid or expired OAuth state", HttpStatus.BAD_REQUEST);
        }
    }
}
