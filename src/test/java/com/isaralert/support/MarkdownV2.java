package com.isaralert.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Validates text the way Telegram parses {@code parse_mode=MarkdownV2}.
 *
 * <p>Telegram rejects a whole message with "400 Bad Request: can't parse entities" when a
 * reserved character is left unescaped or an entity is never closed — a mistake that's easy
 * to make and only shows up in the real chat. See
 * <a href="https://core.telegram.org/bots/api#markdownv2-style">the MarkdownV2 spec</a>.</p>
 */
public final class MarkdownV2 {

    /** Characters that must be escaped with '\' outside of entities (entity markers handled separately). */
    private static final String RESERVED = "()#+-={}.!>";

    private MarkdownV2() {
    }

    /**
     * @return an error description like Telegram's, or empty if the text is valid MarkdownV2
     */
    public static Optional<String> validate(String text) {
        Deque<String> open = new ArrayDeque<>();
        int i = 0;
        int n = text.length();

        while (i < n) {
            char c = text.charAt(i);

            if (c == '\\') {
                if (i + 1 >= n) return error("dangling '\\' at the end of the message", i);
                i += 2;
                continue;
            }

            if (c == '`') {
                boolean pre = text.startsWith("```", i);
                int end = findClosing(text, i + (pre ? 3 : 1), pre ? "```" : "`");
                if (end < 0) return error("can't find end of " + (pre ? "pre" : "code") + " entity", i);
                i = end + (pre ? 3 : 1);
                continue;
            }

            if (c == '*' || c == '~') {
                toggle(open, String.valueOf(c));
                i++;
                continue;
            }

            if (c == '_') {
                String marker = text.startsWith("__", i) ? "__" : "_";
                toggle(open, marker);
                i += marker.length();
                continue;
            }

            if (c == '|') {
                if (!text.startsWith("||", i)) return error("character '|' is reserved and must be escaped", i);
                toggle(open, "||");
                i += 2;
                continue;
            }

            if (c == '[') {
                open.push("[");
                i++;
                continue;
            }

            if (c == ']') {
                if (!"[".equals(open.peek())) return error("character ']' is reserved and must be escaped", i);
                open.pop();
                if (i + 1 >= n || text.charAt(i + 1) != '(') {
                    return error("link text must be followed by '(url)'", i);
                }
                int end = findClosing(text, i + 2, ")");
                if (end < 0) return error("can't find end of link URL", i);
                i = end + 1;
                continue;
            }

            // '>' starts a block quote at the beginning of a line; anywhere else it's reserved
            if (c == '>' && (i == 0 || text.charAt(i - 1) == '\n')) {
                i++;
                continue;
            }

            if (RESERVED.indexOf(c) >= 0) {
                return error("character '" + c + "' is reserved and must be escaped with the preceding '\\'", i);
            }
            i++;
        }

        if (!open.isEmpty()) {
            return Optional.of("can't find end of the entity starting with '" + open.peek() + "'");
        }
        return Optional.empty();
    }

    /** Index of the next unescaped {@code closing} at or after {@code from}, or -1. */
    private static int findClosing(String text, int from, String closing) {
        for (int j = from; j < text.length(); j++) {
            if (text.charAt(j) == '\\') {
                j++;
            } else if (text.startsWith(closing, j)) {
                return j;
            }
        }
        return -1;
    }

    private static void toggle(Deque<String> open, String marker) {
        if (marker.equals(open.peek())) {
            open.pop();
        } else {
            open.push(marker);
        }
    }

    private static Optional<String> error(String reason, int offset) {
        return Optional.of(reason + " (byte offset " + offset + ")");
    }
}
