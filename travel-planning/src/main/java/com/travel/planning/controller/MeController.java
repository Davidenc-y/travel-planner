package com.travel.planning.controller;

import com.travel.common.dto.UsageStatsDTO;
import com.travel.common.entity.User;
import com.travel.common.result.R;
import com.travel.planning.repository.UserMapper;
import com.travel.planning.service.UserUsageStatsService;
import com.travel.planning.service.UserService;
import com.travel.planning.util.AuthUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F121：当前用户资料（含 avatar），供前端导航/个人中心展示头像。
 * U1：新增使用统计端点（数据源 t_agent_trace，口径见 UserUsageStatsService）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class MeController {

    private final UserMapper userMapper;
    private final UserService userService;
    private final UserUsageStatsService userUsageStatsService;

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

    /**
     * U1：个人中心使用统计。
     *
     * @param rangeDays 趋势图/模型用量的时间范围（7 或 30，其余按 30 处理）
     */
    @GetMapping("/me/usage-stats")
    public R<UsageStatsDTO> usageStats(@RequestParam(defaultValue = "30") Integer rangeDays) {
        int range = (rangeDays != null && rangeDays == 7) ? 7 : 30;
        return R.ok(userUsageStatsService.getUsageStats(AuthUtils.resolveUserId(null), range));
    }
}
