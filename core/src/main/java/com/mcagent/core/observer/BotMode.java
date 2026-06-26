package com.mcagent.core.observer;

/**
 * The three operating modes for the autonomous observer.
 *
 * <p>These modes collapse the previous four-axis model (enabled/disabled,
 * passive/active, individual/summary, message-mode) into a single, player-legible
 * concept. The mapping from legacy string values is:
 * <ul>
 *   <li>{@code "off"} / observer disabled  → {@link #STANDBY}</li>
 *   <li>{@code "passive"}                  → {@link #WATCH}</li>
 *   <li>{@code "active"}                   → {@link #AUTO}</li>
 * </ul>
 * </p>
 */
public enum BotMode {

    /**
     * Observer off. No autonomous action, no reflexes, no threat scanning.
     * The bot is present but completely silent and inert.
     */
    STANDBY,

    /**
     * Observer on (passive). Observations accumulate in LLM memory and surface
     * only when the player next chats. Reflexes are off in this mode
     * (per Sprint 14 A1 scope; revisited in Sprint 14 C4 when the full
     * behaviour-tree reflex layer is introduced).
     */
    WATCH,

    /**
     * Observer on (active). Observations are dispatched urgently via the
     * urgent-framework channel, allowing the LLM to react autonomously.
     * Full reflex layer is active. Bot can act on threats and opportunities
     * and execute queued plans when idle.
     */
    AUTO
}
