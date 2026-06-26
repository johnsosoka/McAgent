package com.mcagent.core.routine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that {@link RoutineSeeder} populates the database with
 * all five starter routines on startup.
 *
 * <p>Uses {@code @DataJpaTest} with an in-memory H2 database so no external services
 * are required. The seeder's {@code @PostConstruct} method runs automatically when
 * the Spring context starts.</p>
 */
@DataJpaTest
@Import(RoutineSeeder.class)
class RoutineSeederTest {

    @Autowired
    private RoutineRepository repository;

    @Autowired
    private RoutineSeeder seeder;

    @Test
    void allFiveStarterRoutinesArePresent() {
        List<String> names = repository.findAll().stream()
                .map(RoutineDefinition::getName)
                .sorted()
                .toList();

        assertThat(names).containsExactlyInAnyOrder(
                "depositAndContinue",
                "eatIfHungry",
                "mineUntilFull",
                "nightShelter",
                "returnHome");
    }

    @Test
    void returnHome_isSystemSeeded() {
        RoutineDefinition def = repository.findByName("returnHome").orElseThrow();
        assertThat(def.isSystemSeeded()).isTrue();
        assertThat(def.getDescription()).isNotBlank();
        assertThat(def.getPlanTemplate()).contains("Navigate");
        assertThat(def.getPlanTemplate()).contains("${baseX}");
    }

    @Test
    void mineUntilFull_hasBlockTypeParameter() {
        RoutineDefinition def = repository.findByName("mineUntilFull").orElseThrow();
        assertThat(def.getPlanTemplate()).contains("${blockType}");
        assertThat(def.getParametersJson()).contains("blockType");
    }

    @Test
    void seedIsIdempotent_doesNotCreateDuplicates() {
        // Re-run seeding — should not throw or create duplicates.
        seeder.seed();

        long count = repository.findAll().stream()
                .filter(r -> r.getName().equals("returnHome"))
                .count();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void nightShelter_hasBaseCoordinateParameters() {
        RoutineDefinition def = repository.findByName("nightShelter").orElseThrow();
        assertThat(def.getParametersJson()).contains("baseX");
        assertThat(def.getParametersJson()).contains("baseY");
        assertThat(def.getParametersJson()).contains("baseZ");
    }

    @Test
    void eatIfHungry_hasNoParameters() {
        RoutineDefinition def = repository.findByName("eatIfHungry").orElseThrow();
        assertThat(def.getParametersJson()).isEqualTo("[]");
    }
}
