package com.travel.planning.workflow;

import com.travel.common.entity.ItineraryTaskSnapshot;
import com.travel.planning.repository.ItineraryTaskSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 行程任务快照端口实现（M4-8/P1-5，R2 G1）。
 *
 * <p>节点内异步写（虚拟线程，失败仅 WARN——快照缺失的兜底是 resume 回退整图重跑）；
 * 读取按 node 取每节点最新一条（同节点可能因 budget_retry 重跑产生多版本）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryTaskSnapshotPort {

    private static final ExecutorService SNAPSHOT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ItineraryTaskSnapshotMapper snapshotMapper;

    /** 异步写快照（不阻塞节点执行；失败仅 WARN） */
    public void writeAsync(Long taskId, String node, String payload) {
        if (taskId == null || node == null || payload == null) {
            return;
        }
        SNAPSHOT_EXECUTOR.submit(() -> {
            try {
                ItineraryTaskSnapshot snapshot = new ItineraryTaskSnapshot();
                snapshot.setTaskId(taskId);
                snapshot.setNode(node);
                snapshot.setPayload(payload);
                snapshotMapper.insert(snapshot);
            } catch (Exception e) {
                log.warn("[TaskSnapshot] 快照写入失败（resume 将回退整图重跑）: taskId={}, node={}, error={}",
                        taskId, node, e.getMessage());
            }
        });
    }

    /** 读取任务每个节点的最新快照（node → payload）；无快照返回空 Map */
    public Map<String, String> loadLatestByTask(Long taskId) {
        Map<String, String> latest = new LinkedHashMap<>();
        if (taskId == null) {
            return latest;
        }
        try {
            List<ItineraryTaskSnapshot> all = snapshotMapper.findByTaskId(taskId);
            for (ItineraryTaskSnapshot s : all) { // ORDER BY id DESC：首见即最新
                latest.putIfAbsent(s.getNode(), s.getPayload());
            }
        } catch (Exception e) {
            log.warn("[TaskSnapshot] 快照读取失败（按无快照处理，回退整图重跑）: taskId={}, error={}",
                    taskId, e.getMessage());
        }
        return latest;
    }
}
