package com.mcagent.core.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BotProperties.ChatProperties#getEffectiveWorkingWindow()}.
 *
 * <p>Covers the three branches of the deprecation compatibility logic:</p>
 * <ol>
 *   <li>Both properties at defaults → default window (12) is returned, no WARN.</li>
 *   <li>{@code workingWindow} explicitly set → that value is returned, no WARN.</li>
 *   <li>Only {@code maxHistory} changed → its value is returned and exactly one WARN is logged.</li>
 * </ol>
 */
class LangChain4jConfigTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger chatPropertiesLogger;

    @BeforeEach
    void attachLogAppender() {
        chatPropertiesLogger = (Logger) LoggerFactory.getLogger(BotProperties.ChatProperties.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        chatPropertiesLogger.addAppender(logAppender);
        chatPropertiesLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void detachLogAppender() {
        chatPropertiesLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void getEffectiveWorkingWindow_bothDefaults_returnsDefaultWindow_noWarn() {
        BotProperties.ChatProperties chat = new BotProperties.ChatProperties();
        // defaults: workingWindow=12, maxHistory=20

        int result = chat.getEffectiveWorkingWindow();

        assertThat(result).isEqualTo(BotProperties.ChatProperties.DEFAULT_WORKING_WINDOW);
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    void getEffectiveWorkingWindow_workingWindowOverridden_returnsWorkingWindow_noWarn() {
        BotProperties.ChatProperties chat = new BotProperties.ChatProperties();
        chat.setWorkingWindow(20);
        // maxHistory stays at default (20)

        int result = chat.getEffectiveWorkingWindow();

        assertThat(result).isEqualTo(20);
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    void getEffectiveWorkingWindow_onlyMaxHistoryChanged_returnsMaxHistory_logsOneWarn() {
        BotProperties.ChatProperties chat = new BotProperties.ChatProperties();
        chat.setMaxHistory(30);
        // workingWindow stays at default (12)

        int result = chat.getEffectiveWorkingWindow();

        assertThat(result).isEqualTo(30);

        List<ILoggingEvent> warns = warnMessages();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage())
                .contains("bot.chat.max-history")
                .contains("DEPRECATED")
                .contains("working-window");
    }

    private List<ILoggingEvent> warnMessages() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
    }
}
