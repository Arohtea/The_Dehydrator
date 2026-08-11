package com.arohtea.business_service.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理员会话、CSRF Token 和当前身份接口。
 *
 * <p>登录成功后身份保存在服务端 Session，前端不需要保存 JWT；写请求还必须携带
 * 与当前 Cookie 对应的 CSRF Token。这里不返回密码、哈希或任何可用于再次认证的凭据。</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    /**
     * 返回当前 CSRF Token，并触发 CookieCsrfTokenRepository 写入浏览器 Cookie。
     *
     * @param token 当前请求关联的 CSRF Token
     * @return 可供前端调试和非 Axios 客户端使用的 Token
     */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    /**
     * 使用管理员凭据创建服务端会话。
     *
     * @param request 登录请求
     * @param servletRequest 当前 HTTP 请求
     * @param servletResponse 当前 HTTP 响应
     * @return 登录成功后的管理员身份
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        try {
            // AuthenticationManager 负责 BCrypt 校验，控制器只负责把 JSON 请求转换为认证对象。
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.username(), request.password()));
            // 登录前销毁旧 Session，避免同一浏览器沿用不确定的历史会话状态。
            HttpSession existingSession = servletRequest.getSession(false);
            if (existingSession != null) {
                existingSession.invalidate();
            }
            // 把认证结果写入新的 SecurityContext，并交给 Session 仓库持久化。
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, servletRequest, servletResponse);
            return ResponseEntity.ok(Map.of("authenticated", true, "username", authentication.getName()));
        } catch (AuthenticationException exception) {
            // 对外统一返回凭据错误，不区分用户名不存在还是密码错误。
            SecurityContextHolder.clearContext();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "用户名或密码错误"));
        }
    }

    /**
     * 返回当前会话是否有效。
     *
     * @param authentication 当前认证信息
     * @return 当前管理员身份
     */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        return Map.of("authenticated", true, "username", authentication.getName());
    }

    /**
     * 管理员登录请求。
     *
     * @param username 管理员用户名
     * @param password 管理员密码
     */
    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {
    }
}
