package ru.cashflow.forecast.api.json;

import java.io.IOException;
import java.math.BigDecimal;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class ForecastJson {
    private ForecastJson() { }

    public static ObjectMapper mapper() {
        var factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100)
                .maxStringLength(8192).maxNumberLength(32).build()).build();
        return JsonMapper.builder(factory).addModule(new JavaTimeModule()).addModule(moneyModule())
                .addMixIn(org.springframework.http.ProblemDetail.class, org.springframework.http.converter.json.ProblemDetailJacksonMixin.class)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    public static SimpleModule moneyModule() {
        var module = new SimpleModule();
        module.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator out, SerializerProvider provider) throws IOException {
                out.writeString(value.toPlainString());
            }
        });
        module.addDeserializer(BigDecimal.class, new JsonDeserializer<>() {
            @Override
            public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                if (!parser.hasToken(JsonToken.VALUE_STRING)
                        || !parser.getText().matches("-?(0|[1-9][0-9]{0,16})\\.[0-9]+")) {
                    return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
                }
                return new BigDecimal(parser.getText());
            }
        });
        return module;
    }
}
