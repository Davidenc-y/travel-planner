package com.travel.planning.service;

import com.travel.common.dto.ItineraryGenerateRequestDTO;
import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.entity.Itinerary;
import com.travel.common.exception.BusinessException;
import com.travel.common.exception.ErrorCode;
import com.travel.common.exception.ItineraryGenerationException;
import com.travel.common.result.PageResult;
import com.travel.planning.guard.GuardService;
import com.travel.planning.memory.knowledge.SessionContextChunker;
import com.travel.planning.memory.knowledge.SessionKnowledgeWriter;
import com.travel.planning.memory.longterm.ProfileContextAssembler;
import com.travel.planning.memory.longterm.ProfilePort;
import com.travel.planning.prompt.PromptTemplates;
import com.travel.planning.repository.ItineraryMapper;
import com.travel.planning.workflow.ItineraryStateMachineProperties;
import com.travel.planning.workflow.ItineraryTaskSnapshotPort;
import com.travel.planning.workflow.TravelWorkflowBuilder;
import com.travel.aigateway.core.GatewayException;
import com.travel.aigateway.core.ModelRegistry;
import com.travel.aigateway.route.ModelRoutingContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 行程服务入口（M2-2 增强版，M10-1c 编排拆分后收口）。
 *
 * <p>仅保留：模型校验 + ModelRoutingContext 包装、generate/resume 门面、查询/删除，
 * 以及为既有测试保留的静态兼容委托。生成编排见
 * {@link ItineraryGenerationOrchestrator}，续跑见 {@link ItineraryResumeCoordinator}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryService {

    private final ItineraryMapper itineraryMapper;
    private final TravelWorkflowBuilder workflowBuilder;
    private final ProfilePort profilePort;
    private final MindmapGenerator mindmapGenerator;
    private final ProfileContextAssembler profileContextAssembler;
    private final SessionContextChunker sessionContextChunker;
    private final SessionKnowledgeWriter sessionKnowledgeWriter;
    private final GuardService guardService;
    private final PromptTemplates promptTemplates;
    private final ItineraryPersistenceService persistenceService;
    private final ItineraryTaskSnapshotPort snapshotPort;
    private final ItineraryStateMachineProperties stateMachineProps;
    private final ModelRegistry modelRegistry;

    private ItineraryDtoAssembler dtoAssembler;
    private ItinerarySliceWriter sliceWriter;
    private ItineraryResumeCoordinator resumeCoordinator;
    private ItineraryGraphExecutor graphExecutor;
    private ItineraryGenerationOrchestrator generationOrchestrator;
    private ItineraryCoordinateDecorator coordinateDecorator;

    @Autowired(required = false)
    void setItineraryDtoAssembler(ItineraryDtoAssembler dtoAssembler) {
        this.dtoAssembler = dtoAssembler;
    }

    @Autowired(required = false)
    void setItinerarySliceWriter(ItinerarySliceWriter sliceWriter) {
        this.sliceWriter = sliceWriter;
    }

    @Autowired(required = false)
    void setItineraryResumeCoordinator(ItineraryResumeCoordinator resumeCoordinator) {
        this.resumeCoordinator = resumeCoordinator;
    }

    @Autowired(required = false)
    void setItineraryGraphExecutor(ItineraryGraphExecutor graphExecutor) {
        this.graphExecutor = graphExecutor;
    }

    @Autowired(required = false)
    void setItineraryGenerationOrchestrator(ItineraryGenerationOrchestrator generationOrchestrator) {
        this.generationOrchestrator = generationOrchestrator;
    }

    @Autowired(required = false)
    void setItineraryCoordinateDecorator(ItineraryCoordinateDecorator coordinateDecorator) {
        this.coordinateDecorator = coordinateDecorator;
    }

    private ItineraryDtoAssembler dtoAssembler() {
        if (dtoAssembler == null) {
            dtoAssembler = new ItineraryDtoAssembler();
        }
        return dtoAssembler;
    }

    private ItinerarySliceWriter sliceWriter() {
        if (sliceWriter == null) {
            sliceWriter = new ItinerarySliceWriter(sessionContextChunker, sessionKnowledgeWriter);
        }
        return sliceWriter;
    }

    private ItineraryGraphExecutor graphExecutor() {
        if (graphExecutor == null) {
            graphExecutor = new ItineraryGraphExecutor();
        }
        return graphExecutor;
    }

    private ItineraryResumeCoordinator resumeCoordinator() {
        if (resumeCoordinator == null) {
            resumeCoordinator = new ItineraryResumeCoordinator(
                    stateMachineProps, itineraryMapper, workflowBuilder, profilePort,
                    mindmapGenerator, profileContextAssembler, promptTemplates,
                    persistenceService, snapshotPort, dtoAssembler(), graphExecutor());
        }
        return resumeCoordinator;
    }

    private ItineraryGenerationOrchestrator generationOrchestrator() {
        if (generationOrchestrator == null) {
            generationOrchestrator = new ItineraryGenerationOrchestrator(
                    itineraryMapper, workflowBuilder, profilePort, mindmapGenerator,
                    profileContextAssembler, guardService, promptTemplates, persistenceService,
                    stateMachineProps, dtoAssembler(), graphExecutor(),
                    resumeCoordinator(), sliceWriter());
        }
        return generationOrchestrator;
    }

    /**
     * 生成行程（编排已下沉 {@link ItineraryGenerationOrchestrator}）。
     */
    public ItineraryResponseDTO generate(ItineraryGenerateRequestDTO req, Long userId) {
        validateModel(req.getModel());
        return ModelRoutingContext.runWith(req.getModel(),
                () -> generationOrchestrator().generate(req, userId));
    }

    /**
     * 断点续跑（语义下沉 {@link ItineraryResumeCoordinator}）。
     */
    public ItineraryResponseDTO resume(Long id, Long userId) {
        return ModelRoutingContext.runWith(null,
                () -> resumeCoordinator().resume(id, userId));
    }

    /** 查询行程详情 */
    public ItineraryResponseDTO getById(Long id) {
        Itinerary entity = itineraryMapper.selectById(id);
        if (entity == null) {
            throw new ItineraryGenerationException("行程不存在: " + id);
        }
        ItineraryResponseDTO dto = toResponseDTO(entity);
        // M12-5：坐标装饰只保留在详情/地图路径（列表页不再按城市回查坐标）
        if (coordinateDecorator != null) {
            coordinateDecorator.decorate(dto);
        }
        return dto;
    }

    /** 分页查询用户行程 */
    public PageResult<ItineraryResponseDTO> listByUserId(Long userId, int page, int size) {
        int offset = (page - 1) * size;
        List<Itinerary> list = itineraryMapper.findByUserId(userId, offset, size);
        long total = itineraryMapper.countByUserId(userId);
        return PageResult.of(list.stream().map(this::toResponseDTO).toList(), total, page, size);
    }

    /** 删除行程 */
    public void delete(Long id) {
        itineraryMapper.deleteById(id);
        log.info("行程删除: id={}", id);
    }

    /** M7 D6：未知/禁用/不可选模型 → 40005，不静默回退。 */
    private void validateModel(String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        try {
            modelRegistry.requireSelectable(model);
        } catch (GatewayException e) {
            throw new BusinessException(ErrorCode.MODEL_NOT_FOUND.code(),
                    ErrorCode.MODEL_NOT_FOUND.message() + ": " + model);
        }
    }

    /** 兼容既有静态测试：断点解析委托 ResumeCoordinator。 */
    static String resolveResumeFrom(Map<String, String> snapshots) {
        return ItineraryResumeCoordinator.resolveResumeFrom(snapshots);
    }

    /** 兼容既有静态测试：快照污染过滤委托 ResumeCoordinator。 */
    static Map<String, String> sanitizeSnapshots(Map<String, String> snapshots) {
        return ItineraryResumeCoordinator.sanitizeSnapshots(snapshots);
    }

    /** 兼容既有静态测试：resume 消息构建委托 ResumeCoordinator。 */
    static UserMessage buildResumeMessage(String userInput, Map<String, String> snapshots) {
        return ItineraryResumeCoordinator.buildResumeMessage(userInput, snapshots);
    }

    /** GENERATING 僵尸判定（供 DTO 可续标志） */
    private boolean isZombie(Itinerary task) {
        return resumeCoordinator().isZombie(task);
    }

    /** Entity → ResponseDTO */
    private ItineraryResponseDTO toResponseDTO(Itinerary entity) {
        ItineraryResponseDTO dto = dtoAssembler().toResponseDTO(entity, isResumable(entity));
        return dto;
    }

    /** M6-52：行程是否可断点续跑（与 resume 端点守卫同口径）。 */
    private boolean isResumable(Itinerary entity) {
        if (entity == null || entity.getStatus() == null) {
            return false;
        }
        String status = entity.getStatus();
        return "FAILED".equals(status)
                || ("GENERATING".equals(status) && isZombie(entity));
    }
}
