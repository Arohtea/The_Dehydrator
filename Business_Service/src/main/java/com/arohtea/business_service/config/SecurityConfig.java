package com.arohtea.business_service.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final String adminUsername;
    private final String adminPasswordHash;

    /**
     * 创建单租户管理员安全配置。
     *
     * @param adminUsername 管理员用户名
     * @param adminPasswordHash 管理员 BCrypt 密码哈希
     */
    public SecurityConfig(
            @Value("${security.admin.username:admin}") String adminUsername,
            @Value("${security.admin.password-hash:}") String adminPasswordHash) {
        this.adminUsername = adminUsername;
        this.adminPasswordHash = adminPasswordHash;
    }

    /**
     * 提供 BCrypt 密码校验器。
     *
     * @return 密码校验器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 提供唯一管理员的用户信息查询。
     *
     * @return 管理员用户信息服务
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            if (!StringUtils.hasText(adminPasswordHash) || !adminUsername.equals(username)) {
                throw new UsernameNotFoundException("管理员凭据无效");
            }
            return User.withUsername(adminUsername)
                    .password(adminPasswordHash)
                    .roles("ADMIN")
                    .build();
        };
    }

    /**
     * 组装基于管理员密码的认证提供者。
     *
     * @param userDetailsService 管理员用户信息服务
     * @param passwordEncoder BCrypt 密码校验器
     * @return 认证提供者
     */
    @Bean
    public AuthenticationProvider authenticationProvider(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    /**
     * 创建控制器使用的认证管理器。
     *
     * @param provider 管理员认证提供者
     * @return 认证管理器
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    /**
     * 将认证状态保存在服务端 HTTP Session 中。
     *
     * @return 会话安全上下文仓库
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 配置 API 认证、CSRF、退出和会话 Cookie 边界。
     *
     * @param http Spring Security HTTP 配置器
     * @param securityContextRepository 会话安全上下文仓库
     * @return 安全过滤器链
     * @throws Exception 安全过滤器链构建失败
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) ->
                response.sendError(HttpStatus.UNAUTHORIZED.value(), "未登录");

        http
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health", "/api/auth/login", "/api/auth/csrf", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpStatus.NO_CONTENT.value())))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(unauthorized))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.migrateSession())
                        .maximumSessions(1));
        return http.build();
    }

    private static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(
                HttpServletRequest request,
                HttpServletResponse response,
                Supplier<CsrfToken> csrfToken) {
            xor.handle(request, response, csrfToken);
            csrfToken.get();
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String headerValue = request.getHeader(csrfToken.getHeaderName());
            return (StringUtils.hasText(headerValue) ? plain : xor)
                    .resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
