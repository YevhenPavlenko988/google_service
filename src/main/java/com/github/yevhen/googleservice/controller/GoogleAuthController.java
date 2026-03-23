package com.github.yevhen.googleservice.controller;

import com.github.yevhen.googleservice.service.GoogleAuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/google")
public class GoogleAuthController {

    private final GoogleAuthService googleAuthService;

    public GoogleAuthController(GoogleAuthService googleAuthService) {
        this.googleAuthService = googleAuthService;
    }

    @GetMapping("/auth")
    public ResponseEntity<Map<String, String>> initiateAuth() {
        return ResponseEntity.ok(Map.of("url", googleAuthService.buildAuthorizationUrl()));
    }

    @GetMapping("/auth/callback")
    public ResponseEntity<Void> callback(@RequestParam String code, @RequestParam String state) {
        String redirectUrl = googleAuthService.handleCallback(code, state);
        return ResponseEntity.status(302).location(URI.create(redirectUrl)).build();
    }

    /** Returns Google Calendar OAuth URL. Requires a valid JWT (user must be logged in). */
    @GetMapping("/calendar/connect")
    public ResponseEntity<Map<String, String>> connectCalendar(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader
    ) {
        String url = googleAuthService.buildCalendarConnectUrl(authHeader);
        return ResponseEntity.ok(Map.of("url", url));
    }

    /** Public callback from Google after calendar permission granted. */
    @GetMapping("/calendar/callback")
    public ResponseEntity<Void> calendarCallback(@RequestParam String code, @RequestParam String state) {
        String redirectUrl = googleAuthService.handleCalendarCallback(code, state);
        return ResponseEntity.status(302).location(URI.create(redirectUrl)).build();
    }

    /** Check whether the current user has connected their Google Calendar. */
    @GetMapping("/calendar/status")
    public ResponseEntity<Map<String, Boolean>> calendarStatus(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader
    ) {
        boolean connected = googleAuthService.isCalendarConnected(authHeader);
        return ResponseEntity.ok(Map.of("connected", connected));
    }
}
