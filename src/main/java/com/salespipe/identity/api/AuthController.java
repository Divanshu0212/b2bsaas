package com.salespipe.identity.api;

import com.salespipe.identity.api.dto.*;
import com.salespipe.identity.config.JwtProperties;
import com.salespipe.identity.domain.Organization;
import com.salespipe.identity.domain.Role;
import com.salespipe.identity.domain.User;
import com.salespipe.identity.infra.*;
import com.salespipe.pipeline.domain.DealStageSeeder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.UUID;

/**
 * Auth API. T6.3 change: the refresh token is delivered as an httpOnly {@code refresh_token}
 * cookie (never in the JSON body, never readable by JS — XSS can't exfiltrate it). Only the
 * short-lived access token is returned in the body for the SPA to hold in memory. {@code
 * /auth/refresh} and {@code /auth/logout} read the cookie, so they take no request body.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    /** httpOnly cookie carrying the refresh token. Path "/" so it survives the frontend's
     *  {@code /api/*} reverse-proxy rewrite (the browser sees origin path {@code
     *  /api/auth/refresh}, which a {@code /auth}-scoped cookie would not match). httpOnly
     *  keeps it out of JS regardless of path. */
    static final String REFRESH_COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/";

    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtProvider jwt;
    private final RefreshTokenService refreshTokens;
    private final DealStageSeeder stageSeeder;
    private final Duration refreshTtl;

    public AuthController(OrganizationRepository orgs, UserRepository users,
                          PasswordEncoder encoder, JwtProvider jwt,
                          RefreshTokenService refreshTokens, DealStageSeeder stageSeeder,
                          JwtProperties jwtProps) {
        this.orgs = orgs; this.users = users; this.encoder = encoder;
        this.jwt = jwt; this.refreshTokens = refreshTokens; this.stageSeeder = stageSeeder;
        this.refreshTtl = Duration.ofSeconds(jwtProps.getRefreshTtlSeconds());
    }

    @PostMapping("/register")
    public ResponseEntity<AccessTokenResponse> register(@Valid @RequestBody RegisterRequest req) {
        Organization org = new Organization(UUID.randomUUID(), req.orgName());
        orgs.save(org);
        stageSeeder.seedDefaults(org.getId());
        User admin = new User(UUID.randomUUID(), org.getId(), req.email(),
            encoder.encode(req.password()), Role.ADMIN);
        users.save(admin);
        return issue(admin, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest req) {
        User user = users.findByEmail(req.email())
            .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
            .orElseThrow(BadCredentials::new);
        return issue(user, HttpStatus.OK);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AccessTokenResponse> refresh(HttpServletRequest request) {
        String raw = readRefreshCookie(request);
        var result = refreshTokens.rotate(raw);
        String access = jwt.createAccessToken(result.user().getId(),
            result.user().getOrgId(), result.user().getRole());
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookie(result.rawToken()).toString())
            .body(new AccessTokenResponse(access));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String raw = readRefreshCookieOrNull(request);
        if (raw != null) {
            refreshTokens.revokeFamily(raw);
        }
        // Expire the cookie regardless (idempotent logout).
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
            .build();
    }

    private ResponseEntity<AccessTokenResponse> issue(User user, HttpStatus status) {
        String access = jwt.createAccessToken(user.getId(), user.getOrgId(), user.getRole());
        var issued = refreshTokens.issue(user);
        return ResponseEntity.status(status)
            .header(HttpHeaders.SET_COOKIE, refreshCookie(issued.rawToken()).toString())
            .body(new AccessTokenResponse(access));
    }

    private ResponseCookie refreshCookie(String raw) {
        // SameSite=Lax (not Strict): Strict drops the cookie on top-level cross-site
        // navigations and breaks refresh across subdomains in prod (T6 risk note). httpOnly
        // + Secure so it's inaccessible to JS and only sent over TLS.
        return ResponseCookie.from(REFRESH_COOKIE, raw)
            .httpOnly(true).secure(true).sameSite("Lax")
            .path(COOKIE_PATH).maxAge(refreshTtl).build();
    }

    private ResponseCookie expiredRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
            .httpOnly(true).secure(true).sameSite("Lax")
            .path(COOKIE_PATH).maxAge(0).build();
    }

    private String readRefreshCookie(HttpServletRequest request) {
        String raw = readRefreshCookieOrNull(request);
        if (raw == null) {
            throw new RefreshTokenService.InvalidTokenException("missing refresh cookie");
        }
        return raw;
    }

    private String readRefreshCookieOrNull(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var c : request.getCookies()) {
            if (REFRESH_COOKIE.equals(c.getName()) && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    static class BadCredentials extends RuntimeException {}
}
