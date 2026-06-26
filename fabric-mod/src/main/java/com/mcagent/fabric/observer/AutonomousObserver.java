package com.mcagent.fabric.observer;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.reflex.ReflexSnapshot;
import com.mcagent.core.reflex.SurvivalReflexes;
import com.mcagent.core.service.BotOperations.HealthStatus;
import com.mcagent.core.service.BotOperations.Location;
import com.mcagent.core.service.BotOperations.ThreatInfo;
import com.mcagent.core.service.ops.BotControlOperations;
import com.mcagent.core.service.ops.InventoryOperations;
import com.mcagent.core.service.ops.ObservationOperations;
import com.mcagent.core.task.TaskExecutor;
import com.mcagent.core.task.TaskSnapshot;
import com.mcagent.core.task.TaskState;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.JobTracker;
import com.mcagent.core.telemetry.RunLog;
import com.mcagent.fabric.cache.WorldStateCache;
import com.mcagent.fabric.queue.BotEventQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class AutonomousObserver {

    private static final Logger log = LoggerFactory.getLogger(AutonomousObserver.class);

    /**
     * Abstracts away reading alive/dead state from the local player.
     * Returns {@code null} when no player is available (boot, dimension change, test).
     * The returned record holds the data extracted on the Minecraft client thread.
     */
    public record PlayerState(boolean isDead, int x, int y, int z) {}

    private final ObservationOperations botOperations;
    private final BotEventQueue eventQueue;
    private final BotProperties.ObservationProperties config;
    private final Map<String, Long> debounceMap;
    private final JobTracker jobTracker;
    private final Counters counters;
    private final RunLog runLog;
    private final DeathDetector deathDetector;
    // Nullable: cancel() is called on death. May be absent in lightweight test wiring.
    private final BotControlOperations botControl;
    // Supplies the local player's state each tick; null return = no player present.
    private final Supplier<PlayerState> playerStateSupplier;
    // Nullable: when present, scan() reads from the cache instead of the bridge.
    private final WorldStateCache worldStateCache;
    // Nullable: when present, tickReflexes is called in active mode before scan reporting.
    private final SurvivalReflexes survivalReflexes;
    private final BotProperties botProps;
    // Nullable: when present, threat observations are suppressed during low-risk task execution.
    private final TaskExecutor taskExecutor;
    // Nullable: when present, heartbeat messages include inventory summary.
    private final InventoryOperations inventoryOps;

    private int tickCounter;
    private volatile boolean enabled;

    /**
     * Full constructor used in production wiring (via {@code McAgentFabricMod}).
     *
     * @param worldStateCache     read-only cache refreshed on the client tick; may be
     *                            {@code null} in lightweight test wiring (falls back to
     *                            live bridge calls).
     * @param playerStateSupplier reads alive/dead state from the local player each tick.
     *                            Must not be null; return null from the supplier when no
     *                            player is available (boot, dimension change).
     * @param survivalReflexes    deterministic reflex layer; may be {@code null} in
     *                            lightweight test wiring that does not exercise reflexes.
     * @param botProps            full bot properties, used to gate reflexes by
     *                            {@code reflex.enabled}; may be {@code null} when
     *                            {@code survivalReflexes} is also null.
     * @param taskExecutor        task executor used to detect active Plans; may be
     *                            {@code null} in lightweight test wiring that does not
     *                            exercise task-aware suppression.
     * @param inventoryOps        inventory operations used to retrieve the inventory summary
     *                            for heartbeat messages; may be {@code null} in lightweight
     *                            test wiring (heartbeat will omit inventory info).
     */
    public AutonomousObserver(ObservationOperations botOperations,
                              BotEventQueue eventQueue,
                              BotProperties.ObservationProperties config,
                              JobTracker jobTracker,
                              Counters counters,
                              RunLog runLog,
                              BotControlOperations botControl,
                              Supplier<PlayerState> playerStateSupplier,
                              WorldStateCache worldStateCache,
                              SurvivalReflexes survivalReflexes,
                              BotProperties botProps,
                              TaskExecutor taskExecutor,
                              InventoryOperations inventoryOps) {
        if (botOperations == null) {
            throw new IllegalArgumentException("botOperations must not be null");
        }
        if (eventQueue == null) {
            throw new IllegalArgumentException("eventQueue must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (jobTracker == null) {
            throw new IllegalArgumentException("jobTracker must not be null");
        }
        if (counters == null) {
            throw new IllegalArgumentException("counters must not be null");
        }
        if (playerStateSupplier == null) {
            throw new IllegalArgumentException("playerStateSupplier must not be null");
        }
        this.botOperations = botOperations;
        this.eventQueue = eventQueue;
        this.config = config;
        this.jobTracker = jobTracker;
        this.counters = counters;
        this.runLog = runLog;
        this.botControl = botControl;
        this.playerStateSupplier = playerStateSupplier;
        this.worldStateCache = worldStateCache;
        this.survivalReflexes = survivalReflexes;
        this.botProps = botProps;
        this.taskExecutor = taskExecutor;
        this.inventoryOps = inventoryOps;
        this.debounceMap = new ConcurrentHashMap<>();
        this.deathDetector = new DeathDetector();
        this.enabled = config.isEnabled();
    }

    /**
     * Legacy three-argument constructor retained for existing tests that do not
     * exercise death detection or cache-based reads. Death-related fields and the
     * world-state cache default to no-ops / null.
     *
     * @deprecated Use the full constructor in production wiring.
     */
    @Deprecated
    public AutonomousObserver(ObservationOperations botOperations, BotEventQueue eventQueue,
                              BotProperties.ObservationProperties config) {
        this(botOperations, eventQueue, config,
                new JobTracker(),
                new Counters(),
                null,
                null,
                () -> null,   // no Minecraft classes loaded in test context
                null,         // no cache in lightweight test wiring
                null,         // no reflexes in lightweight test wiring
                null,         // no botProps in lightweight test wiring
                null,         // no taskExecutor in lightweight test wiring
                null);        // no inventoryOps in lightweight test wiring
    }

    public void onTick() {
        checkDeath();

        if (!enabled) {
            return;
        }
        if (++tickCounter < config.getScanIntervalTicks()) {
            return;
        }
        tickCounter = 0;
        scan();
    }

    /**
     * Check the local player's alive/dead state each tick and fire events on transitions.
     *
     * <p>This runs unconditionally (even when {@link #enabled} is false) because death
     * detection must work regardless of whether the observation loop is active.</p>
     */
    private void checkDeath() {
        PlayerState state = playerStateSupplier.get();
        if (state == null) {
            return;
        }

        Location location = new Location(state.x(), state.y(), state.z());
        Object event = deathDetector.tick(state.isDead(), location);

        if (event instanceof DeathDetector.DeathEvent death) {
            handleDeath(death.location());
        } else if (event instanceof DeathDetector.RespawnEvent respawn) {
            handleRespawn(respawn.location());
        }
    }

    private void handleDeath(Location location) {
        int x = location.x();
        int y = location.y();
        int z = location.z();

        JobTracker.Job suspendedJob = jobTracker.getCurrentJob().orElse(null);
        jobTracker.recordDeath(location, suspendedJob);

        counters.inc("bot.deaths");

        String jobDesc = suspendedJob != null ? suspendedJob.description() : "";
        if (runLog != null) {
            runLog.log("bot_death", Map.of("x", x, "y", y, "z", z,
                    "suspended_job", jobDesc));
        }

        eventQueue.publishFramework(
                "You died at (" + x + "," + y + "," + z + "). Current task suspended.");

        if (botControl != null) {
            try {
                botControl.cancel();
            } catch (Exception e) {
                log.warn("Could not cancel Baritone after death: {}", e.getMessage());
            }
        }

        log.info("Bot death detected at ({},{},{}), suspended job: {}", x, y, z, jobDesc);
    }

    private void handleRespawn(Location location) {
        int x = location.x();
        int y = location.y();
        int z = location.z();

        if (runLog != null) {
            runLog.log("bot_respawn", Map.of("x", x, "y", y, "z", z));
        }

        eventQueue.publishFramework(
                "Respawned at (" + x + "," + y + "," + z + "). Task suspended; awaiting player decision.");

        log.info("Bot respawn detected at ({},{},{})", x, y, z);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            tickCounter = 0;
        }
        log.info("AutonomousObserver enabled={}", enabled);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getMode() {
        return config.getMode();
    }

    public void setMode(String mode) {
        if (!"passive".equalsIgnoreCase(mode) && !"active".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("mode must be 'passive' or 'active'");
        }
        config.setMode(mode.toLowerCase());
        log.info("AutonomousObserver mode set to {}", mode.toLowerCase());
    }

    public void setMessageMode(String messageMode) {
        if (!"individual".equalsIgnoreCase(messageMode) && !"summary".equalsIgnoreCase(messageMode)) {
            throw new IllegalArgumentException("messageMode must be 'individual' or 'summary'");
        }
        config.setMessageMode(messageMode.toLowerCase());
    }

    private void scan() {
        // Reflex layer — deterministic, sub-LLM, silent. Runs before threat/opportunity
        // reporting so survival actions fire first. Only active in "active" mode.
        if ("active".equalsIgnoreCase(config.getMode())
                && survivalReflexes != null
                && botProps != null
                && botProps.getReflex().isEnabled()) {
            WorldStateCache.Snapshot cached = (worldStateCache != null) ? worldStateCache.get() : null;
            if (cached != null && !cached.isEmpty()) {
                ReflexSnapshot rsnap = new ReflexSnapshot(
                        cached.health(),
                        cached.worldTime(),
                        cached.isNight(),
                        cached.lightLevel(),
                        cached.threats()
                );
                SurvivalReflexes.Action taken = survivalReflexes.tickReflexes(rsnap);
                if (taken != SurvivalReflexes.Action.NONE) {
                    if (runLog != null) {
                        runLog.log("reflex_action", Map.of("action", taken.name()));
                    }
                    // Rule 1b: critical HP with no threats — publish a framework message so
                    // the LLM is informed without SurvivalReflexes coupling to fabric messaging.
                    if (taken == SurvivalReflexes.Action.CRITICAL_NO_THREAT) {
                        float hp = rsnap.health().health();
                        float maxHp = rsnap.health().maxHealth();
                        eventQueue.publishFramework(
                                "Warning: HP critical (" + (int) hp + "/" + (int) maxHp
                                        + ") with no nearby threats. Possible environmental damage (fall, fire, drowning).");
                    }
                }
            }
        }

        maybeEmitHeartbeat();

        cleanExpiredDebounces();

        var threats = threatsFromCacheOrBridge();
        var opportunities = collectOpportunities();

        var newThreats = filterDebouncedThreats(threats);
        newThreats = suppressLowRiskThreats(newThreats);
        var newOpportunities = filterDebouncedEntities(opportunities);

        if (newThreats.isEmpty() && newOpportunities.isEmpty()) {
            return;
        }

        log.debug("Scan found {} new threats, {} new opportunities",
                newThreats.size(), newOpportunities.size());

        if ("summary".equalsIgnoreCase(config.getMessageMode())) {
            publishSummary(newThreats, newOpportunities);
            debounceAllThreats(newThreats);
            debounceAllEntities(newOpportunities);
        } else {
            publishClosest(newThreats, newOpportunities);
        }
    }

    /**
     * Return the threat list from the cache when it is warm; fall back to the bridge
     * live call (blocking the client thread) when the cache is empty or absent.
     */
    private List<ThreatInfo> threatsFromCacheOrBridge() {
        if (worldStateCache != null) {
            WorldStateCache.Snapshot snap = worldStateCache.get();
            if (!snap.isEmpty()) {
                return snap.threatsWithinRadius(config.getThreatRadius());
            }
        }
        return botOperations.getNearbyThreats(config.getThreatRadius());
    }

    private List<EntityInfo> collectOpportunities() {
        var opportunities = new ArrayList<EntityInfo>();
        if (!config.isTrackPassiveMobs()) {
            return opportunities;
        }
        for (var type : config.getPassiveMobTypes()) {
            opportunities.addAll(entitiesFromCacheOrBridge(type, config.getPassiveRadius()));
        }
        return opportunities;
    }

    /**
     * Return entities of the given type from the cache when it is warm; fall back to
     * a live bridge call otherwise.
     */
    private List<EntityInfo> entitiesFromCacheOrBridge(String entityType, int radius) {
        if (worldStateCache != null) {
            WorldStateCache.Snapshot snap = worldStateCache.get();
            if (!snap.isEmpty()) {
                return snap.entitiesWithinRadius(entityType, radius);
            }
        }
        return botOperations.getNearbyEntities(entityType, radius);
    }

    /**
     * Suppress threats that are outside the low-risk suppression radius when a low-risk
     * task is currently RUNNING. Opportunities are never suppressed by this method.
     *
     * <p>Only threats beyond {@code config.getLowRiskThreatRadius()} are suppressed.
     * Threats within the radius are always published so the bot can react to immediate danger.</p>
     *
     * <p>TODO Sprint 14: when {@code riskLevel == HIGH} (combat-tagged plans), skip suppression
     * entirely and publish all threats regardless of distance.</p>
     *
     * @param threats debounce-filtered threat list
     * @return threats that should be published after task-aware suppression
     */
    private List<ThreatInfo> suppressLowRiskThreats(List<ThreatInfo> threats) {
        if (taskExecutor == null || threats.isEmpty()) {
            return threats;
        }

        Optional<TaskSnapshot> currentTask = taskExecutor.currentTask();
        boolean lowRiskActive = currentTask.isPresent()
                && currentTask.get().state() == TaskState.RUNNING;

        if (!lowRiskActive) {
            return threats;
        }

        int suppressionRadius = config.getLowRiskThreatRadius();
        var result = new ArrayList<ThreatInfo>();
        for (ThreatInfo threat : threats) {
            if (threat.distance() <= suppressionRadius) {
                result.add(threat);
            } else {
                counters.inc("observation.suppressed");
                if (runLog != null) {
                    runLog.log("observation_suppressed", Map.of(
                            "reason", "task_low_risk",
                            "distance", (int) threat.distance(),
                            "type", threat.type()));
                }
                log.debug("Suppressed threat {} at distance {} (low-risk task active, radius {})",
                        threat.type(), (int) threat.distance(), suppressionRadius);
            }
        }
        return result;
    }

    /**
     * Emit a heartbeat outbound message when all conditions are met:
     * <ol>
     *   <li>A Plan is in RUNNING state.</li>
     *   <li>The plan has been running longer than {@code heartbeatGraceSeconds}.</li>
     *   <li>The per-heartbeat cooldown has elapsed.</li>
     *   <li>The player has not chatted within {@code suppressIfRecentChatSeconds}.</li>
     * </ol>
     *
     * <p>Requires {@code botProps} to be non-null (set in full-constructor wiring). If
     * {@code botProps} or {@code taskExecutor} is absent (lightweight test wiring), the
     * method is a no-op.</p>
     */
    private void maybeEmitHeartbeat() {
        if (botProps == null || taskExecutor == null) {
            return;
        }

        Optional<TaskSnapshot> currentTask = taskExecutor.currentTask();
        if (currentTask.isEmpty() || currentTask.get().state() != TaskState.RUNNING) {
            return;
        }

        TaskSnapshot snap = currentTask.get();
        Duration elapsed = Duration.between(snap.startedAt(), Instant.now());
        BotProperties.JobProperties jobProps = botProps.getJob();
        if (elapsed.toSeconds() < jobProps.getHeartbeatGraceSeconds()) {
            return;
        }

        Duration cooldown = Duration.ofSeconds(jobProps.getHeartbeatCooldownSeconds());
        Duration suppressWindow = Duration.ofSeconds(jobProps.getSuppressIfRecentChatSeconds());
        if (!jobTracker.shouldSendHeartbeat(cooldown, suppressWindow)) {
            return;
        }

        String heartbeat = formatHeartbeat(snap);
        eventQueue.enqueueOutbound(heartbeat);
        jobTracker.markHeartbeatSent();
        counters.inc("heartbeat.sent");
        if (runLog != null) {
            runLog.log("heartbeat", Map.of(
                    "task_id", snap.id().toString(),
                    "summary", snap.plan().summary()));
        }
        log.debug("Heartbeat emitted for task {}: {}", snap.id(), heartbeat);
    }

    /**
     * Format a terse heartbeat string for the given task snapshot.
     *
     * <p>Pulls inventory summary and health from the live bridge when available.
     * Format: {@code <step> | <progress> | Y=<y> | <inv> | HP <hp>/<maxHp>}</p>
     *
     * <p>The output is truncated to 250 chars (Minecraft chat limit).</p>
     */
    private String formatHeartbeat(TaskSnapshot snap) {
        String stepDesc = snap.plan().steps().isEmpty()
                ? snap.plan().summary()
                : snap.plan().steps().get(snap.currentStepIndex()).description();
        String progress = snap.stepProgress().isBlank() ? "in progress" : snap.stepProgress();

        Location pos = null;
        HealthStatus health = null;
        String invSummary = null;
        try {
            pos = botOperations.getCurrentPosition();
            health = botOperations.getHealthStatus();
            if (inventoryOps != null) {
                invSummary = inventoryOps.getInventorySummary();
            }
        } catch (Exception e) {
            log.debug("Could not read bot state for heartbeat: {}", e.getMessage());
        }

        String posStr = (pos != null) ? "Y=" + pos.y() : "Y=?";
        String invStr = (invSummary != null) ? shorten(invSummary, 40) : "Inv ?";
        String hpStr = (health != null)
                ? "HP " + (int) health.health() + "/" + (int) health.maxHealth()
                : "HP ?";

        String msg = String.format("%s | %s | %s | %s | %s",
                shorten(stepDesc, 60),
                shorten(progress, 50),
                posStr,
                invStr,
                hpStr);
        return msg.length() > 250 ? msg.substring(0, 250) : msg;
    }

    /**
     * Truncate a string to at most {@code maxLen} characters, appending {@code "…"} when
     * truncation occurs. Returns the original string when it fits within the limit.
     */
    private String shorten(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen - 1) + "…";
    }

    private void cleanExpiredDebounces() {
        long now = System.currentTimeMillis();
        long window = config.getDebounceSeconds() * 1000L;
        debounceMap.entrySet().removeIf(entry -> now - entry.getValue() > window);
    }

    private List<ThreatInfo> filterDebouncedThreats(List<ThreatInfo> threats) {
        var result = new ArrayList<ThreatInfo>();
        for (var threat : threats) {
            if (!isDebounced(threat.type(), threat.location())) {
                result.add(threat);
            }
        }
        return result;
    }

    private List<EntityInfo> filterDebouncedEntities(List<EntityInfo> entities) {
        var result = new ArrayList<EntityInfo>();
        for (var entity : entities) {
            if (!isDebounced(entity.getType(), entity.getLocation())) {
                result.add(entity);
            }
        }
        return result;
    }

    private boolean isDebounced(String type, Location location) {
        var key = buildDebounceKey(type, location);
        var lastSeen = debounceMap.get(key);
        if (lastSeen == null) {
            return false;
        }
        long window = config.getDebounceSeconds() * 1000L;
        return System.currentTimeMillis() - lastSeen <= window;
    }

    private String buildDebounceKey(String type, Location location) {
        int bucketX = (int) (Math.round(location.x() / 4.0) * 4);
        int bucketZ = (int) (Math.round(location.z() / 4.0) * 4);
        return type + ":" + bucketX + ":" + bucketZ;
    }

    private void debounce(String type, Location location) {
        debounceMap.put(buildDebounceKey(type, location), System.currentTimeMillis());
    }

    private void debounceAllThreats(List<ThreatInfo> threats) {
        for (var threat : threats) {
            debounce(threat.type(), threat.location());
        }
    }

    private void debounceAllEntities(List<EntityInfo> entities) {
        for (var entity : entities) {
            debounce(entity.getType(), entity.getLocation());
        }
    }

    private void publishClosest(List<ThreatInfo> threats, List<EntityInfo> opportunities) {
        if (!threats.isEmpty()) {
            var closest = threats.get(0);
            publish(formatThreat(closest));
            debounce(closest.type(), closest.location());
        } else if (!opportunities.isEmpty()) {
            var closest = opportunities.get(0);
            publish(formatOpportunity(closest));
            debounce(closest.getType(), closest.getLocation());
        }
    }

    private void publishSummary(List<ThreatInfo> threats, List<EntityInfo> opportunities) {
        var message = formatSummary(threats, opportunities);
        publish(message);
    }

    private void publish(String message) {
        if ("active".equalsIgnoreCase(config.getMode())) {
            eventQueue.triggerUrgentFramework(message);
        } else {
            eventQueue.publishFramework(message);
        }
    }

    private String formatThreat(ThreatInfo threat) {
        return String.format("Threat detected: %s at %s, %.0f blocks %s",
                threat.type(), threat.location(), threat.distance(), threat.direction());
    }

    private String formatOpportunity(EntityInfo entity) {
        return String.format("Opportunity: %s at %s, %.0f blocks %s",
                entity.getType(), entity.getLocation(), entity.getDistance(), entity.getDirection());
    }

    private String formatSummary(List<ThreatInfo> threats, List<EntityInfo> opportunities) {
        var threatPart = threats.isEmpty() ? "none" : formatThreatList(threats);
        var oppPart = opportunities.isEmpty() ? "none" : formatOpportunityList(opportunities);
        return String.format("Status scan: threats: %s, opportunities: %s", threatPart, oppPart);
    }

    private String formatThreatList(List<ThreatInfo> threats) {
        var sb = new StringBuilder();
        for (int i = 0; i < threats.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            var t = threats.get(i);
            sb.append(String.format("%s at %s (%.0f blocks %s)",
                    t.type(), t.location(), t.distance(), t.direction()));
        }
        return sb.toString();
    }

    private String formatOpportunityList(List<EntityInfo> opportunities) {
        var sb = new StringBuilder();
        for (int i = 0; i < opportunities.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            var o = opportunities.get(i);
            sb.append(String.format("%s at %s (%.0f blocks %s)",
                    o.getType(), o.getLocation(), o.getDistance(), o.getDirection()));
        }
        return sb.toString();
    }
}
