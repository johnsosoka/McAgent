package com.mcagent.core.routine;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Seeds the database with the hand-authored starter routines on application startup.
 *
 * <p>Each seed method is idempotent — it checks whether the routine already exists
 * before inserting, so re-starts and re-deployments do not create duplicates.</p>
 *
 * <p>All templates use the Plan JSON format from Sprint 13: step {@code type} is one
 * of {@code Navigate}, {@code Mine}, or {@code WaitFor}. Numeric coordinate parameters
 * use {@code ${param}} placeholders that are resolved by
 * {@link DefaultRoutineService#substituteTemplate} at invocation time.</p>
 *
 * <p>This seeder writes directly to {@link RoutineRepository} (rather than via
 * {@link RoutineService#define}) so that it can set {@code systemSeeded=true},
 * distinguishing hand-authored routines from LLM-authored ones.</p>
 *
 * <h3>Seeded routines</h3>
 * <ol>
 *   <li><b>returnHome</b> — Navigate to base coordinates.</li>
 *   <li><b>mineUntilFull</b> — Mine a block type until inventory is saturated or time out.</li>
 *   <li><b>depositAndContinue</b> — Navigate to a chest location (deposit action TBD).</li>
 *   <li><b>nightShelter</b> — Navigate to base for night shelter (sleep action TBD).</li>
 *   <li><b>eatIfHungry</b> — WaitFor condition checking inventory has food (placeholder).</li>
 * </ol>
 */
@Component
public class RoutineSeeder {

    private static final Logger log = LoggerFactory.getLogger(RoutineSeeder.class);

    private final RoutineRepository repository;

    public RoutineSeeder(RoutineRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void seed() {
        seedReturnHome();
        seedMineUntilFull();
        seedDepositAndContinue();
        seedNightShelter();
        seedEatIfHungry();
        log.info("RoutineSeeder complete");
    }

    // -------------------------------------------------------------------------
    // Individual seed methods
    // -------------------------------------------------------------------------

    private void seedReturnHome() {
        final String name = "returnHome";
        if (repository.existsByName(name)) {
            return;
        }
        String template = "[{\"type\":\"Navigate\",\"x\":\"${baseX}\",\"y\":\"${baseY}\",\"z\":\"${baseZ}\"," +
                "\"timeout\":\"PT10M\",\"retry\":{\"maxRetries\":1,\"backoff\":\"PT5S\"}}]";
        repository.save(new RoutineDefinition(
                name,
                "Navigate back to the configured base coordinates.",
                template,
                "[\"baseX\",\"baseY\",\"baseZ\"]",
                true));
        log.debug("Seeded routine '{}'", name);
    }

    private void seedMineUntilFull() {
        final String name = "mineUntilFull";
        if (repository.existsByName(name)) {
            return;
        }
        String template = "[{\"type\":\"Mine\",\"blockType\":\"${blockType}\",\"quantity\":2048," +
                "\"searchRadius\":32,\"timeout\":\"PT60M\",\"retry\":{\"maxRetries\":1,\"backoff\":\"PT10S\"}}]";
        repository.save(new RoutineDefinition(
                name,
                "Mine the given block type until inventory is full or the timeout elapses.",
                template,
                "[\"blockType\"]",
                true));
        log.debug("Seeded routine '{}'", name);
    }

    private void seedDepositAndContinue() {
        final String name = "depositAndContinue";
        if (repository.existsByName(name)) {
            return;
        }
        // Navigate to chest coordinates — actual deposit action is deferred to a later sprint.
        String template = "[{\"type\":\"Navigate\",\"x\":\"${chestX}\",\"y\":\"${chestY}\",\"z\":\"${chestZ}\"," +
                "\"timeout\":\"PT10M\",\"retry\":{\"maxRetries\":1,\"backoff\":\"PT5S\"}}]";
        repository.save(new RoutineDefinition(
                name,
                "Navigate to a chest location to deposit inventory (deposit action TBD).",
                template,
                "[\"chestX\",\"chestY\",\"chestZ\"]",
                true));
        log.debug("Seeded routine '{}'", name);
    }

    private void seedNightShelter() {
        final String name = "nightShelter";
        if (repository.existsByName(name)) {
            return;
        }
        // Navigate to base for shelter — sleep action is deferred to a later sprint.
        String template = "[{\"type\":\"Navigate\",\"x\":\"${baseX}\",\"y\":\"${baseY}\",\"z\":\"${baseZ}\"," +
                "\"timeout\":\"PT10M\",\"retry\":{\"maxRetries\":1,\"backoff\":\"PT5S\"}}]";
        repository.save(new RoutineDefinition(
                name,
                "Navigate to base for night shelter (sleep action TBD).",
                template,
                "[\"baseX\",\"baseY\",\"baseZ\"]",
                true));
        log.debug("Seeded routine '{}'", name);
    }

    private void seedEatIfHungry() {
        final String name = "eatIfHungry";
        if (repository.existsByName(name)) {
            return;
        }
        // WaitFor an inventory-has-food condition; actual eating is handled by reflexes.
        String template = "[{\"type\":\"WaitFor\"," +
                "\"condition\":{\"type\":\"InventoryHas\",\"itemId\":\"minecraft:bread\",\"count\":1}," +
                "\"timeout\":\"PT1M\",\"retry\":{\"maxRetries\":0,\"backoff\":\"PT0S\"}}]";
        repository.save(new RoutineDefinition(
                name,
                "Wait until inventory contains food, then eat if hungry (placeholder — eating is handled by reflexes).",
                template,
                "[]",
                true));
        log.debug("Seeded routine '{}'", name);
    }
}
