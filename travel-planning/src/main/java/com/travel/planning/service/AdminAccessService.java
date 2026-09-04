package com.travel.planning.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * M11-3：管理员白名单判定（毕设无角色列，配置 travel.admin.user-ids 驱动）。
 */
@Component
public class AdminAccessService {

    private final Set<String> adminUserIds;

    public AdminAccessService(@Value("${travel.admin.user-ids:}") String adminUserIds) {
        this.adminUserIds = Arrays.stream(adminUserIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public boolean isAdmin(Long userId) {
        return userId != null && adminUserIds.contains(String.valueOf(userId));
    }
}
