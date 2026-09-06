package com.travel.planning.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.List;

/**
 * M16-3（2026-09-06 机制修订）：chat-domain 域共享配置装载器。
 *
 * <p>背景：共享配置 {@code application-chat.yml} 物理上位于 travel-chat-domain 模块，
 * 此前两消费进程经 {@code spring.config.import: classpath:application-chat.yml} 引入。
 * 运行时该机制完全正常（依赖模块 classpath 可达），但 <b>IDEA 无法静态解析依赖模块内的
 * classpath 资源</b>，在 planning/webflux 的 application.yml 上报
 * "Cannot resolve file 'application-chat.yml'"。本类改用 Spring 官方的
 * 「共享库配置」模式：经 {@code META-INF/spring.factories} 注册
 * {@link EnvironmentPostProcessor}，在 Environment 准备期直接装载该文件——
 * 两进程 yml 不再出现 import 语句，IDEA 报错消除，单源不变。</p>
 *
 * <p>优先级语义与原 config.import 等价：装载为<b>最低优先级</b>属性源
 * （{@code addLast}）——进程 application.yml 与 profile
 * application-local.yml 的同名键均可覆盖，三层结构
 * （local &gt; 进程 yml &gt; chat-domain 共享）保持不变。</p>
 *
 * <p>注册：{@code travel-chat-domain/src/main/resources/META-INF/spring.factories}
 * （Boot 3 中 EnvironmentPostProcessor 仍走 spring.factories，非
 * AutoConfiguration.imports）。文件缺失快速失败（词表/守卫等单源配置不可静默丢失）。</p>
 */
public class ChatDomainConfigEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** 属性源名（幂等判重用） */
    static final String SOURCE_NAME = "application-chat";
    /** 共享配置文件（与 chat-domain resources 内单源文件一致） */
    static final String RESOURCE_NAME = "application-chat.yml";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return; // 已装载（幂等；兼容过渡期 spring.config.import 已加载的场景）
        }
        Resource resource = resource();
        if (!resource.exists()) {
            throw new IllegalStateException("chat-domain 共享配置缺失: classpath:" + RESOURCE_NAME
                    + " —— 守卫词表/五组词表/模型注册表等以该文件为单源，拒绝启动（快速失败）");
        }
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(SOURCE_NAME, resource);
            for (PropertySource<?> source : sources) {
                // 最低优先级：进程 yml / profile(local) 的同名键覆盖本文件（三层结构语义不变）
                environment.getPropertySources().addLast(source);
            }
        } catch (IOException e) {
            throw new IllegalStateException("chat-domain 共享配置解析失败: " + RESOURCE_NAME, e);
        }
    }

    /** 资源定位（protected 供单测覆盖缺失场景） */
    protected Resource resource() {
        return new ClassPathResource(RESOURCE_NAME);
    }
}
