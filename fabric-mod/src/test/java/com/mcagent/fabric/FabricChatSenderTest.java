package com.mcagent.fabric;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FabricChatSender}.
 *
 * <p>Minecraft's class hierarchy cannot be instantiated in unit tests, so we test only the
 * pure decision logic extracted into {@link FabricChatSender#decide(long, long, long, String)}.
 * That helper encodes all four outcomes without touching any Minecraft state.</p>
 */
class FabricChatSenderTest {

    private static final long INTERVAL_MS = 1200;

    @Test
    void decide_returnsEmpty_whenMessageIsNull() {
        assertThat(FabricChatSender.decide(1000, 0, INTERVAL_MS, null))
                .isEqualTo(FabricChatSender.SendResult.EMPTY);
    }

    @Test
    void decide_returnsEmpty_whenMessageIsBlank() {
        assertThat(FabricChatSender.decide(1000, 0, INTERVAL_MS, "   "))
                .isEqualTo(FabricChatSender.SendResult.EMPTY);
    }

    @Test
    void decide_returnsRateLimited_whenIntervalNotElapsed() {
        long lastSend = 1000L;
        long now = lastSend + INTERVAL_MS - 1; // one ms short
        assertThat(FabricChatSender.decide(now, lastSend, INTERVAL_MS, "hello"))
                .isEqualTo(FabricChatSender.SendResult.RATE_LIMITED);
    }

    @Test
    void decide_returnsSent_whenIntervalExactlyElapsed() {
        long lastSend = 1000L;
        long now = lastSend + INTERVAL_MS; // exactly at boundary
        assertThat(FabricChatSender.decide(now, lastSend, INTERVAL_MS, "hello"))
                .isEqualTo(FabricChatSender.SendResult.SENT);
    }

    @Test
    void decide_returnsSent_whenIntervalSurpassed() {
        long lastSend = 1000L;
        long now = lastSend + INTERVAL_MS + 500;
        assertThat(FabricChatSender.decide(now, lastSend, INTERVAL_MS, "hello"))
                .isEqualTo(FabricChatSender.SendResult.SENT);
    }

    @Test
    void decide_returnsSent_whenLastSendTimeIsZero() {
        // First send ever: lastSendTime starts at 0
        assertThat(FabricChatSender.decide(System.currentTimeMillis(), 0, INTERVAL_MS, "hello"))
                .isEqualTo(FabricChatSender.SendResult.SENT);
    }

    @Test
    void setMinIntervalMs_changesStaticField() {
        long original = FabricChatSender.MIN_INTERVAL_MS;
        try {
            FabricChatSender.setMinIntervalMs(2500);
            assertThat(FabricChatSender.MIN_INTERVAL_MS).isEqualTo(2500);
        } finally {
            FabricChatSender.setMinIntervalMs(original);
        }
    }
}
