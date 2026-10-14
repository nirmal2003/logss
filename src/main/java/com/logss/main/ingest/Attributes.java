package com.logss.main.ingest;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts OTLP attributes to plain Java values and reads them by any of several names. */
final class Attributes {

    private Attributes() {
    }

    static Map<String, Object> toMap(List<KeyValue> keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (KeyValue kv : keyValues) {
            map.put(kv.getKey(), toJava(kv.getValue()));
        }
        return map;
    }

    static Object toJava(AnyValue value) {
        return switch (value.getValueCase()) {
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case INT_VALUE -> value.getIntValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case ARRAY_VALUE -> value.getArrayValue().getValuesList().stream().map(Attributes::toJava).toList();
            case KVLIST_VALUE -> toMap(value.getKvlistValue().getValuesList());
            case BYTES_VALUE -> HexFormat.of().formatHex(value.getBytesValue().toByteArray());
            default -> null;
        };
    }

    /** First non-null value among the given keys, as text. Semantic conventions renamed many keys over time. */
    static String str(Map<String, Object> attrs, String... keys) {
        for (String key : keys) {
            Object value = attrs.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    static Integer integer(Map<String, Object> attrs, String... keys) {
        for (String key : keys) {
            Object value = attrs.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text) {
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    // try the next key
                }
            }
        }
        return null;
    }
}
