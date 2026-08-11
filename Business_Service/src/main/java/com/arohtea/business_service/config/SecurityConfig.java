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

/**
 * 单管理员会话认证、CSRF、退出和会话固定攻击防护配置。
 *
 * <p>本系统面向单个管理员，不在数据库维护用户表，而是从部署 Secret 中读取一个
 * 用户名和 BCrypt 哈希。登录成功后使用服务端 Session 保存身份；浏览器仍需通过
 * CSRF Token 证明写请求来自已加载的前端页面。</p>
 */
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
            @Value("${security.admin.username}") String adminUsername,
            @Value("${security.admin.password-hash}") String adminPasswordHash) {
        this.adminUsername = adminUsername;
        this.adminPasswordHash = stripOptionalQuotes(adminPasswordHash);
    }

    /**
     * 去掉密码哈希配置可能存在的包裹引号。
     *
     * @param value 原始环境变量值
     * @return 清理后的值
     */
    private String stripOptionalQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        return (first == last && (first == '\'' || first == '"'))
                ? value.substring(1, value.length() - 1)
                : value;
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
     * <p>用户名不匹配或哈希为空时统一抛出“凭据无效”，不分别透露是用户名还是
     * 密码配置错误。</p>
     *
     * @return 管理员用户信息服务
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            // 不暴露管理员是否存在，错误输入统一走同一条认证失败路径。
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
     * <p>健康检查和登录/CSRF 初始化接口允许匿名访问，其余 API 都需要 Session；
     * 禁用表单登录和 Basic 是因为前端使用 JSON 登录接口，而不是浏览器默认登录页。</p>
     *
     * <p>会话固定防护会在登录时迁移 Session，单会话限制则保证同一管理员不会同时
     * 保持多个有效后台会话。</p>
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
        // 未登录 API 返回 401 JSON/HTTP 状态语义，不重定向到不存在的登录页面。
        AuthenticationEntryPoint unauthorized = (request, response, exception) ->
                response.sendError(HttpStatus.UNAUTHORIZED.value(), "未登录");

        // Cookie 中的 CSRF Token 允许前端读取；请求处理器仍会校验 Header 中的 Token。
        http
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .authorizeHttpRequests(auth -> auth
                        // 这些入口在建立会话前必须可访问，其余请求默认要求管理员身份。
                        .requestMatchers("/health", "/api/auth/login", "/api/auth/csrf", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout
                        // 登出由前端显式调用，成功后只返回 204，不返回额外敏感信息。
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpStatus.NO_CONTENT.value())))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(unauthorized))
                .sessionManagement(session -> session
                        // 登录后迁移 Session ID，阻断攻击者预先固定的会话标识。
                        .sessionFixation(fixation -> fixation.migrateSession())
                        // 单管理员只保留一个会话，减少遗留浏览器继续操作后台的风险。
                        .maximumSessions(1));
        return http.build();
    }

    private static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        /**
         * 为 SPA 写入可由前端读取的 CSRF Cookie，同时保留请求属性处理。
         *
         * @param request 当前请求
         * @param response 当前响应
         * @param csrfToken 延迟生成 CSRF Token 的供应器
         */
        @Override
        public void handle(
                HttpServletRequest request,
                HttpServletResponse response,
                Supplier<CsrfToken> csrfToken) {
            // 先按 SPA 规则处理请求属性，再主动获取 Token，触发 Cookie 写入浏览器。
            xor.handle(request, response, csrfToken);
            csrfToken.get();
        }

        /**
         * 优先读取前端 Header 中的明文 Token；缺失时使用 Spring 默认 XOR 值。
         *
         * @param request 当前请求
         * @param csrfToken 当前 CSRF Token
         * @return 请求携带的 CSRF Token 值
         */
        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            // Axios 等前端通常发送明文 Header；未发送时沿用 Spring 的 XOR 解析规则。
            String headerValue = request.getHeader(csrfToken.getHeaderName());
            return (StringUtils.hasText(headerValue) ? plain : xor)
                    .resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
