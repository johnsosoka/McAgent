package com.mcagent.core.task;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests Plan construction, step immutability, and full JSON round-trip
 * for all three Sprint 13 step types.
 */
class PlanTest {

    @Test
    void plan_defensivelyCopiesSteps() {
        List<Step> mutableSteps = new java.util.ArrayList<>();
        mutableSteps.add(new NavigateStep(10, 64, 20, Duration.ofMinutes(5), RetryPolicy.NONE));

        Plan plan = buildPlan(mutableSteps);

        mutableSteps.add(new NavigateStep(0, 0, 0, Duration.ofSeconds(30), RetryPolicy.NONE));

        assertThat(plan.steps()).hasSize(1);
    }

    @Test
    void plan_stepsAreUnmodifiable() {
        Plan plan = buildPlan(List.of(
                new NavigateStep(10, 64, 20, Duration.ofMinutes(5), RetryPolicy.NONE)));

        assertThatThrownBy(() -> plan.steps().add(new NavigateStep(0, 0, 0, Duration.ofSeconds(1), RetryPolicy.NONE)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -------------------------------------------------------------------------
    // JSON round-trip — NavigateStep
    // -------------------------------------------------------------------------

    @Test
    void navigateStep_roundTrip() {
        Plan plan = buildPlan(List.of(
                new NavigateStep(100, 64, -200, Duration.ofMinutes(5), RetryPolicy.DEFAULT)));

        String json = PlanSerializer.toJson(plan);
        Plan restored = PlanSerializer.fromJson(json);

        assertThat(restored.id()).isEqualTo(plan.id());
        assertThat(restored.summary()).isEqualTo(plan.summary());
        assertThat(restored.steps()).hasSize(1);

        NavigateStep step = (NavigateStep) restored.steps().get(0);
        assertThat(step.x()).isEqualTo(100);
        assertThat(step.y()).isEqualTo(64);
        assertThat(step.z()).isEqualTo(-200);
        assertThat(step.timeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(step.retry().maxRetries()).isEqualTo(1);
        assertThat(step.retry().backoff()).isEqualTo(Duration.ofSeconds(2));
    }

    // -------------------------------------------------------------------------
    // JSON round-trip — MineStep
    // -------------------------------------------------------------------------

    @Test
    void mineStep_roundTrip() {
        Plan plan = buildPlan(List.of(
                new MineStep("minecraft:iron_ore", 64, 32, Duration.ofMinutes(20), RetryPolicy.NONE)));

        String json = PlanSerializer.toJson(plan);
        Plan restored = PlanSerializer.fromJson(json);

        MineStep step = (MineStep) restored.steps().get(0);
        assertThat(step.blockType()).isEqualTo("minecraft:iron_ore");
        assertThat(step.quantity()).isEqualTo(64);
        assertThat(step.searchRadius()).isEqualTo(32);
        assertThat(step.timeout()).isEqualTo(Duration.ofMinutes(20));
        assertThat(step.retry().maxRetries()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // JSON round-trip — WaitForStep with InventoryHasCondition
    // -------------------------------------------------------------------------

    @Test
    void waitForStep_inventoryHasCondition_roundTrip() {
        WaitCondition condition = new InventoryHasCondition("minecraft:iron_ore", 16);
        Plan plan = buildPlan(List.of(
                new WaitForStep(condition, Duration.ofMinutes(5), RetryPolicy.DEFAULT)));

        String json = PlanSerializer.toJson(plan);
        Plan restored = PlanSerializer.fromJson(json);

        WaitForStep step = (WaitForStep) restored.steps().get(0);
        assertThat(step.timeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(step.condition()).isInstanceOf(InventoryHasCondition.class);

        InventoryHasCondition inv = (InventoryHasCondition) step.condition();
        assertThat(inv.itemId()).isEqualTo("minecraft:iron_ore");
        assertThat(inv.count()).isEqualTo(16);
        assertThat(inv.describe()).isEqualTo("inventory has >= 16 minecraft:iron_ore");
    }

    // -------------------------------------------------------------------------
    // Multi-step plan round-trip
    // -------------------------------------------------------------------------

    @Test
    void multiStep_plan_roundTrip() {
        Plan plan = buildPlan(List.of(
                new NavigateStep(100, 64, 200, Duration.ofMinutes(3), RetryPolicy.NONE),
                new MineStep("minecraft:coal_ore", 16, 32, Duration.ofMinutes(15), RetryPolicy.DEFAULT),
                new WaitForStep(new InventoryHasCondition("minecraft:coal", 16), Duration.ofMinutes(5), RetryPolicy.NONE)
        ));

        String json = PlanSerializer.toJson(plan);
        Plan restored = PlanSerializer.fromJson(json);

        assertThat(restored.steps()).hasSize(3);
        assertThat(restored.steps().get(0)).isInstanceOf(NavigateStep.class);
        assertThat(restored.steps().get(1)).isInstanceOf(MineStep.class);
        assertThat(restored.steps().get(2)).isInstanceOf(WaitForStep.class);
    }

    // -------------------------------------------------------------------------
    // TerminationPolicy round-trip
    // -------------------------------------------------------------------------

    @Test
    void terminationPolicy_roundTrip() {
        Plan plan = new Plan(
                UUID.randomUUID(),
                "Test plan",
                "testplayer",
                Instant.now(),
                List.of(new NavigateStep(0, 64, 0, Duration.ofMinutes(1), RetryPolicy.NONE)),
                new TerminationPolicy(Duration.ofHours(2), false));

        String json = PlanSerializer.toJson(plan);
        Plan restored = PlanSerializer.fromJson(json);

        assertThat(restored.termination().maxDuration()).isEqualTo(Duration.ofHours(2));
        assertThat(restored.termination().stopOnLowHealth()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Step description methods
    // -------------------------------------------------------------------------

    @Test
    void navigateStep_description() {
        assertThat(new NavigateStep(1, 2, 3, Duration.ofMinutes(1), RetryPolicy.NONE).description())
                .isEqualTo("Navigate to (1, 2, 3)");
    }

    @Test
    void mineStep_description() {
        assertThat(new MineStep("minecraft:iron_ore", 64, 32, Duration.ofMinutes(10), RetryPolicy.NONE).description())
                .isEqualTo("Mine 64 minecraft:iron_ore (radius 32)");
    }

    @Test
    void waitForStep_description() {
        WaitForStep step = new WaitForStep(
                new InventoryHasCondition("minecraft:diamond", 3),
                Duration.ofMinutes(5), RetryPolicy.NONE);
        assertThat(step.description()).isEqualTo("Wait for inventory has >= 3 minecraft:diamond");
    }

    // -------------------------------------------------------------------------
    // stepsFromJson helper
    // -------------------------------------------------------------------------

    @Test
    void stepsFromJson_parsesNavigateStep() {
        String json = """
                [{"type":"Navigate","x":10,"y":64,"z":20,"timeout":"PT5M","retry":{"maxRetries":0,"backoff":"PT0S"}}]
                """;
        var steps = PlanSerializer.stepsFromJson(json);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).isInstanceOf(NavigateStep.class);
        NavigateStep nav = (NavigateStep) steps.get(0);
        assertThat(nav.x()).isEqualTo(10);
    }

    @Test
    void stepsFromJson_parsesMineStep() {
        String json = """
                [{"type":"Mine","blockType":"minecraft:coal_ore","quantity":16,"searchRadius":32,"timeout":"PT15M","retry":{"maxRetries":1,"backoff":"PT2S"}}]
                """;
        var steps = PlanSerializer.stepsFromJson(json);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).isInstanceOf(MineStep.class);
    }

    @Test
    void stepsFromJson_throwsOnInvalidJson() {
        assertThatThrownBy(() -> PlanSerializer.stepsFromJson("not-json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Fix 2 — WaitCondition discriminator must be "type" not "kind"
    // -------------------------------------------------------------------------

    @Test
    void Plan_serializeAndDeserialize_waitForStep_usesTypeDiscriminator() {
        WaitCondition condition = new InventoryHasCondition("minecraft:iron_ore", 16);
        Plan plan = buildPlan(List.of(
                new WaitForStep(condition, Duration.ofMinutes(5), RetryPolicy.NONE)));

        String json = PlanSerializer.toJson(plan);

        // The JSON must use "type" as the discriminator, not "kind".
        assertThat(json).contains("\"type\":\"InventoryHas\"");
        assertThat(json).doesNotContain("\"kind\"");

        // Round-trip must survive.
        Plan restored = PlanSerializer.fromJson(json);
        WaitForStep step = (WaitForStep) restored.steps().get(0);
        assertThat(step.condition()).isInstanceOf(InventoryHasCondition.class);
    }

    @Test
    void stepsFromJson_waitForStep_withTypeDiscriminator_parsesCorrectly() {
        // This is the JSON path used by the submitPlan tool: "type" as discriminator.
        String json = """
                [{"type":"WaitFor","condition":{"type":"InventoryHas","itemId":"minecraft:iron_ore","count":16},"timeout":"PT5M","retry":{"maxRetries":0,"backoff":"PT0S"}}]
                """;
        var steps = PlanSerializer.stepsFromJson(json);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).isInstanceOf(WaitForStep.class);
        WaitForStep waitFor = (WaitForStep) steps.get(0);
        assertThat(waitFor.condition()).isInstanceOf(InventoryHasCondition.class);
        InventoryHasCondition inv = (InventoryHasCondition) waitFor.condition();
        assertThat(inv.itemId()).isEqualTo("minecraft:iron_ore");
        assertThat(inv.count()).isEqualTo(16);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Plan buildPlan(List<Step> steps) {
        return new Plan(
                UUID.randomUUID(),
                "Test plan",
                "testplayer",
                Instant.now(),
                steps,
                TerminationPolicy.DEFAULT);
    }
}
