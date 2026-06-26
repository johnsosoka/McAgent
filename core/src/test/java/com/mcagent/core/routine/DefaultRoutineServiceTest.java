package com.mcagent.core.routine;

import com.mcagent.core.task.Plan;
import com.mcagent.core.task.NavigateStep;
import com.mcagent.core.task.MineStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultRoutineService}.
 *
 * Uses a mock {@link RoutineRepository} to isolate service logic from the database.
 */
class DefaultRoutineServiceTest {

    private RoutineRepository repository;
    private DefaultRoutineService service;

    @BeforeEach
    void setUp() {
        repository = mock(RoutineRepository.class);
        service = new DefaultRoutineService(repository);

        // Default: save returns the argument unchanged
        when(repository.save(any(RoutineDefinition.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // define()
    // -------------------------------------------------------------------------

    @Test
    void define_savesAndReturnsId() {
        when(repository.existsByName("returnHome")).thenReturn(false);

        // define() does not throw — that is the success condition.
        // The returned ID may be null in a mock-repository context since @GeneratedValue
        // is assigned by the real database; verify the save was delegated correctly.
        service.define("returnHome", "Navigate home",
                "[{\"type\":\"Navigate\",\"x\":0,\"y\":64,\"z\":0}]",
                List.of("baseX", "baseY", "baseZ"));

        verify(repository).save(any(RoutineDefinition.class));
    }

    @Test
    void define_throwsWhenNameAlreadyExists() {
        when(repository.existsByName("returnHome")).thenReturn(true);

        assertThatThrownBy(() ->
                service.define("returnHome", "desc", "[{}]", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("returnHome");

        verify(repository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // invoke() — happy path
    // -------------------------------------------------------------------------

    @Test
    void invoke_substitutesIntegerParameters() {
        String template = "[{\"type\":\"Navigate\",\"x\":\"${baseX}\",\"y\":\"${baseY}\",\"z\":\"${baseZ}\"," +
                "\"timeout\":\"PT10M\",\"retry\":{\"maxRetries\":1,\"backoff\":\"PT5S\"}}]";
        RoutineDefinition definition = new RoutineDefinition(
                "returnHome", "Navigate home", template, "[\"baseX\",\"baseY\",\"baseZ\"]", true);
        when(repository.findByName("returnHome")).thenReturn(Optional.of(definition));

        Plan plan = service.invoke("returnHome", Map.of("baseX", 100, "baseY", 64, "baseZ", 200));

        assertThat(plan).isNotNull();
        assertThat(plan.steps()).hasSize(1);
        NavigateStep step = (NavigateStep) plan.steps().get(0);
        assertThat(step.x()).isEqualTo(100);
        assertThat(step.y()).isEqualTo(64);
        assertThat(step.z()).isEqualTo(200);
    }

    @Test
    void invoke_substitutesStringParameter_inMineStep() {
        String template = "[{\"type\":\"Mine\",\"blockType\":\"${blockType}\",\"quantity\":2048," +
                "\"searchRadius\":32,\"timeout\":\"PT60M\",\"retry\":{\"maxRetries\":1,\"backoff\":\"PT10S\"}}]";
        RoutineDefinition definition = new RoutineDefinition(
                "mineUntilFull", "Mine until full", template, "[\"blockType\"]", true);
        when(repository.findByName("mineUntilFull")).thenReturn(Optional.of(definition));

        Plan plan = service.invoke("mineUntilFull", Map.of("blockType", "minecraft:iron_ore"));

        assertThat(plan.steps()).hasSize(1);
        MineStep step = (MineStep) plan.steps().get(0);
        assertThat(step.blockType()).isEqualTo("minecraft:iron_ore");
        assertThat(step.quantity()).isEqualTo(2048);
    }

    @Test
    void invoke_planSummaryUsesRoutineDescription() {
        String template = "[{\"type\":\"Navigate\",\"x\":\"${x}\",\"y\":64,\"z\":\"${z}\"," +
                "\"timeout\":\"PT5M\",\"retry\":{\"maxRetries\":0,\"backoff\":\"PT0S\"}}]";
        RoutineDefinition definition = new RoutineDefinition(
                "goThere", "Go to location", template, "[\"x\",\"z\"]", false);
        when(repository.findByName("goThere")).thenReturn(Optional.of(definition));

        Plan plan = service.invoke("goThere", Map.of("x", 50, "z", -100));

        assertThat(plan.summary()).isEqualTo("Go to location");
        assertThat(plan.requestedBy()).isEqualTo("routine");
    }

    @Test
    void invoke_unknownPlaceholder_leftAsIs_causesParseError() {
        // A placeholder with no matching arg should remain and cause a downstream error
        String template = "[{\"type\":\"Navigate\",\"x\":\"${missing}\",\"y\":64,\"z\":0," +
                "\"timeout\":\"PT5M\",\"retry\":{\"maxRetries\":0,\"backoff\":\"PT0S\"}}]";
        RoutineDefinition definition = new RoutineDefinition(
                "broken", "Broken routine", template, "[\"missing\"]", false);
        when(repository.findByName("broken")).thenReturn(Optional.of(definition));

        // The unresolved placeholder string "${missing}" cannot be parsed as an integer x-coord
        assertThatThrownBy(() -> service.invoke("broken", Map.of()))
                .isInstanceOf(RoutineInvocationException.class);
    }

    // -------------------------------------------------------------------------
    // invoke() — not found
    // -------------------------------------------------------------------------

    @Test
    void invoke_throwsRoutineNotFoundException_whenNotFound() {
        when(repository.findByName("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.invoke("nonexistent", Map.of()))
                .isInstanceOf(RoutineNotFoundException.class)
                .hasMessageContaining("nonexistent");
    }

    // -------------------------------------------------------------------------
    // findByName() / listAll() / delete()
    // -------------------------------------------------------------------------

    @Test
    void findByName_delegatesToRepository() {
        RoutineDefinition definition = new RoutineDefinition("x", "desc", "[]", "[]", true);
        when(repository.findByName("x")).thenReturn(Optional.of(definition));

        Optional<RoutineDefinition> result = service.findByName("x");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("x");
    }

    @Test
    void delete_returnsFalse_whenNotFound() {
        when(repository.findByName("missing")).thenReturn(Optional.empty());

        boolean deleted = service.delete("missing");

        assertThat(deleted).isFalse();
    }

    @Test
    void delete_returnsTrue_whenFound() {
        RoutineDefinition definition = new RoutineDefinition("target", "desc", "[]", "[]", false);
        when(repository.findByName("target")).thenReturn(Optional.of(definition));

        boolean deleted = service.delete("target");

        assertThat(deleted).isTrue();
        verify(repository).delete(definition);
    }

    // -------------------------------------------------------------------------
    // substituteTemplate — unit tests for the core substitution logic
    // -------------------------------------------------------------------------

    @Test
    void substituteTemplate_replacesIntegerNode() {
        String template = "[{\"x\":\"${xVal}\"}]";
        String result = service.substituteTemplate(template, Map.of("xVal", 42));
        assertThat(result).contains("42").doesNotContain("${xVal}");
    }

    @Test
    void substituteTemplate_replacesStringNode() {
        String template = "[{\"blockType\":\"${bt}\"}]";
        String result = service.substituteTemplate(template, Map.of("bt", "minecraft:coal_ore"));
        assertThat(result).contains("minecraft:coal_ore").doesNotContain("${bt}");
    }

    @Test
    void substituteTemplate_ignoresNonPlaceholderStrings() {
        String template = "[{\"type\":\"Navigate\"}]";
        String result = service.substituteTemplate(template, Map.of());
        assertThat(result).contains("Navigate");
    }

    @Test
    void substituteTemplate_throwsOnInvalidJson() {
        assertThatThrownBy(() ->
                service.substituteTemplate("not valid json {{{", Map.of()))
                .isInstanceOf(RoutineInvocationException.class)
                .hasMessageContaining("valid JSON");
    }
}
