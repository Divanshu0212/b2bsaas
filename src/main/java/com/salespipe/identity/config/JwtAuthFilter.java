package com.salespipe.identity.config;

import com.salespipe.common.tenant.TenantFilter.AuthPrincipal;
import com.salespipe.identity.infra.JwtProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Populates the SecurityContext from a Bearer JWT.
 *
 * <p>Intentionally NOT a {@code @Component}: Spring Boot auto-registers any
 * {@code @Component implements Filter} as a standalone servlet container filter
 * (via {@code FilterRegistrationBean}), which runs outside the Spring Security
 * {@code FilterChainProxy} in addition to any explicit wiring. This filter is
 * instead exposed as a plain {@code @Bean} (see {@link SecurityConfig}) and wired
 * into the security chain via {@code addFilterBefore}, so it runs exactly once,
 * in a deterministic position ahead of {@link TenantFilter}.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtProvider jwt;
    public JwtAuthFilter(JwtProvider jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Jws<Claims> jws = jwt.parse(header.substring(7));
                Claims c = jws.getPayload();
                var principal = new AuthPrincipal(jwt.userId(c), jwt.orgId(c));
                var authority = new SimpleGrantedAuthority("ROLE_" + jwt.role(c).name());
                var auth = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(req, res);
    }
}
