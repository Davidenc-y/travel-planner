package com.travel.aigateway.route;

import com.travel.aigateway.core.ChatModelFactory;
import com.travel.aigateway.core.ModelDescriptor;
import com.travel.aigateway.core.ModelRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * M7：角色路由代理（方案 B 核心）。
 *
 * <p>实现 {@link ChatModel}，两个实例分别注册为 {@code chatModel}(@Primary) 与
 * {@code lightModel} Bean；Agent 构建期绑定的是该稳定代理，运行期按以下顺序委托：</p>
 * <ol>
 *   <li>Prompt 显式 options.model（Level 2 拦截器注入 / 未来调用点显式传参）；</li>
 *   <li>请求级上下文（仅 main 角色消费，D3）；</li>
 *   <li>注册表角色默认模型。</li>
 * </ol>
 * 未注册/未启用/不可选的目标 → {@link com.travel.aigateway.core.GatewayException}
 * （D6：入口快速失败，不静默回退）。</p>
 */
@Slf4j
public final class RoleRoutingChatModel implements ChatModel {

    private final String role;
    private final ModelRegistry registry;
    private final ChatModelFactory factory;

    public RoleRoutingChatModel(String role, ModelRegistry registry, ChatModelFactory factory) {
        this.role = role;
        this.registry = registry;
        this.factory = factory;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return resolve(prompt).call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return resolve(prompt).stream(prompt);
    }

    /** D1/D3/D5/D6 落地：options > context(main) > 角色默认。 */
    private ChatModel resolve(Prompt prompt) {
        String fromOptions = prompt.getOptions() != null ? prompt.getOptions().getModel() : null;
        String fromContext = "main".equals(role) ? ModelRoutingContext.current() : null;
        String target = fromOptions != null
                ? fromOptions
                : (fromContext != null ? fromContext : registry.defaultOf(role).key());
        // M8-9k：请求级模型生效时的可观测点（fromContext/fromOptions 命中即打印）
        if (fromContext != null || fromOptions != null) {
            log.info("[RoleRouting] role={}, optionsModel={}, contextModel={}, target={}",
                    role, fromOptions, fromContext, target);
        }
        ModelDescriptor descriptor = registry.requireSelectable(target);
        ModelRoutingContext.recordRouted(descriptor.key());
        return factory.obtain(descriptor);
    }

    public String role() {
        return role;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        // 路由代理无固定默认参数：defaultOptions 语义由目标模型描述符承载
        return null;
    }
}
