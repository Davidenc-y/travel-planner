package com.travel.planning.service;

import com.travel.common.entity.User;
import com.travel.common.exception.BusinessException;
import com.travel.planning.repository.UserMapper;
import com.travel.planning.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务
 *
 * <p>M2-4-F18 增强：refreshToken 存入 Redis，支持 Token 刷新</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private static final String REFRESH_TOKEN_KEY = "refresh_token:";
    private static final long REFRESH_TOKEN_TTL_DAYS = 7;

    /**
     * 注册（注册即登录，返回 Token 对）
     */
    public Map<String, Object> register(String username, String password, String email) {
        if (userMapper.findByUsername(username) != null) {
            throw new BusinessException(40003, "用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setEmail(email);
        userMapper.insert(user);
        log.info("用户注册成功: id={}, username={}", user.getId(), username);
        return generateTokenPair(user);
    }

    /**
     * 登录
     */
    public Map<String, Object> login(String username, String password) {
        User user = userMapper.findByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(40101, "用户名或密码错误");
        }
        log.info("用户登录成功: id={}, username={}", user.getId(), username);
        return generateTokenPair(user);
    }

    /**
     * 根据 ID 查询用户
     */
    public User findById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(40403, "用户不存在");
        }
        return user;
    }

    /**
     * 刷新 Token
     *
     * <p>验证 refreshToken 是否在 Redis 中有效，签发新的 accessToken + refreshToken</p>
     *
     * @param refreshToken 客户端传入的 refreshToken
     * @return 新的 Token 对
     */
    public Map<String, Object> refreshToken(String refreshToken) {
        // 1. 验证 JWT 格式
        if (!jwtUtil.validateToken(refreshToken)) {
            throw new BusinessException(40102, "refreshToken 无效或已过期");
        }

        // 2. 提取 userId
        Long userId = jwtUtil.getUserIdFromToken(refreshToken);
        String username = jwtUtil.getUsernameFromToken(refreshToken);

        // 3. 验证 Redis 中是否存在（防止已注销的 Token 被使用）
        String redisKey = REFRESH_TOKEN_KEY + userId;
        String storedToken = redisTemplate.opsForValue().get(redisKey);
        if (storedToken == null || !refreshToken.equals(storedToken)) {
            throw new BusinessException(40103, "refreshToken 已失效，请重新登录");
        }

        // 4. 查询用户
        User user = findById(userId);

        // 5. 签发新 Token 对
        log.info("Token 刷新成功: userId={}", userId);
        return generateTokenPair(user);
    }

    /**
     * 退出登录（注销 refreshToken）
     *
     * @param userId 用户 ID
     */
    public void logout(Long userId) {
        String redisKey = REFRESH_TOKEN_KEY + userId;
        redisTemplate.delete(redisKey);
        log.info("用户退出登录: userId={}", userId);
    }

    /**
     * 生成 Token 对并存入 Redis
     */
    private Map<String, Object> generateTokenPair(User user) {
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), user.getUsername());

        // refreshToken 存入 Redis，TTL = 7 天
        String redisKey = REFRESH_TOKEN_KEY + user.getId();
        redisTemplate.opsForValue().set(redisKey, refreshToken, REFRESH_TOKEN_TTL_DAYS, TimeUnit.DAYS);

        log.info("Token 对已生成并存储 Redis: userId={}, key={}", user.getId(), redisKey);

        return Map.of(
                "accessToken", accessToken,
                "refreshToken", refreshToken,
                "userId", user.getId(),
                "username", user.getUsername()
        );
    }
}
