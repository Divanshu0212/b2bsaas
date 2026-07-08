package com.salespipe.identity.api;

import com.salespipe.identity.api.dto.*;
import com.salespipe.identity.domain.Organization;
import com.salespipe.identity.domain.Role;
import com.salespipe.identity.domain.User;
import com.salespipe.identity.infra.*;
import com.salespipe.pipeline.domain.DealStageSeeder;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtProvider jwt;
    private final RefreshTokenService refreshTokens;
    private final DealStageSeeder stageSeeder;

    public AuthController(OrganizationRepository orgs, UserRepository users,
                          PasswordEncoder encoder, JwtProvider jwt,
                          RefreshTokenService refreshTokens, DealStageSeeder stageSeeder) {
        this.orgs = orgs; this.users = users; this.encoder = encoder;
        this.jwt = jwt; this.refreshTokens = refreshTokens; this.stageSeeder = stageSeeder;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest req) {
        Organization org = new Organization(UUID.randomUUID(), req.orgName());
        orgs.save(org);
        stageSeeder.seedDefaults(org.getId());
        User admin = new User(UUID.randomUUID(), org.getId(), req.email(),
            encoder.encode(req.password()), Role.ADMIN);
        users.save(admin);
        return tokens(admin);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        User user = users.findByEmail(req.email())
            .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
            .orElseThrow(BadCredentials::new);
        return tokens(user);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        var result = refreshTokens.rotate(req.refreshToken());
        String access = jwt.createAccessToken(result.user().getId(),
            result.user().getOrgId(), result.user().getRole());
        return new TokenResponse(access, result.rawToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest req) {
        refreshTokens.revokeFamily(req.refreshToken());
    }

    private TokenResponse tokens(User user) {
        String access = jwt.createAccessToken(user.getId(), user.getOrgId(), user.getRole());
        var issued = refreshTokens.issue(user);
        return new TokenResponse(access, issued.rawToken());
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    static class BadCredentials extends RuntimeException {}
}
