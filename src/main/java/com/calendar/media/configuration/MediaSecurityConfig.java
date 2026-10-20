package com.calendar.media.configuration;

import com.calendar.media.application.security.InternalSecretAuthenticationConverter;
import com.calendar.media.application.security.MediaClientAuthenticationManager;
import com.calendar.media.application.security.MediaClientAuthenticationManager.RegisteredClient;
import com.calendar.media.application.security.ProblemDetailAuthenticationEntryPoint;
import com.calendar.media.domain.models.MediaClient;
import com.calendar.media.exception.ProblemDetailWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;

/**
 * Callers authenticate with a per-service shared secret in {@code X-Internal-Secret}; the matched
 * {@link MediaClient} becomes the principal. Anything else answers 401 MED-REQ-001.
 */
@Configuration
@EnableWebFluxSecurity
public class MediaSecurityConfig {

    @Bean
    public MediaClientAuthenticationManager mediaClientAuthenticationManager(MediaClientsProperties properties) {
        return new MediaClientAuthenticationManager(properties.clients().entrySet().stream()
                .map(entry -> new RegisteredClient(
                        new MediaClient(entry.getKey(), entry.getValue().prefix(), entry.getValue().bucket()),
                        entry.getValue().secret()))
                .toList());
    }

    @Bean
    public ProblemDetailAuthenticationEntryPoint problemDetailAuthenticationEntryPoint(ProblemDetailWriter writer) {
        return new ProblemDetailAuthenticationEntryPoint(writer);
    }

    @Bean
    public SecurityWebFilterChain internalHttpSecurity(ServerHttpSecurity http,
                                                       MediaClientAuthenticationManager authenticationManager,
                                                       ProblemDetailAuthenticationEntryPoint entryPoint) {
        AuthenticationWebFilter secretFilter = new AuthenticationWebFilter(authenticationManager);
        secretFilter.setServerAuthenticationConverter(new InternalSecretAuthenticationConverter());
        secretFilter.setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(entryPoint));

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(authorize -> authorize
                        // Kubernetes probes: the kubelet carries no secret. Only the two
                        // health groups are opened, not /actuator as a whole.
                        .pathMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        // Metrics scrape. The Service is ClusterIP only and no gateway route reaches
                        // this service at all, so only in-cluster callers get here; the metrics hold
                        // no user data. Only this endpoint, and only GET.
                        .pathMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        // OpenAPI. No gateway route reaches this service, so opening these
                        // paths makes the documentation reachable in-cluster and in dev only.
                        .pathMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/webjars/swagger-ui/**").permitAll()
                        .anyExchange().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .addFilterAt(secretFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
