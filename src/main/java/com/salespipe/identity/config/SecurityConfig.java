package com.salespipe.identity.config;

import com.salespipe.common.ratelimit.ApiRateLimitFilter;
import com.salespipe.common.ratelimit.ApiRateLimitProperties;
import com.salespipe.common.ratelimit.ApiRateLimiter;
import com.salespipe.common.tenant.TenantContext;
import com.salespipe.common.tenant.TenantFilter;
import com.salespipe.common.tenant.TenantFilterAspect;
import com.salespipe.identity.infra.JwtProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, ApiRateLimitProperties.class, CorsProperties.class})
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    /**
     * Plain {@code @Bean}, not {@code @Component}: keeps this filter out of Boot's
     * auto {@code FilterRegistrationBean} servlet-container registration so it only
     * runs once, explicitly wired into the security chain below.
     */
    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtProvider jwtProvider) {
        return new JwtAuthFilter(jwtProvider);
    }

    /**
     * Plain {@code @Bean}, not {@code @Component} — same reasoning as {@link #jwtAuthFilter}.
     * Wired after {@link JwtAuthFilter} in the security chain so it always runs once the
     * SecurityContext has been populated.
     */
    @Bean
    public TenantFilter tenantFilter(TenantContext tenantContext, TenantFilterAspect filterAspect) {
        return new TenantFilter(tenantContext, filterAspect);
    }

    /**
     * T4.6 per-tenant API rate-limit filter. Plain {@code @Bean} (not {@code @Component})
     * — same single-registration reasoning as the other chain filters. Wired after
     * {@link TenantFilter} so the tenant is resolved before the quota is checked.
     */
    @Bean
    public ApiRateLimitFilter apiRateLimitFilter(
        ApiRateLimiter apiRateLimiter, TenantContext tenantContext, ApiRateLimitProperties properties
    ) {
        return new ApiRateLimitFilter(apiRateLimiter, tenantContext, properties);
    }

    /**
     * T6.3: CORS source driven by {@code app.cors.allowed-origins}. Credentials enabled so
     * the browser sends the httpOnly refresh cookie on {@code /auth/refresh}; exposes no
     * extra headers (the access token is returned in the JSON body, not a header).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.allowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
            TenantFilter tenantFilter, ApiRateLimitFilter apiRateLimitFilter,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            // T6.3: CORS for the Next.js frontend origin. Credentials on (the refresh
            // token rides an httpOnly cookie), so allowedOrigins must be explicit — a
            // wildcard is illegal with allowCredentials=true.
            .cors(c -> c.configurationSource(corsConfigurationSource))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(reg -> reg
                .requestMatchers("/auth/**", "/swagger-ui/**", "/v3/api-docs/**",
                    "/actuator/health/**", "/actuator/prometheus", "/actuator/info",
                    "/emails/**", "/webhooks/**", "/error").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(tenantFilter, JwtAuthFilter.class)
            // After tenant resolution: enforce the per-tenant API quota before controllers.
            .addFilterAfter(apiRateLimitFilter, TenantFilter.class)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
