package com.bobo.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * JSON 时间格式统一。
 * <p>
 * 默认的 ISO 格式（{@code 2026-01-08T09:00:00}）对前端和 curl 都不友好，
 * 这里统一输出 {@code yyyy-MM-dd HH:mm:ss}；输入则宽松一些，
 * 兼容 {@code 2026-01-09 15:00}、{@code 2026-01-09T15:00:00}、{@code 2026-01-09} 等写法。
 *
 * @author bobo
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME));
            module.addDeserializer(LocalDateTime.class, new LenientLocalDateTimeDeserializer());
            module.addSerializer(LocalDate.class, new LocalDateSerializer(DATE));
            module.addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE));
            builder.modules(module);
        };
    }

    /**
     * 宽松的 LocalDateTime 反序列化。
     * <p>
     * 注意不要把「日期时间格式」和「纯日期格式」混在一张表里判断：
     * {@code DateTimeFormatter.toString()} 返回的是内部结构描述而不是模式串，
     * 用字符串比较去区分格式是行不通的（第一版就踩了这个坑，
     * 表现为纯日期输入永远解析失败）。这里对两种语义分别处理。
     */
    static class LenientLocalDateTimeDeserializer extends LocalDateTimeDeserializer {

        private static final DateTimeFormatter[] DATE_TIME_FORMATS = {
                DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
        };

        LenientLocalDateTimeDeserializer() {
            super(DATE_TIME);
        }

        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String raw = parser.getText();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String text = raw.trim();
            String normalized = text.replace('T', ' ');

            for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
                try {
                    // ISO 格式保留了 T 分隔符，用它自己的串解析
                    String candidate = formatter == DateTimeFormatter.ISO_LOCAL_DATE_TIME ? text : normalized;
                    return LocalDateTime.parse(candidate, formatter);
                } catch (DateTimeParseException ignored) {
                    // 换下一种格式继续尝试
                }
            }
            // 只给了日期（如 2026-01-09）时，按当天 00:00:00 处理
            try {
                return LocalDate.parse(text, DATE).atStartOfDay();
            } catch (DateTimeParseException e) {
                throw new IOException("无法解析的时间格式：" + text, e);
            }
        }
    }

    /** 供测试与工具类复用的默认 ObjectMapper */
    public static ObjectMapper newObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME));
        mapper.registerModule(module);
        return mapper;
    }
}
