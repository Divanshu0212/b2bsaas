package com.salespipe.identity.api.dto;

/**
 * T6.3: auth response body. Only the short-lived access token is returned to the SPA (held
 * in memory). The refresh token is NOT in the body — it rides an httpOnly cookie set by
 * {@link com.salespipe.identity.api.AuthController}.
 */
public record AccessTokenResponse(String accessToken) {}
