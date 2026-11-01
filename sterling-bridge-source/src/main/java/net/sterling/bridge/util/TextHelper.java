package net.sterling.bridge.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextHelper {
    private static final Pattern URI_PATTERN = Pattern.compile("(?:https?|sterling)://\\S+");
    private static final Pattern LEGACY_COLOR_PATTERN = Pattern.compile("&([0-9a-fk-or])", Pattern.CASE_INSENSITIVE);

    public static String replacePlaceholders(String text, Map<String, String> placeholders) {
        if (text == null || placeholders == null) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    public static Component formatComponent(String rawMessage, Map<String, String> placeholders) {
        String formatted = replacePlaceholders(rawMessage, placeholders);
        return parseText(formatted);
    }

    public static Component parseText(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }

        StringBuilder legacy = new StringBuilder(input.length());
        Matcher uriMatcher = URI_PATTERN.matcher(input);
        int offset = 0;
        while (uriMatcher.find()) {
            legacy.append(convertLegacyColors(input.substring(offset, uriMatcher.start())));
            legacy.append(uriMatcher.group());
            offset = uriMatcher.end();
        }
        legacy.append(convertLegacyColors(input.substring(offset)));
        MutableComponent component = Component.literal(legacy.toString());
        return component;
    }

    private static String convertLegacyColors(String text) {
        return LEGACY_COLOR_PATTERN.matcher(text).replaceAll("§$1");
    }
}
