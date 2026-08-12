package com.travel.planning.controller;

import com.travel.common.result.R;
import com.travel.planning.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /**
     * 注册
     */
    @PostMapping("/register")
    public R<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String email = body.get("email");
        return R.ok(userService.register(username, password, email));
    }

    /**
     * 登录
     */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        return R.ok(userService.login(username, password));
    }

    /**
     * 刷新 Token
     *
     * <p>客户端 accessToken 过期后，使用 refreshToken 获取新的 Token 对</p>
     */
    @PostMapping("/refresh")
    public R<Map<String, Object>> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        return R.ok(userService.refreshToken(refreshToken));
    }

    /**
     * 退出登录
     *
     * <p>注销 Redis 中的 refreshToken</p>
     */
    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader("X-User-Id") Long userId) {
        userService.logout(userId);
        return R.ok(null);
    }
}
