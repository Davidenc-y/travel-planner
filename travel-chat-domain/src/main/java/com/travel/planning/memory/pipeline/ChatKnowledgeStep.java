package com.travel.planning.memory.pipeline;

import com.travel.planning.memory.knowledge.SessionContextChunker;
import com.travel.planning.memory.knowledge.SessionKnowledgeWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * M3-13：MessagePipeline 步骤 4「知识」。
 * 用户消息的会话知识切片 + 异步写入从 ChatService 抽出为独立可测步骤。
 */
@Component
@RequiredArgsConstructor
public class ChatKnowledgeStep implements ChatPipelineStep {

    /** B3.2：步骤顺序——M3-13 步骤 4「知识」（依据 R7-pipeline-mapping 现发送链步骤序 4，ChatService :476）。 */
    static final int STEP_ORDER = 4;

    @Override
    public int order() {
        return STEP_ORDER;
    }

    private final SessionContextChunker sessionContextChunker;
    private final SessionKnowledgeWriter sessionKnowledgeWriter;

    /**
     * 约束/反馈类消息 → 切片 → 异步写入会话知识（幂等；失败不影响主流程）。
     */
    public void writeUserMessageAsync(String sessionId, String message) {
        sessionKnowledgeWriter.writeAsync(sessionId,
                sessionContextChunker.chunkUserMessage(sessionId, message));
    }
}
