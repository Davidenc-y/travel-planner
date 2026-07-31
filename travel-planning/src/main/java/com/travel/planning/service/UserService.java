package com.travel.planning.service;

import com.travel.common.entity.User;
import com.travel.common.exception.BusinessException;
import com.travel.planning.repository.UserMapper;
import com.travel.planning.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 用户服务
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
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 注册
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

    private Map<String, Object> generateTokenPair(User user) {
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), user.getUsername());
        return Map.of(
                "accessToken", accessToken,
                "refreshToken", refreshToken,
                "userId", user.getId(),
                "username", user.getUsername()
        );
    }
}
