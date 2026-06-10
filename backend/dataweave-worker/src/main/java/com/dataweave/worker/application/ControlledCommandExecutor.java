package com.dataweave.worker.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 受控命令执行器：仅接受白名单前缀命令，强制超时与输出截断，返回 exitCode/stdout/stderr。
 *
 * <p>worker 侧防线（白名单首词）；命令注入升级由 master 侧 PolicyEngine 负责（design D7）。
 */
@Component
public class ControlledCommandExecutor {

    private final List<String> whitelist;
    private final long timeoutSeconds;
    private final int maxOutputBytes;

    public ControlledCommandExecutor(
            @Value("${worker.exec.whitelist:df,tail,grep,cat,free,jstat,dw}") List<String> whitelist,
            @Value("${worker.exec.timeout-seconds:60}") long timeoutSeconds,
            @Value("${worker.exec.max-output-bytes:65536}") int maxOutputBytes) {
        this.whitelist = whitelist;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
    }

    public CommandResult execute(String command) {
        if (command == null || command.isBlank()) {
            return CommandResult.rejected("命令为空");
        }
        String firstWord = command.trim().split("\\s+", 2)[0];
        if (!isWhitelisted(firstWord)) {
            return CommandResult.rejected("命令首词 '" + firstWord + "' 不在白名单，拒绝执行");
        }

        Process process = null;
        try {
            process = new ProcessBuilder("bash", "-c", command).start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                Capture out = safeRead(process.getInputStream());
                return CommandResult.timedOut(out.text, out.truncated, out.originalBytes,
                        "命令执行超过 " + timeoutSeconds + "s，已终止");
            }
            Capture out = readCapped(process.getInputStream());
            Capture err = readCapped(process.getErrorStream());
            int exit = process.exitValue();
            boolean truncated = out.truncated || err.truncated;
            return new CommandResult(true, exit, out.text, err.text, truncated, false,
                    out.originalBytes + err.originalBytes,
                    exit == 0 ? "执行完成" : "命令退出码 " + exit);
        } catch (IOException e) {
            return CommandResult.rejected("无法启动进程：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return CommandResult.timedOut("", false, 0, "执行被中断");
        }
    }

    private boolean isWhitelisted(String firstWord) {
        for (String allow : whitelist) {
            if (firstWord.equals(allow.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 读取已缓冲输出，容忍流已关闭（进程被强杀后）。 */
    private Capture safeRead(InputStream in) {
        try {
            return readCapped(in);
        } catch (IOException e) {
            return new Capture("", false, 0);
        }
    }

    private Capture readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        int n;
        boolean truncated = false;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            int remaining = maxOutputBytes - buf.size();
            if (remaining > 0) {
                buf.write(chunk, 0, Math.min(n, remaining));
            } else {
                truncated = true;
            }
            if (buf.size() >= maxOutputBytes && n > 0) {
                truncated = truncated || total > maxOutputBytes;
            }
        }
        truncated = truncated || total > maxOutputBytes;
        return new Capture(buf.toString(StandardCharsets.UTF_8), truncated, total);
    }

    private record Capture(String text, boolean truncated, int originalBytes) {
    }

    /**
     * 命令执行结果。
     *
     * @param accepted      是否通过白名单被接受执行（false=拒绝，未起子进程）
     * @param exitCode      退出码（拒绝/超时为 null）
     * @param stdout        标准输出（可能截断）
     * @param stderr        标准错误（可能截断）
     * @param truncated     输出是否被截断
     * @param timedOut      是否超时终止
     * @param originalBytes 原始输出总字节数（截断前）
     * @param message       面向用户/审计的摘要
     */
    public record CommandResult(boolean accepted, Integer exitCode, String stdout, String stderr,
                                boolean truncated, boolean timedOut, int originalBytes, String message) {

        static CommandResult rejected(String message) {
            return new CommandResult(false, null, "", "", false, false, 0, message);
        }

        static CommandResult timedOut(String stdout, boolean truncated, int originalBytes, String message) {
            return new CommandResult(true, null, stdout, "", truncated, true, originalBytes, message);
        }

        public boolean success() {
            return accepted && !timedOut && exitCode != null && exitCode == 0;
        }
    }
}
