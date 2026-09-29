package com.ledger.api;

import com.nimbusds.jose.JOSEObjectType;
import jakarta.servlet.DispatcherType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what. The scope each endpoint needs is declared here, in one table, and everything not listed is
 * denied, so a new endpoint is closed until someone decides which scope guards it.
 *
 * <p>Scopes come from the identity service's tokens ({@code scope} claim) as authorities named
 * {@code SCOPE_<scope>}. The write scope is separate from the read scope on purpose: {@code transfers:write}
 * does not let a caller read accounts, and {@code accounts:read} cannot move money.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiKeyAuthFilter apiKeyFilter, ProblemAuthHandlers problems)
            throws Exception {
        http
                // Stateless API: credentials travel in headers, never cookies, so there is no CSRF surface.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**",
                                "/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                        // Boot renders unhandled errors by forwarding to /error. Denying that forward turned every
                        // server error into a misleading 401/403, so let error rendering through.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/accounts", "/accounts/*").hasAuthority("SCOPE_accounts:read")
                        .requestMatchers(HttpMethod.POST, "/accounts").hasAuthority("SCOPE_accounts:write")
                        .requestMatchers(HttpMethod.GET, "/accounts/*/statement").hasAuthority("SCOPE_transfers:read")
                        .requestMatchers(HttpMethod.POST, "/transfers").hasAuthority("SCOPE_transfers:write")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .addFilterBefore(apiKeyFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** The key filter lives inside the security chain; stop Boot also registering it as a plain servlet filter. */
    @Bean
    FilterRegistrationBean<ApiKeyAuthFilter> apiKeyFilterRegistration(ApiKeyAuthFilter filter) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Verifies tokens against the identity service's published keys. Deliberately strict:
     * <ul>
     *   <li>only RS256 is accepted, which rules out {@code alg: none} and HS256-signed-with-the-public-key tricks;</li>
     *   <li>the JOSE {@code typ} must be {@code at+jwt} (RFC 9068), so an ID token cannot be used as an access token;</li>
     *   <li>the issuer must match exactly and the audience must include this API;</li>
     *   <li>expiry is checked (default 60 s clock skew).</li>
     * </ul>
     * Keys are fetched from the JWKS URI on first use and cached; an unknown {@code kid} triggers a (rate-limited)
     * refetch, which is how a key rotation on the identity side reaches this service without a restart.
     */
    @Bean
    JwtDecoder jwtDecoder(JwtProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .jwtProcessorCustomizer(processor -> processor.setJWSTypeVerifier(
                        new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("at+jwt"))))
                .build();

        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(props.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "The token is not intended for this API", null));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.issuer()), audience));
        return decoder;
    }
}
