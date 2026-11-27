package com.melonhub.rbac.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * R31 HTTP JSON 响应形状归一化与比对工具，供单测与集成测试共用。
 */
public final class JsonShapeSupport {
    public static final String DEFAULT_TIME_ZONE = "Asia/Shanghai";
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}.*)?");
    private static final Pattern NUMERIC_KEY = Pattern.compile("\\d+");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private JsonShapeSupport() {}

    public static Path recordedShapesPath() {
        Path direct = Path.of("src/test/resources/contract/api-shapes.json");
        if (Files.exists(direct)) {
            return direct;
        }
        return Path.of("backend/src/test/resources/contract/api-shapes.json");
    }

    public static ObjectMapper springCompatibleMapper(String timeZoneId) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setTimeZone(TimeZone.getTimeZone(timeZoneId));
        return mapper;
    }

    public static JsonNode shape(JsonNode node) {
        if (node == null || node.isMissingNode()) return TextNode.valueOf("absent");
        if (node.isNull()) return TextNode.valueOf("null");
        if (node.isBoolean()) return TextNode.valueOf("boolean");
        if (node.isIntegralNumber()) return TextNode.valueOf("integer");
        if (node.isNumber()) return TextNode.valueOf("decimal");
        if (node.isTextual()) {
            String text = node.asText();
            return TextNode.valueOf(DATE_PATTERN.matcher(text).matches() ? "datetime:" + text.replaceAll("\\d", "d") : "string");
        }
        if (node.isArray()) {
            ArrayNode array = NODES.arrayNode();
            JsonNode merged = null;
            for (JsonNode item : node) {
                merged = merged == null ? shape(item) : merge(merged, shape(item));
            }
            if (merged != null) {
                array.add(merged);
            }
            return array;
        }
        TreeMap<String, JsonNode> fields = new TreeMap<>();
        node.fields().forEachRemaining(e -> {
            String key = NUMERIC_KEY.matcher(e.getKey()).matches() ? "<number>" : e.getKey();
            JsonNode value = shape(e.getValue());
            fields.merge(key, value, JsonShapeSupport::merge);
        });
        ObjectNode object = NODES.objectNode();
        fields.forEach(object::set);
        return object;
    }

    public static JsonNode merge(JsonNode a, JsonNode b) {
        if (a.equals(b)) return a;
        if (a.isObject() && b.isObject()) {
            TreeSet<String> names = new TreeSet<>();
            a.fieldNames().forEachRemaining(names::add);
            b.fieldNames().forEachRemaining(names::add);
            ObjectNode object = NODES.objectNode();
            for (String name : names) {
                JsonNode left = a.has(name) ? a.get(name) : TextNode.valueOf("absent");
                JsonNode right = b.has(name) ? b.get(name) : TextNode.valueOf("absent");
                object.set(name, merge(left, right));
            }
            return object;
        }
        if (a.isArray() && b.isArray()) {
            if (a.isEmpty()) return b;
            if (b.isEmpty()) return a;
            return NODES.arrayNode().add(merge(a.get(0), b.get(0)));
        }
        if (a.isTextual() && b.isTextual()) {
            TreeSet<String> kinds = new TreeSet<>(List.of(a.asText().split("\\|")));
            kinds.addAll(List.of(b.asText().split("\\|")));
            return TextNode.valueOf(String.join("|", kinds));
        }
        List<JsonNode> choices = new ArrayList<>();
        for (JsonNode side : new JsonNode[]{a, b}) {
            if (side.has("oneOf")) side.get("oneOf").forEach(choices::add);
            else choices.add(side);
        }
        choices.sort((x, y) -> x.toString().compareTo(y.toString()));
        ObjectNode union = NODES.objectNode();
        ArrayNode list = union.putArray("oneOf");
        choices.stream().distinct().forEach(list::add);
        return union;
    }

    public static void differences(String path, JsonNode want, JsonNode got, List<String> problems) {
        if (want.equals(got)) return;
        if (want.isObject() && got.isObject() && !want.has("oneOf") && !got.has("oneOf")) {
            TreeSet<String> names = new TreeSet<>();
            want.fieldNames().forEachRemaining(names::add);
            got.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (!got.has(name)) problems.add(path + "." + name + ": missing (was " + want.get(name) + ")");
                else if (!want.has(name)) problems.add(path + "." + name + ": new field " + got.get(name));
                else differences(path + "." + name, want.get(name), got.get(name), problems);
            }
            return;
        }
        if (want.isArray() && got.isArray() && !want.isEmpty() && !got.isEmpty()) {
            differences(path + "[]", want.get(0), got.get(0), problems);
            return;
        }
        problems.add(path + ": expected " + want + " but was " + got);
    }
}
