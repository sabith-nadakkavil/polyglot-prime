package org.techbd.service.http;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.ForwardedHeaderFilter;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@ConfigurationProperties(prefix = "spring.security.oauth2.client.registration.github")
@Profile("!localopen")
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class.getName());

    @Autowired
    private GitHubUserAuthorizationFilter authzFilter;

    @Value("${TECHBD_HUB_PRIME_FHIR_API_BASE_URL:#{null}}")
    private String apiUrl;

    @Value("${TECHBD_HUB_PRIME_FHIR_UI_BASE_URL:#{null}}")
    private String uiUrl;

    @Value("${TECHBD_ALLOWED_ORIGIN:#{null}}")
    private String allowedOriginsString;

    private List<String> allowedOrigins;

    @PostConstruct
    private void init() {
        allowedOrigins = Arrays.asList(allowedOriginsString.split(","));
        LOG.info("Initialized allowed origins: {}", allowedOrigins);
        System.out.println("Initialized allowed origins: " + allowedOrigins);
    }

    @Bean
    public SecurityFilterChain statelessSecurityFilterChain(final HttpSecurity http) throws Exception {
        // Stateless configuration for bundle endpoints
        http
                .securityMatcher(Constant.STATELESS_API_URLS)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()) // Allow all requests
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // Stateless
                .csrf(AbstractHttpConfigurer::disable); // Disable CSRF for stateless APIs

        return http.build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
        // allow authentication for security
        // and turn off CSRF to allow POST methods
        http
                .authorizeHttpRequests(
                        authorize -> authorize
                                .requestMatchers(Constant.UNAUTHENTICATED_URLS)
                                .permitAll()
                                .anyRequest().authenticated())
                .oauth2Login(
                        oauth2Login -> oauth2Login
                                .successHandler(gitHubLoginSuccessHandler())
                                .defaultSuccessUrl(Constant.HOME_PAGE_URL)
                                .loginPage(Constant.LOGIN_PAGE_URL))
                .logout(
                        logout -> logout
                                .deleteCookies(Constant.SESSIONID_COOKIE)
                                .logoutSuccessUrl(Constant.LOGOUT_PAGE_URL)
                                .invalidateHttpSession(true)
                                .permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        sessionManagement -> sessionManagement
                                .invalidSessionUrl(Constant.SESSION_TIMEOUT_URL)
                                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .addFilterAfter(authzFilter, UsernamePasswordAuthenticationFilter.class);
        // allow us to show our own content in IFRAMEs (e.g. Swagger, etc.)
        http.headers(headers -> {
            headers.frameOptions(frameOptions -> frameOptions.sameOrigin());
            headers.httpStrictTransportSecurity(
                    hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(Constant.HSTS_MAX_AGE)); // Enable HSTS
        });
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        if (!allowedOriginsString.isEmpty()) {
            Arrays.asList(allowedOriginsString.split(",")).forEach(config::addAllowedOriginPattern);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        config.addAllowedHeader("*");
        config.addExposedHeader("Location");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
        return new CorsFilter(corsConfigurationSource);
    }


    @Bean
    ForwardedHeaderFilter forwardedHeaderFilter() {
        return new ForwardedHeaderFilter();
    }

    @Bean
    public AuthenticationSuccessHandler gitHubLoginSuccessHandler() {
        return new GitHubLoginSuccessHandler();
    }

    private static class GitHubLoginSuccessHandler implements AuthenticationSuccessHandler {

        private final RequestCache requestCache = new HttpSessionRequestCache();

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request,
                HttpServletResponse response, Authentication authentication)
                throws IOException, jakarta.servlet.ServletException {
            final var savedRequest = requestCache.getRequest(request, response);

            if (savedRequest == null) {
                response.sendRedirect(Constant.HOME_PAGE_URL);
                return;
            }

            final var targetUrl = savedRequest.getRedirectUrl();
            response.sendRedirect(targetUrl);
        }
    }

}
