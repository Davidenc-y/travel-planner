package com.travel.planning.controller;

import com.travel.common.entity.User;
import com.travel.common.result.R;
import com.travel.planning.repository.UserMapper;
import com.travel.planning.service.UserService;
import com.travel.planning.util.AuthUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F121：当前用户资料（含 avatar），供前端导航/个人中心展示头像。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class MeController {

    private final UserMapper userMapper;
    private final UserService userService;

    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        Long userId = AuthUtils.resolveUserId(null);
        User user = userMapper.selectById(userId);
        if (user == null) {
            return R.fail(40401, "用户不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("username", user.getUsername());
        m.put("avatar", user.getAvatar());
        m.put("email", user.getEmail());
        m.put("phone", user.getPhone());
        return R.ok(m);
    }

    /**
     * M5-1：绑定邮箱（注册未填邮箱时后补；格式与唯一性由 UserService 校验）
     */
    @PutMapping("/email")
    public R<Void> updateEmail(@RequestBody Map<String, String> body) {
        userService.updateEmail(AuthUtils.resolveUserId(null), body.get("email"));
        return R.ok();
    }
}
