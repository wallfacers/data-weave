package com.dataweave.alert.infrastructure.channel;

/**
 * 通道投递结果。
 *
 * <p>{@code configured=false}（{@link #notConfigured}）表示通道未配置——语义区别于
 * 「配置了但发送失败」（{@link #failed}）：未配置不应重试、不应记为 FAILED，
 * 由 {@code AlertDispatchService} 短路为 SKIPPED。
 */
public record DispatchResult(boolean success, String error, String responseDigest, boolean configured) {

    public static DispatchResult sent(String responseDigest) {
        return new DispatchResult(true, null, responseDigest, true);
    }

    public static DispatchResult failed(String error) {
        return new DispatchResult(false, error, null, true);
    }

    /** 通道未配置（如缺收件人 / 未配 SMTP）：不抛错、不假成功、不重试。 */
    public static DispatchResult notConfigured(String reason) {
        return new DispatchResult(false, reason, null, false);
    }
}
