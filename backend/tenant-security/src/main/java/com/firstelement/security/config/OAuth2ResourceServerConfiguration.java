package com.firstelement.security.config;

import com.firstelement.security.oauth2.IntegrationJwtAuthenticationConverter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The optional OAuth2 resource-server path for trusted integrations (FR-2.6).
 *
 * <h2>Off by default, and separate on purpose</h2>
 *
 * <p>Requires {@code firstelement.security.oauth2.enabled=true} <b>and</b> a configured
 * {@link JwtDecoder} (a {@code jwk-set-uri} or an {@code issuer-uri} under
 * {@code spring.security.oauth2.resourceserver.jwt.*}). With either missing nothing here
 * is registered, so an ordinary browser deployment never has a second credential path.
 *
 * <h2>Why a second filter chain at order 1</h2>
 *
 * <p>Spring Security evaluates chains in order and the first matching
 * {@code securityMatcher} wins. Order 1 puts this chain ahead of the browser chain
 * (default {@code -100}), so an integration path can never fall through to cookie
 * authentication and a browser path never sees a bearer token. FR-2.6 requires the two
 * credential paths to remain separate; sharing one chain is how that requirement usually
 * quietly stops being true.
 *
 * <p>{@code integrationPathsOnly} (the default) restricts bearer tokens to the configured
 * path prefixes. Turning it off would let every endpoint accept a JWT, which is not an
 * approved configuration for this release.
 *
 * <h2>CSRF stays on</h2>
 *
 * <p>A bearer token is not ambient authority — a browser does not attach it
 * automatically — so the framework's "disable CSRF for stateless APIs" advice does not
 * apply and is deliberately not followed.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "firstelement.security.oauth2", name = "enabled", havingValue = "true")
public class OAuth2ResourceServerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OAuth2ResourceServerConfiguration.class);

    private static final JwtGrantedAuthoritiesConverter SCOPE_AUTHORITIES =
        new JwtGrantedAuthoritiesConverter();

    @Bean
    @Order(1)
    @ConditionalOnBean(JwtDecoder.class)
    SecurityFilterChain integrationResourceServerChain(HttpSecurity http,
                                                      JwtDecoder jwtDecoder,
                                                      SecurityProperties security) throws Exception {
        SecurityProperties.Oauth2 oauth2 = security.getOauth2();
        if (oauth2.isIntegrationPathsOnly()) {
            String[] matchers = oauth2.integrationPathPrefixes().toArray(String[]::new);
            http.securityMatcher(matchers);
            log.info("OAuth2 resource server enabled for integration paths only: {}", List.of(matchers));
        } else {
            log.warn("firstelement.security.oauth2.integration-paths-only=false: bearer tokens are "
                + "accepted on every path. Not an approved configuration for this release.");
        }

        IntegrationJwtAuthenticationConverter converter =
            new IntegrationJwtAuthenticationConverter(oauth2);

        http
            .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
            .oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(token -> converter.convert(token, authorities(token)))));

        return http.build();
    }

    private List<org.springframework.security.core.GrantedAuthority> authorities(Jwt jwt) {
        return List.copyOf(SCOPE_AUTHORITIES.convert(jwt));
    }
}