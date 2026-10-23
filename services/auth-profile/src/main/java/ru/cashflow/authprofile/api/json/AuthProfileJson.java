package ru.cashflow.authprofile.api.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/** Apply once at startup, before the mapper is used by HTTP message converters. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class AuthProfileJson {

    public static void configure(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.setConfig(
                mapper.getDeserializationConfig().without(MapperFeature.ALLOW_COERCION_OF_SCALARS));
        // ALLOW_COERCION_OF_SCALARS alone does not prohibit number -> String conversion.
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }
}
