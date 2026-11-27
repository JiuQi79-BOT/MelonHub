package com.melonhub.rbac.api.shared.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * 将 {@link Instant} 按 Jackson 配置的时区序列化为 {@code yyyy-MM-dd'T'HH:mm:ss.SSSxxx}，
 * 与既有 HTTP JSON 契约（{@code api-shapes.json}）逐字符一致，同时在后端代码内完全消除 {@code java.sql.Timestamp}。
 */
public final class ApiTimestampSerializer extends StdSerializer<Instant> {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    public ApiTimestampSerializer() {
        super(Instant.class);
    }

    @Override
    public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeString(FORMATTER.withZone(serializers.getTimeZone().toZoneId()).format(value));
    }
}
