package com.travel.planning.service;

import com.travel.common.entity.User;

import java.util.Map;

/**
 * UserService 公共契约（AD-2d 接口化）。
 *
 * <p>实现见 {@link UserServiceImpl}（@Service 在 Impl）；
 * 公有方法签名逐字提取（零语义变更）。</p>
 */
public interface UserService {

    /** 注册。 */
    Map<String, Object> register(String username, String password, String email);

    /** 登录。 */
    Map<String, Object> login(String username, String password);

    /** 按 id 查询（不存在返回 null）。 */
    User findById(Long userId);

    /** 按 id 查询（不存在抛业务异常）。 */
    User getById(Long userId);

    /** 更新头像。 */
    void updateAvatar(Long userId, String avatarUrl);

    /** 更新邮箱。 */
    void updateEmail(Long userId, String email);

    /** 刷新令牌。 */
    Map<String, Object> refreshToken(String refreshToken);

    /** 登出。 */
    void logout(Long userId);
}
