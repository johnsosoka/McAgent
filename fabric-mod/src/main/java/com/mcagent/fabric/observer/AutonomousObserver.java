package com.mcagent.fabric.observer;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.BotOperations.ThreatInfo;
import com.mcagent.fabric.queue.BotEventQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AutonomousObserver {

    private static final Logger log = LoggerFactory.getLogger(AutonomousObserver.class);

    private final BotOperations botOperations;
    private final BotEventQueue eventQueue;
    private final BotProperties.ObservationProperties config;
    private final Map<String, Long> debounceMap;

    private int tickCounter;
    private volatile boolean enabled;

    public AutonomousObserver(BotOperations botOperations, BotEventQueue eventQueue,
                              BotProperties.ObservationProperties config) {
        if (botOperations == null) {
            throw new IllegalArgumentException("botOperations must not be null");
        }
        if (eventQueue == null) {
            throw new IllegalArgumentException("eventQueue must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.botOperations = botOperations;
        this.eventQueue = eventQueue;
        this.config = config;
        this.debounceMap = new ConcurrentHashMap<>();
        this.enabled = config.isEnabled();
    }

    public void onTick() {
        if (!enabled) {
            return;
        }
        if (++tickCounter < config.getScanIntervalTicks()) {
            return;
        }
        tickCounter = 0;
        scan();
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
        cleanExpiredDebounces();

        var threats = botOperations.getNearbyThreats(config.getThreatRadius());
        var opportunities = collectOpportunities();

        var newThreats = filterDebouncedThreats(threats);
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

    private List<EntityInfo> collectOpportunities() {
        var opportunities = new ArrayList<EntityInfo>();
        if (!config.isTrackPassiveMobs()) {
            return opportunities;
        }
        for (var type : config.getPassiveMobTypes()) {
            opportunities.addAll(botOperations.getNearbyEntities(type, config.getPassiveRadius()));
        }
        return opportunities;
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

    private boolean isDebounced(String type, BotOperations.Location location) {
        var key = buildDebounceKey(type, location);
        var lastSeen = debounceMap.get(key);
        if (lastSeen == null) {
            return false;
        }
        long window = config.getDebounceSeconds() * 1000L;
        return System.currentTimeMillis() - lastSeen <= window;
    }

    private String buildDebounceKey(String type, BotOperations.Location location) {
        int bucketX = (int) (Math.round(location.x() / 4.0) * 4);
        int bucketZ = (int) (Math.round(location.z() / 4.0) * 4);
        return type + ":" + bucketX + ":" + bucketZ;
    }

    private void debounce(String type, BotOperations.Location location) {
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
