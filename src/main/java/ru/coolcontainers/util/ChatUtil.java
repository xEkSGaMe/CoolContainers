package ru.coolcontainers.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

/**
 * Выравнивание многострочных сообщений по центру чата.
 *
 * <p>В MiniMessage нет тега {@code <center>}, а у компонентов чата нет
 * свойства выравнивания (в отличие от {@code TextDisplay}). Поэтому строки
 * дополняются пробелами слева. Ширина считается в пикселях стандартного
 * шрифта Minecraft: строка чата — 320 px, пробел — 4 px.</p>
 */
public final class ChatUtil {

    private static final int CHAT_WIDTH_PX = 320;
    private static final int SPACE_WIDTH_PX = 4;

    /**
     * Ширина глифов ASCII 0x20..0x7E в стандартном шрифте Minecraft.
     * Кириллица и прочие не-ASCII символы в дефолтном шрифте занимают
     * примерно 6 px, поэтому для них используется эта ширина.
     */
    private static final int[] ASCII_WIDTH = {
            4, 2, 5, 6, 6, 6, 6, 3, 5, 5,  // ' ' ! " # $ % & ' ( )
            5, 6, 2, 6, 2, 6, 6, 6, 6, 6,  // * + , - . / 0-4
            6, 6, 6, 6, 6, 6, 2, 2, 5, 6,  // 5-9 : ; < =
            5, 6, 7, 6, 6, 6, 6, 6, 6, 6,  // > ? @ A B C D E F G
            6, 4, 6, 6, 6, 6, 6, 6, 6, 6,  // H I J K L M N O P Q
            6, 6, 6, 6, 6, 6, 6, 6, 4, 6,  // R S T U V W X Y Z [
            4, 6, 6, 3, 6, 6, 6, 6, 6, 6,  // \ ] ^ _ ` a b c d e
            5, 6, 6, 2, 6, 5, 3, 6, 6, 6,  // f g h i j k l m n o
            6, 6, 6, 6, 6, 4, 6, 6, 6, 6,  // p q r s t u v w x y
            6, 5, 2, 5, 7                  // z { | } ~
    };

    private ChatUtil() {
    }

    /**
     * Центрирует каждую строку MiniMessage-блока и склеивает их через
     * перевод строки. Пустые строки сохраняются как отступы.
     */
    public static @NotNull Component centerLines(@NotNull String miniMessage) {
        String normalized = miniMessage.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isBlank() || "none".equalsIgnoreCase(normalized.trim())) {
            return Component.empty();
        }

        TextComponent.Builder out = Component.text();
        String[] lines = normalized.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append(Component.newline());
            out.append(centerLine(lines[i]));
        }
        return out.build();
    }

    private static @NotNull Component centerLine(@NotNull String miniMessageLine) {
        Component parsed = MiniMessageUtil.parse(miniMessageLine);
        String plain = PlainTextComponentSerializer.plainText().serialize(parsed);

        int textWidth = textWidth(plain);
        int spaces = Math.max(0, (CHAT_WIDTH_PX - textWidth) / 2 / SPACE_WIDTH_PX);
        if (spaces == 0) return parsed;

        return Component.text(" ".repeat(spaces)).append(parsed);
    }

    private static int textWidth(@NotNull String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Легаси-код §x не занимает места.
            if (c == '\u00A7' && i + 1 < text.length()) {
                i++;
                continue;
            }
            width += glyphWidth(c);
        }
        return width;
    }

    private static int glyphWidth(char c) {
        if (c >= ' ' && c <= '~') {
            return ASCII_WIDTH[c - ' '];
        }
        if (c == '\u00A0' || Character.isWhitespace(c)) {
            return SPACE_WIDTH_PX;
        }
        return 6;
    }
}
