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
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.username(), request.password()));
            HttpSession existingSession = servletRequest.getSession(false);
            if (existingSession != null) {
                existingSession.invalidate();
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, servletRequest, servletResponse);
            return ResponseEntity.ok(Map.of("authenticated", true, "username", authentication.getName()));
        } catch (AuthenticationException exception) {
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

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {
    }
}
