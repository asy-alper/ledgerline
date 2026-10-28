package dev.ledgerline.api;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.math.BigDecimal;

public final class Json {
    public static final ObjectMapper MAPPER = create();
    private Json() { }

    private static ObjectMapper create() {
        JsonFactory factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                        .maxStringLength(16_384).maxNumberLength(32).build()).build();
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        SimpleModule money = new SimpleModule();
        money.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider provider) throws IOException {
                generator.writeString(value.toPlainString());
            }
        });
        return mapper.registerModule(money);
    }
}
