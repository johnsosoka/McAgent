package com.mcagent.core.reflex;

import com.mcagent.core.service.BotOperations.HealthStatus;
import com.mcagent.core.service.BotOperations.ThreatInfo;

import java.util.List;

/**
 * Immutable point-in-time snapshot of world state consumed by {@link SurvivalReflexes}.
 *
 * <p>Built from {@link com.mcagent.fabric.cache.WorldStateCache.Snapshot} in
 * {@code AutonomousObserver} before each reflex tick. All fields use only
 * {@code core/} types so this record can be constructed and tested without
 * any Minecraft classes on the classpath.</p>
 *
 * <p>Inventory checks (e.g. "has torch") are performed via {@code bot.hasItem(...)}
 * directly in {@link SurvivalReflexes} rather than through a summary string,
 * so no inventory field is needed here.</p>
 *
 * @param health     current health and hunger status
 * @param worldTime  day-time tick (0–24000; &gt;13000 is night)
 * @param isNight    convenience flag derived from worldTime
 * @param lightLevel combined sky+block light at the bot's feet (0–15)
 * @param threats    nearby hostile entities within scan radius
 */
public record ReflexSnapshot(
        HealthStatus health,
        int worldTime,
        boolean isNight,
        int lightLevel,
        List<ThreatInfo> threats
) {}
