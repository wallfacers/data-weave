package com.dataweave.api.infrastructure;

import com.dataweave.master.quality.application.QualityProbeGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link QualityProbeGateway} 的 distributed 模式 no-op 实现。
 *
 * <p>distributed 模式下不支持进程内同步执行质量探针（worker 是独立进程），
 * 质量检查功能在 distributed 模式下降级为 SKIPPED，不阻断启动。
 * 完整 distributed 质量探针实现留 TODO（需 worker HTTP 回调通道）。
 */
@Component
@ConditionalOnProperty(name = "scheduler.mode", havingValue = "distributed")
public class DistributedQualityProbeGateway implements QualityProbeGateway {

    private static final Logger log = LoggerFactory.getLogger(DistributedQualityProbeGateway.class);

    @Override
    public ProbeOutcome probe(long datasourceId, String measureSql, int timeoutSec) {
        log.debug("distributed mode quality probe skipped: datasourceId={}", datasourceId);
        return ProbeOutcome.skipped("distributed mode — quality probe not yet supported");
    }
}
