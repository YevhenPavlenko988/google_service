package com.github.yevhen.googleservice.service;

import com.github.yevhen.common.config.JwtProperties;
import com.github.yevhen.common.exception.ServiceException;
import com.github.yevhen.common.security.JwtHelper;
import com.github.yevhen.googleservice.config.GoogleProperties;
import com.github.yevhen.googleservice.dto.AuthUserResponse;
import com.github.yevhen.googleservice.dto.GoogleAuthInternalRequest;
import com.github.yevhen.googleservice.dto.GoogleTokenResponse;
import com.github.yevhen.googleservice.dto.GoogleUserInfo;
import com.github.yevhen.googleservice.model.GoogleToken;
import com.github.yevhen.googleservice.repository.GoogleTokenRepository;
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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GoogleAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);

    private static final String AUTH_SCOPE = "openid email profile";
    private static final String CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar";

    /** Login flow: state → Boolean */
    private final Map<String, Boolean> pendingLoginStates = new ConcurrentHashMap<>();

    /** Calendar connect flow: state → userId */
    private final Map<String, UUID> pendingCalendarStates = new ConcurrentHashMap<>();

    private final GoogleProperties googleProps;
    private final JwtHelper jwtHelper;
    private final JwtProperties jwtProperties;
    private final RestClient restClient;
    private final GoogleTokenRepository googleTokenRepository;
    private final String authServiceUrl;
    private final String frontendUrl;
    private final SecureRandom secureRandom = new SecureRandom();

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
        String state = generateState();
        pendingLoginStates.put(state, true);

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

        if (!pendingLoginStates.remove(state, true)) {
            throw new ServiceException("Invalid or expired OAuth state", HttpStatus.BAD_REQUEST);
        }

        GoogleTokenResponse googleTokens = exchangeCodeForTokens(code, googleProps.getRedirectUri());
        GoogleUserInfo userInfo = fetchUserInfo(googleTokens.accessToken());

        if (!userInfo.emailVerified()) {
            throw new ServiceException("Google account email is not verified", HttpStatus.BAD_REQUEST);
        }

        AuthUserResponse user = findOrCreateUser(userInfo.sub(), userInfo.email());

        String accessToken = jwtHelper.generateAccessToken(
                user.id(), user.email(), user.role(), jwtProperties.getAccessExpiration());
        String refreshToken = jwtHelper.generateRefreshToken(
                user.id(), jwtProperties.getRefreshExpiration());
        long expiresIn = jwtProperties.getAccessExpiration();

        return UriComponentsBuilder.fromHttpUrl(frontendUrl)
                .fragment("access_token=" + accessToken
                        + "&refresh_token=" + refreshToken
                        + "&expires_in=" + expiresIn)
                .build(true)
                .toUriString();
    }

    // ─── Calendar OAuth flow ─────────────────────────────────────────────────

    public String buildCalendarConnectUrl(String authorizationHeader) {
        UUID userId = jwtHelper.extractCallerInfo(authorizationHeader).id();
        String state = generateState();
        pendingCalendarStates.put(state, userId);

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
        UUID userId = pendingCalendarStates.remove(state);
        if (userId == null) {
            throw new ServiceException("Invalid or expired OAuth state", HttpStatus.BAD_REQUEST);
        }

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
        try {
            return restClient.post()
                    .uri(authServiceUrl + "/internal/google-auth")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new GoogleAuthInternalRequest(googleId, email))
                    .retrieve()
                    .body(AuthUserResponse.class);
        } catch (Exception e) {
            throw new ServiceException("Failed to resolve user in auth service", HttpStatus.BAD_GATEWAY);
        }
    }

    private String generateState() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
