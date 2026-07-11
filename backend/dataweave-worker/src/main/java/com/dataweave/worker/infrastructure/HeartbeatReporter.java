package com.dataweave.worker.infrastructure;

import com.dataweave.worker.application.IncarnationManager;
import com.dataweave.worker.application.WorkerExecService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Worker 心跳上报客户端（task 3.5）。
 *
 * <p>定时采集本机指标 + incarnation + 运行中实例 ID 列表，POST 到 master 的 /api/fleet/heartbeat 端点。
 * 供独立 worker 进程部署时启用（dataweave.worker.heartbeat.enabled=true）。
 */
@Component
public class HeartbeatReporter {

    private static final System.Logger log = System.getLogger(HeartbeatReporter.class.getName());

    @Value("${dataweave.worker.heartbeat.enabled:true}")
    private boolean enabled;

    @Value("${dataweave.master.url:http://localhost:8000}")
    private String masterUrl;

    @Value("${dataweave.worker.node-code:worker-local}")
    private String nodeCode;

    /** worker 自身 HTTP 端口，随心跳上报供 master distributed 下发寻址。 */
    @Value("${server.port:8100}")
    private int serverPort;

    /** 对外可达主机名/IP（同机多 worker 区分用），留空则回退本机名。 */
    @Value("${dataweave.worker.advertise-host:}")
    private String advertiseHost;

    /** worker 失联自我中止宽限期（ms），必须 ≥ master 租约过期窗口以防止误杀（FR-021）。 */
    @Value("${scheduler.worker.self-fence-grace-ms:20000}")
    private long selfFenceGraceMs;

    /** master 租约续约窗（心跳间隔内租约不会过期），用于 self-fence-grace 下限校验。 */
    @Value("${dataweave.worker.heartbeat.interval-ms:10000}")
    private long heartbeatIntervalMs;

    private final IncarnationManager incarnationManager;
    private final WorkerExecService execService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 首次连续心跳失败的时刻（null=当前未在失败序列中）。 */
    private Instant firstFailureSince;
    /** 是否已触发自我中止（防重复 abort）。 */
    private volatile boolean aborted;

    public HeartbeatReporter(IncarnationManager incarnationManager, WorkerExecService execService) {
        this.incarnationManager = incarnationManager;
        this.execService = execService;
    }

