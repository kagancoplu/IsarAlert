package com.isaralert.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Makes sure the test-side MarkdownV2 validator agrees with Telegram's rules. */
class MarkdownV2Test {

    @ParameterizedTest
    @ValueSource(strings = {
            "plain text without specials",
            "*bold* and _italic_ and __underline__ and ~strike~ and ||spoiler||",
            "escaped \\. \\! \\- \\( \\) \\# \\+ \\= \\{ \\} \\> \\| \\_ \\* \\[ \\] \\~ \\` \\\\",
            "👉 [View Listing](https://www.wg-gesucht.de/wohnungen-in-Muenchen.123.html)",
            "inline `code with . and !` here",
            "```\npre block with . ( )\n```",
            ">a block quote",
            "*bold with \\. inside*"
    })
    void acceptsValidText(String text) {
        assertThat(MarkdownV2.validate(text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Sentence ending with a dot.",
            "Exclamation!",
            "rent (warm)",
            "WG-Gesucht",
            "a = b",
            "*unclosed bold",
            "_unclosed italic",
            "stray ] bracket",
            "[link text without url]",
            "[link](unclosed",
            "`unclosed code",
            "single | pipe",
            "text > not at line start",
            "dangling backslash \\"
    })
    void rejectsInvalidText(String text) {
        assertThat(MarkdownV2.validate(text)).isPresent();
    }

    @Test
    void reportsWhichCharacterIsWrong() {
        assertThat(MarkdownV2.validate("Hello."))
                .hasValueSatisfying(reason -> assertThat(reason).contains("'.'"));
    }
}
