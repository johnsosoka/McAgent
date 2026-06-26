package com.mcagent.core.service;

import com.mcagent.core.task.InventoryHasCondition;
import com.mcagent.core.task.MineStep;
import com.mcagent.core.task.NavigateStep;
import com.mcagent.core.task.Plan;
import com.mcagent.core.task.WaitForStep;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PlanFromDraftConverter}.
 *
 * <p>Covers all three step types, defaults, optional field behaviour,
 * and all structured rejection paths ({@link PlanValidationException}).</p>
 */
class PlanFromDraftConverterTest {

    // -------------------------------------------------------------------------
    // Navigate step
    // -------------------------------------------------------------------------

    @Test
    void convert_navigateStep_buildsCorrectStep() {
        PlanDraft draft = new PlanDraft(
                "Go to base",
                List.of(Map.of("type", "Navigate", "x", 100, "y", 64, "z", 200, "timeoutSeconds", 90))
        );

        Plan plan = PlanFromDraftConverter.convert(draft, "testPlayer");

        assertThat(plan.steps()).hasSize(1);
        NavigateStep step = (NavigateStep) plan.steps().get(0);
        assertThat(step.x()).isEqualTo(100);
        assertThat(step.y()).isEqualTo(64);
        assertThat(step.z()).isEqualTo(200);
        assertThat(step.timeout()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void convert_navigateStep_usesDefaultTimeoutWhenAbsent() {
        PlanDraft draft = new PlanDraft(
                "Go somewhere",
                List.of(Map.of("type", "Navigate", "x", 0, "y", 64, "z", 0))
        );

        Plan plan = PlanFromDraftConverter.convert(draft, "player");

        NavigateStep step = (NavigateStep) plan.steps().get(0);
        assertThat(step.timeout()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void convert_navigateStep_missingX_throwsValidationException() {
        PlanDraft draft = new PlanDraft(
                "Broken nav",
                List.of(Map.of("type", "Navigate", "y", 64, "z", 200))
        );

        assertThatThrownBy(() -> PlanFromDraftConverter.convert(draft, "player"))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("x");
    }

    // -------------------------------------------------------------------------
    // Mine step
    // -------------------------------------------------------------------------

    @Test
    void convert_mineStep_buildsCorrectStep() {
        PlanDraft draft = new PlanDraft(
                "Mine iron",
                List.of(Map.of(
                        "type", "Mine",
                        "blockType", "minecraft:iron_ore",
                        "quantity", 64,
                        "searchRadius", 32,
                        "timeoutSeconds", 1800
                ))
        );

        Plan plan = PlanFromDraftConverter.convert(draft, "player");

        MineStep step = (MineStep) plan.steps().get(0);
        assertThat(step.blockType()).isEqualTo("minecraft:iron_ore");
        assertThat(step.quantity()).isEqualTo(64);
        assertThat(step.searchRadius()).isEqualTo(32);
        assertThat(step.timeout()).isEqualTo(Duration.ofSeconds(1800));
    }

    @Test
    void convert_mineStep_defaultSearchRadius_whenAbsent() {
        PlanDraft draft = new PlanDraft(
                "Mine coal",
                List.of(Map.of("type", "Mine", "blockType", "minecraft:coal_ore", "quantity", 16))
        );

        Plan plan = PlanFromDraftConverter.convert(draft, "player");

        MineStep step = (MineStep) plan.steps().get(0);
        assertThat(step.searchRadius()).isEqualTo(64);
    }

    @Test
    void convert_mineStep_usesDefaultTimeoutWhenAbsent() {
        PlanDraft draft = new PlanDraft(
                "Mine coal",
                List.of(Map.of("type", "Mine", "blockType", "minecraft:coal_ore", "quantity", 16))
        );

        Plan plan = PlanFromDraftConverter.convert(draft, "player");

        MineStep step = (MineStep) plan.steps().get(0);
        assertThat(step.timeout()).isEqualTo(Duration.ofSeconds(1800));
    }

    @Test
    void convert_mineStep_missingBlockType_throwsValidationException() {
        PlanDraft draft = new PlanDraft(
                "Mine something",
                List.of(Map.of("type", "Mine", "quantity", 16))
        );

        assertThatThrownBy(() -> PlanFromDraftConverter.convert(draft, "player"))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("blockType");
    }

    // -------------------------------------------------------------------------
    // WaitFor step
    // -------------------------------------------------------------------------

    @Test
    void convert_waitForStep_inventoryHasCondition_buildsCorrectStep() {
        PlanDraft draft = new PlanDraft(
                "Wait for iron",
                List.of(Map.of(
                        "type", "WaitFor",
                        "condition", Map.of("type", "InventoryHas", "itemId", "minecraft:iron_ore", "count", 64),
                        "timeoutSeconds", 300
                ))
        );

        Plan plan = PlanFromDraftConverter.convert(draft, "player");

        WaitForStep step = (WaitForStep) plan.steps().get(0);
        InventoryHasCondition condition = (InventoryHasCondition) step.condition();
        assertThat(condition.itemId()).isEqualTo("minecraft:iron_ore");
        assertThat(condition.count()).isEqualTo(64);
        assertThat(step.timeout()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void convert_waitForStep_usesDefaultTimeoutWhenAbsent() {
        PlanDraft draft = new PlanDraft(
                "Wait for items",
                List.of(Map.of(
                        "type", "WaitFor",
                        "condition", Map.of("type", "InventoryHas", "itemId", "minecraft:diamond", "count", 5)
                ))
        );

        Plan plan = PlanFromDraftConverter.convert(draft, "player");

        WaitForStep step = (WaitForStep) plan.steps().get(0);
        assertThat(step.timeout()).isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void convert_waitForStep_missingCondition_throwsValidationException() {
        PlanDraft draft = new PlanDraft(
                "Wait",
                List.of(Map.of("type", "WaitFor", "timeoutSeconds", 60))
        );

        assertThatThrownBy(() -> PlanFromDraftConverter.convert(draft, "player"))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("condition");
    }

    @Test
    void convert_waitForStep_unknownConditionType_throwsValidationException() {
        PlanDraft draft = new PlanDraft(
                "Wait",
                List.of(Map.of(
                        "type", "WaitFor",
                        "condition", Map.of("type", "UnknownCondition", "foo", "bar")
                ))
        );

        assertThatThrownBy(() -> PlanFromDraftConverter.convert(draft, "player"))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("UnknownCondition");
    }

    // -------------------------------------------------------------------------
    // Unknown step type
    // -------------------------------------------------------------------------

    @Test
    void convert_unknownStepType_throwsValidationException() {
        PlanDraft draft = new PlanDraft(
                "Craft something",
                List.of(Map.of("type", "Craft", "item", "minecraft:iron_pickaxe", "quantity", 1))
        );

        assertThatThrownBy(() -> PlanFromDraftConverter.convert(draft, "player"))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("Craft");
    }

    // -------------------------------------------------------------------------
    // Top-level draft validation
    // -------------------------------------------------------------------------

    @Test
    void convert_nullDraft_throwsValidationException() {
        assertThatThrownBy(() -> PlanFromDraftConverter.convert(null, "player"))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("null");
    }

    @Test
    void convert_blankSummary_throwsValidationException() {
        PlanDraft draft = new PlanDraft(
                "  ",
                List.of(Map.of("type", "Navigate", "x", 0, "y", 64, "z", 0))
        );

        assertThatThrownBy(() -> PlanFromDraftConverter.convert(draft, "player"))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("summary");
    }

    @Test
    void convert_emptySteps_throwsValidationException() {
        PlanDraft draft = new PlanDraft("Mine iron", List.of());

        assertThatThrownBy(() -> PlanFromDraftConverter.convert(draft, "player"))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("steps");
    }

    // -------------------------------------------------------------------------
    // Plan metadata
    // -------------------------------------------------------------------------

    @Test
    void convert_setsRequestedByFromCaller() {
        PlanDraft draft = new PlanDraft(
                "Go home",
                List.of(Map.of("type", "Navigate", "x", 0, "y", 64, "z", 0))
        );

        Plan plan = PlanFromDraftConverter.convert(draft, "Steve");

        assertThat(plan.requestedBy()).isEqualTo("Steve");
    }

    @Test
    void convert_assignsUniqueId() {
        PlanDraft draft = new PlanDraft(
                "Go home",
                List.of(Map.of("type", "Navigate", "x", 0, "y", 64, "z", 0))
        );

        Plan plan1 = PlanFromDraftConverter.convert(draft, "player");
        Plan plan2 = PlanFromDraftConverter.convert(draft, "player");

        assertThat(plan1.id()).isNotEqualTo(plan2.id());
    }

    @Test
    void convert_multipleSteps_preservesOrder() {
        PlanDraft draft = new PlanDraft(
                "Navigate then mine",
                List.of(
                        Map.of("type", "Navigate", "x", 50, "y", 64, "z", 50),
                        Map.of("type", "Mine", "blockType", "minecraft:coal_ore", "quantity", 8)
                )
        );

        Plan plan = PlanFromDraftConverter.convert(draft, "player");

        assertThat(plan.steps()).hasSize(2);
        assertThat(plan.steps().get(0)).isInstanceOf(NavigateStep.class);
        assertThat(plan.steps().get(1)).isInstanceOf(MineStep.class);
    }
}
