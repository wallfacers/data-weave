package com.dataweave.master.application.incident;

import com.dataweave.master.domain.signal.AlertSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Incident 信号消费入口（043）：第三个 AlertSignal 消费者，整方法 try-catch 吞异常。
 *
 * <p>同步派发（Spring 默认）——listener 异常会冒泡回 InstanceStateMachine/SlaService/LeaseReaper，
 * 故必须整体隔离（镜像 HealthEventRecorder FR-007）。仅处理四类信号，其余直接 return。
 *
 * <p>签名生成（research D3）、同 workflowInstanceId 归并优先（D4）、severity 只升不降（D9）
 * 均由 {@link IncidentService#openOrAttach(AlertSignal)} 内部完成。
 */
@Component
public class IncidentSignalListener {

    private static final Logger log = LoggerFactory.getLogger(IncidentSignalListener.class);

    private final IncidentService incidentService;

    public IncidentSignalListener(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @EventListener(AlertSignal.class)
    public void on(AlertSignal signal) {
        try {
            AlertSignal.Type type = signal.getType();
            if (type != AlertSignal.Type.TASK_FAILED
                    && type != AlertSignal.Type.TASK_TIMEOUT
                    && type != AlertSignal.Type.SLA_BREACH
                    && type != AlertSignal.Type.NODE_OFFLINE) {
                return; // FR-001：仅接入四类信号，其余静默跳过
            }
            incidentService.openOrAttach(signal);
        } catch (Exception e) {
            log.error("[IncidentSignal] failed to process signal type={}", signal.getType(), e);
            // 吞异常：不得冒泡回发布点（InstanceStateMachine / SlaService / LeaseReaper）
        }
    }
}