    @Scheduled(fixedDelayString = "${dataweave.worker.heartbeat.interval-ms:10000}")
    public void report() {
        if (!enabled) {
            return;
        }
        // 已触发自我中止则不再上报（避免中止后的心跳误导 master）
        if (aborted) {
            return;
        }
        try {
            String host = buildAdvertisedHost(advertiseHost, InetAddress.getLocalHost().getHostName(), serverPort);
            String capacity = buildCapacity();
            // L1 真采集（live-telemetry）：替换硬编码常量，输出 0-100 百分比量纲（与诊断 mem>=90 等阈值一致）。
            NodeMetrics m = sample();
            double cpu = m.cpu();
            double mem = m.mem();
            double disk = m.disk();
            double loadAvg = m.loadAvg();

            long incarnation = incarnationManager.incarnation();
            // FR-016：真实上报正在运行的实例 ID，使 master renewLease 生效（修复长跑任务被误杀根因）
            Set<UUID> runningIds = execService.runningInstanceIds();
            String instanceIdsArray = runningIds.isEmpty()
                    ? "[]"
                    : "[" + runningIds.stream()
                            .map(id -> "\"" + id.toString() + "\"")
                            .collect(Collectors.joining(",")) + "]";

            String json = """
                    {"nodeCode":"%s","host":"%s","capacity":"%s","cpu":%s,"mem":%s,"disk":%s,"loadAvg":%s,"runningTasks":%s,"incarnation":%s,"runningInstanceIds":%s}
                    """.formatted(
                    nodeCode, host, capacity, cpu, mem, disk, loadAvg,
                    execService.inFlightCount(), incarnation, instanceIdsArray);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(masterUrl + "/api/fleet/heartbeat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.log(System.Logger.Level.DEBUG,
                    "Heartbeat reported: nodeCode={0}, incarnation={1}, status={2}",
                    nodeCode, incarnation, response.statusCode());

            // 心跳成功 → 复位失败计数器
            firstFailureSince = null;
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING,
                    "Heartbeat report failed: {0}", e.getMessage());
            onHeartbeatFailure();
        }
    }

    /**
     * 累积心跳失败 → 跨过 self-fence-grace 阈值则自我中止（FR-021：防分区物理双跑）。
     *
     * <p>不变量断言：self-fence-grace 必须 ≥ 心跳间隔 × 2（给 master 至少两次心跳机会判过期），
     * 否则一次短暂网络抖动就可能触发误杀。启动时校验，违反则拒绝启动。
     */
    private void onHeartbeatFailure() {
        // 启动时校验不变量：self-fence-grace 必须 ≥ 2× 心跳间隔（给续约两次机会）
        if (selfFenceGraceMs < heartbeatIntervalMs * 2L) {
            String msg = "self-fence-grace-ms (" + selfFenceGraceMs
                    + "ms) 必须 ≥ 2× heartbeat-interval (" + heartbeatIntervalMs
                    + "ms) = " + (heartbeatIntervalMs * 2L) + "ms，"
                    + "防止短暂网络抖动误杀运行中任务";
            log.log(System.Logger.Level.ERROR, msg);
            throw new IllegalStateException(msg);
        }

        Instant now = Instant.now();
        if (firstFailureSince == null) {
            firstFailureSince = now;
            log.log(System.Logger.Level.WARNING,
                    "心跳开始失败（首次 {0}），self-fence-grace={1}ms 后将自我中止",
                    firstFailureSince, selfFenceGraceMs);
            return;
        }

        long failedMs = Duration.between(firstFailureSince, now).toMillis();
        if (failedMs >= selfFenceGraceMs) {
            log.log(System.Logger.Level.ERROR,
                    "心跳连续失败 {0}ms ≥ self-fence-grace {1}ms，触发自我中止",
                    failedMs, selfFenceGraceMs);
            aborted = true;
            execService.abortAll();
        }
    }

    /**
     * 拼装上报的可达地址 {@code host:port}：advertiseHost 非空则用它（已含端口则原样返回），
     * 否则回退 fallbackHost；最终统一补上 port。
     */
    static String buildAdvertisedHost(String advertiseHost, String fallbackHost, int port) {
        String host = (advertiseHost != null && !advertiseHost.isBlank()) ? advertiseHost : fallbackHost;
        return host.contains(":") ? host : host + ":" + port;
    }

    /** 节点真实资源指标（0-100 百分比；loadAvg 为系统原始值，不可得时为 0）。 */
    record NodeMetrics(double cpu, double mem, double disk, double loadAvg) {
    }

    /**
     * 经 {@link com.sun.management.OperatingSystemMXBean} 采集本机真实资源水位。
     * CPU/内存/磁盘归一到 0-100；CPU 首次采样可能为负（未就绪），统一夹到 [0,100]。
     */
    static NodeMetrics sample() {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        double cpu = clampPercent(os.getCpuLoad() * 100.0);

        long total = os.getTotalMemorySize();
        long free = os.getFreeMemorySize();
        double mem = total > 0 ? clampPercent((total - free) * 100.0 / total) : 0.0;

        File root = new File("/");
        long diskTotal = root.getTotalSpace();
        long diskUsable = root.getUsableSpace();
        double disk = diskTotal > 0 ? clampPercent((diskTotal - diskUsable) * 100.0 / diskTotal) : 0.0;

        double rawLoad = os.getSystemLoadAverage();
        double loadAvg = rawLoad < 0 ? 0.0 : rawLoad;

        return new NodeMetrics(round2(cpu), round2(mem), round2(disk), round2(loadAvg));
    }

    private static double clampPercent(double v) {
        if (Double.isNaN(v) || v < 0) {
            return 0.0;
        }
        return Math.min(100.0, v);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * 构建容量规格字符串（如 "8C/16G"），Docker 容器感知：优先读 cgroup 限制，
     * 不可得时回退到 JVM 可见的宿主机 CPU/内存。
     */
    static String buildCapacity() {
        int cores = detectCpuCores();
        long memoryGb = detectMemoryGb();
        return cores + "C/" + memoryGb + "G";
    }

    /** 检测 CPU 核数：cgroup v2 → v1 → JVM availableProcessors。 */
    static int detectCpuCores() {
        // cgroup v2: /sys/fs/cgroup/cpu.max 格式 "MAX PERIOD"
        Double v2 = parseCgroupV2Cpu();
        if (v2 != null && v2 > 0) {
            return Math.max(1, (int) Math.ceil(v2));
        }
        // cgroup v1: quota/period
        Double v1 = parseCgroupV1Cpu();
        if (v1 != null && v1 > 0) {
            return Math.max(1, (int) Math.ceil(v1));
        }
        return Runtime.getRuntime().availableProcessors();
    }

    /** 检测总内存（GB）：cgroup v2 → v1 → OS MXBean totalMemory。 */
    static long detectMemoryGb() {
        Long cgroupBytes = parseCgroupMemoryBytes();
        long bytes;
        if (cgroupBytes != null && cgroupBytes > 0) {
            bytes = cgroupBytes;
        } else {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            bytes = os.getTotalMemorySize();
        }
        long gb = bytes / (1024L * 1024L * 1024L);
        return Math.max(1, gb);
    }

    /** 读 cgroup v2 cpu.max，返回核数（可为小数）或 null。 */
    private static Double parseCgroupV2Cpu() {
        try {
            String content = readFileString("/sys/fs/cgroup/cpu.max");
            if (content == null || content.isBlank()) return null;
            String[] parts = content.strip().split("\\s+");
            if (parts.length < 2) return null;
            String max = parts[0];
            if ("max".equals(max)) return null; // 无限制
            double quota = Double.parseDouble(max);
            double period = Double.parseDouble(parts[1]);
            return period > 0 ? quota / period : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 读 cgroup v1 cpu.cfs_quota_us / cpu.cfs_period_us，返回核数或 null。 */
    private static Double parseCgroupV1Cpu() {
        try {
            String quotaStr = readFileString("/sys/fs/cgroup/cpu/cpu.cfs_quota_us");
            String periodStr = readFileString("/sys/fs/cgroup/cpu/cpu.cfs_period_us");
            if (quotaStr == null || periodStr == null) return null;
            double quota = Double.parseDouble(quotaStr.strip());
            if (quota < 0) return null; // -1 = 无限制
            double period = Double.parseDouble(periodStr.strip());
            return period > 0 ? quota / period : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 读 cgroup 内存限制（bytes）：v2 memory.max → v1 memory.limit_in_bytes，不可得返回 null。 */
    private static Long parseCgroupMemoryBytes() {
        // cgroup v2
        String v2 = readFileString("/sys/fs/cgroup/memory.max");
        if (v2 != null && !v2.isBlank() && !"max".equals(v2.strip())) {
            try { return Long.parseLong(v2.strip()); } catch (NumberFormatException ignored) {}
        }
        // cgroup v1
        String v1 = readFileString("/sys/fs/cgroup/memory/memory.limit_in_bytes");
        if (v1 != null && !v1.isBlank()) {
            try {
                long val = Long.parseLong(v1.strip());
                // 超大值 = 无限制（cgroup v1 无限制时设为接近 Long.MAX）
                long limit = 1L << 50; // ~1 PB
                return val < limit ? val : null;
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /** 读取文件首行内容，文件不存在或读取失败返回 null。 */
    private static String readFileString(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path)).strip();
        } catch (Exception e) {
            return null;
        }
    }
}
