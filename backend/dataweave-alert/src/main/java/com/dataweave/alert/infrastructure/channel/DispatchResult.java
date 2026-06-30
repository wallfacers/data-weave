package com.dataweave.alert.infrastructure.channel;

/**
 * 通道投递结果。
 */
public record DispatchResult(boolean success, String error, String responseDigest) {

    public static DispatchResult sent(String responseDigest) {
        return new DispatchResult(true, null, responseDigest);
    }

    public static DispatchResult failed(String error) {
        return new DispatchResult(false, error, null);
    }
}
