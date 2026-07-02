package com.dataweave.api.infrastructure;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;

/**
 * 全局日期格式配置 —— 所有 LocalDateTime 字段序列化为带时区的 UTC ISO-8601 字符串。
 * Spring Boot 4 + Jackson 3 版本。
 *
 * 序列化：LocalDateTime 假定为 JVM 默认时区 → Instant → UTC ISO 字符串 (e.g. 2026-07-01T09:42:19Z)
 * 反序列化：优先按 ISO Instant 解析转回 JVM 默认时区的 LocalDateTime，兼容旧格式 yyyy-MM-dd HH:mm:ss
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public JsonMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("DataWeaveDateTimeModule");

            // 序列化：LocalDateTime → 假定 JVM 默认时区 → Instant → UTC ISO 字符串
            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(null) {
                @Override
                public void serialize(LocalDateTime value, JsonGenerator gen,
                                      SerializationContext context) throws JacksonException {
                    String iso = value.atZone(ZoneId.systemDefault()).toInstant().toString();
                    gen.writeString(iso);
                }
            });

            // 反序列化：优先 ISO Instant 解析 → JVM 默认时区 LocalDateTime，兼容旧格式
            module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DE_FORMATTER) {
                @Override
                public LocalDateTime deserialize(tools.jackson.core.JsonParser p,
                                                 tools.jackson.databind.DeserializationContext ctxt)
                                                 throws JacksonException {
                    String text = p.getText();
                    try {
                        return Instant.parse(text).atZone(ZoneId.systemDefault()).toLocalDateTime();
                    } catch (java.time.format.DateTimeParseException e) {
                        return super.deserialize(p, ctxt);
                    }
                }
            });

            builder.addModule(module);
        };
    }
}
