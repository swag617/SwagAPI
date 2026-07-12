package com.SwagDev.SwagAPI.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ColorUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern STRIP_HEX_PATTERN = Pattern.compile("§x(§[0-9A-Fa-f]){6}");
    private static final Pattern STRIP_SINGLE_PATTERN = Pattern.compile("§[0-9A-Fa-fK-Ok-oRrXx]");

    private ColorUtil() {}

    public static String colorize(String input) {
        if (input == null) return null;
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append('§').append(c);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    public static Component toComponent(String input) {
        return LegacyComponentSerializer.legacySection().deserialize(colorize(input));
    }

    public static List<Component> toComponents(List<String> input) {
        return input.stream().map(ColorUtil::toComponent).collect(Collectors.toList());
    }

    public static String strip(String input) {
        if (input == null) return null;
        String result = STRIP_HEX_PATTERN.matcher(input).replaceAll("");
        result = STRIP_SINGLE_PATTERN.matcher(result).replaceAll("");
        return result;
    }
}
