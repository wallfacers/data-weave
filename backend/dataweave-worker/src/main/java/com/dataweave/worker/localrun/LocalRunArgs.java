package com.dataweave.worker.localrun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 本地 runner 子进程入参（Go CLI ↔ {@code LocalRunMain} 契约，见 data-model §3）。
 *
 * <p>调用形如：{@code java -cp <worker-runtime-cp> com.dataweave.worker.localrun.LocalRunMain
 * --type SQL --timeout 600 [--ds-json <file>] < script-body}
 *
 * @param type           SHELL/SQL/PYTHON（选执行器 {@code TaskExecutor.type()}）
 * @param timeoutSeconds 超时秒数（≤0 不限）
 * @param dsJsonPath     已解析数据源连接 JSON 文件路径（SQL→DataSourceRef / PYTHON→pythonConfigPath）；null=无数据源
 * @param content        脚本体（从 stdin 读取 → {@code ExecutionContext.content}）
 */
public record LocalRunArgs(String type, int timeoutSeconds, String dsJsonPath, String content) {

    /** 从命令行参数 + stdin 解析（生产 main 入口）。 */
    public static LocalRunArgs parse(String[] args) throws IOException {
        String type = null;
        int timeout = 0;
        String dsJsonPath = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--type" -> type = requireValue(args, a, ++i);
                case "--timeout" -> timeout = Integer.parseInt(requireValue(args, a, ++i));
                case "--ds-json" -> dsJsonPath = requireValue(args, a, ++i);
                default -> throw new IllegalArgumentException("未知参数: " + a);
            }
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("缺少 --type（SHELL/SQL/PYTHON）");
        }
        String content = readStdin();
        return new LocalRunArgs(type, timeout, dsJsonPath, content);
    }

    /** 直接构造（测试用，不经命令行/stdin）。 */
    public static LocalRunArgs of(String type, int timeoutSeconds, String dsJsonPath, String content) {
        return new LocalRunArgs(type, timeoutSeconds, dsJsonPath, content);
    }

    private static String requireValue(String[] args, String flag, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " 缺少值");
        }
        return args[i];
    }

    private static String readStdin() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.in.transferTo(buf);
        return buf.toString(StandardCharsets.UTF_8);
    }
}
