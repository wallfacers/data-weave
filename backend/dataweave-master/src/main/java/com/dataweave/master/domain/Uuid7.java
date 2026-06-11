package com.dataweave.master.domain;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 生成器（RFC 9562）：高 48 位为 Unix 毫秒时间戳，时间有序。
 *
 * <p>时间有序是百万级升级「第一刀」（PG 按 biz_date 分区 + 冷数据归档）的前提——
 * 实例类核心表（task_instance / workflow_instance）主键由此生成，避免自增主键的跨库/归档撞键。
 * JDK 25 的 {@link UUID} 仅内置 v4（randomUUID），故此处自实现 v7。
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuid7() {
    }

    /** 生成一个时间有序的 UUIDv7。 */
    public static UUID generate() {
        long ts = System.currentTimeMillis();
        byte[] value = new byte[16];
        // 48-bit 毫秒时间戳（大端）
        value[0] = (byte) (ts >>> 40);
        value[1] = (byte) (ts >>> 32);
        value[2] = (byte) (ts >>> 24);
        value[3] = (byte) (ts >>> 16);
        value[4] = (byte) (ts >>> 8);
        value[5] = (byte) ts;
        // 余下 10 字节随机
        byte[] rnd = new byte[10];
        RANDOM.nextBytes(rnd);
        System.arraycopy(rnd, 0, value, 6, 10);
        // 版本号 7（byte6 高 4 位）
        value[6] = (byte) ((value[6] & 0x0F) | 0x70);
        // 变体 10（byte8 高 2 位）
        value[8] = (byte) ((value[8] & 0x3F) | 0x80);

        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (value[i] & 0xFFL);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (value[i] & 0xFFL);
        }
        return new UUID(msb, lsb);
    }
}
