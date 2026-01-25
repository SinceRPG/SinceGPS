package net.danh.sinceGPS.utils;

import net.danh.sinceGPS.SinceGPS;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class ColorUtils {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    public static Component parse(String input) {
        String converted = input.replace("&", "§")
                .replace("§0", "<black>").replace("§1", "<dark_blue>").replace("§2", "<dark_green>")
                .replace("§3", "<dark_aqua>").replace("§4", "<dark_red>").replace("§5", "<dark_purple>")
                .replace("§6", "<gold>").replace("§7", "<gray>").replace("§8", "<dark_gray>")
                .replace("§9", "<blue>").replace("§a", "<green>").replace("§b", "<aqua>")
                .replace("§c", "<red>").replace("§d", "<light_purple>").replace("§e", "<yellow>")
                .replace("§f", "<white>").replace("§l", "<bold>");
        return MINI.deserialize(converted);
    }

    public static Component parseWithPrefix(String input) {
        String prefix = SinceGPS.inst().getMsg().getString("prefix");
        return parse(prefix + input);
    }

    public static String stripColor(String inputWithColor) {
        Component c = parse(inputWithColor);
        return PlainTextComponentSerializer.plainText().serialize(c);
    }
}