package com.salespipe.identity.config;

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

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
            TenantFilter tenantFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(reg -> reg
                .requestMatchers("/auth/**", "/swagger-ui/**", "/v3/api-docs/**",
                    "/actuator/health/**", "/emails/**", "/error").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(tenantFilter, JwtAuthFilter.class)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
