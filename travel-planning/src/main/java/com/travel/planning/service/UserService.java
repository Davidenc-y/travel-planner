package com.travel.planning.service;

import com.travel.common.entity.User;
import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ErrorCode;
import com.travel.planning.repository.UserMapper;
import com.travel.planning.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 用户服务
 *
 * <p>M2-4-F18 增强：refreshToken 存入 Redis，支持 Token 刷新；
 * M3-7 收口：头像更新走本服务（AvatarController 不再直连 Mapper）。</p>
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
    /** M5-1：邮箱格式（与前端一致） */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    /** M5-1：邮箱最大长度（表结构 VARCHAR(100)） */
    private static final int EMAIL_MAX_LENGTH = 100;

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

    /** M3-7：可空查询（头像清理等 best-effort 场景） */
    public User getById(Long userId) {
        return userMapper.selectById(userId);
    }

    /** M3-7：更新头像 URL（独立事务） */
    @Transactional
    public void updateAvatar(Long userId, String avatarUrl) {
        User user = new User();
        user.setId(userId);
        user.setAvatar(avatarUrl);
        userMapper.updateById(user);
    }

    /**
     * M5-1：绑定邮箱（注册未填时后补；格式校验 + 唯一性查重）。
     */
    @Transactional
    public void updateEmail(Long userId, String email) {
        String normalized = email == null ? "" : email.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(40001, "邮箱不能为空");
        }
        if (normalized.length() > EMAIL_MAX_LENGTH || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(40001, "邮箱格式不正确");
        }
        User existing = userMapper.findByEmail(normalized);
        if (existing != null && !existing.getId().equals(userId)) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS.code(), ErrorCode.EMAIL_EXISTS.message());
        }
        User user = new User();
        user.setId(userId);
        user.setEmail(normalized);
        userMapper.updateById(user);
        log.info("邮箱绑定成功: userId={}, email={}", userId, normalized);
    }

    /**
     * 刷新 Token
     */
    public Map<String, Object> refreshToken(String refreshToken) {
        if (!jwtUtil.validateToken(refreshToken)) {
            throw new BusinessException(40102, "refreshToken 无效或已过期");
        }

        Long userId = jwtUtil.getUserIdFromToken(refreshToken);
        String username = jwtUtil.getUsernameFromToken(refreshToken);

        String redisKey = REFRESH_TOKEN_KEY + userId;
        String storedToken = redisTemplate.opsForValue().get(redisKey);
        if (storedToken == null || !refreshToken.equals(storedToken)) {
            throw new BusinessException(40103, "refreshToken 已失效，请重新登录");
        }

        User user = findById(userId);

        log.info("Token 刷新成功: userId={}", userId);
        return generateTokenPair(user);
    }

    /**
     * 退出登录（注销 refreshToken）
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
