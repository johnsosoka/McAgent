package com.mcagent.core.service;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.model.BotAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyValidatorTest {

    private static final int BOT_Y = 64;
    private static final int MAX_DEPTH = 16;

    private SafetyValidator validator;

    @BeforeEach
    void setUp() {
        BotProperties properties = new BotProperties();
        properties.getSafety().setDangerousBlocks(List.of("TNT", "LAVA"));
        properties.getSafety().setMaxMiningDepth(MAX_DEPTH);
        validator = new SafetyValidator(properties);
    }

    // --- Dangerous block rejection ---

    @Test
    void validate_mineResource_dangerousBlockTnt_rejectsAction() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.MINE)
                .target("TNT_BLOCK")
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action, BOT_Y);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("TNT");
        assertThat(result.reason()).contains("dangerous in safety config");
    }

    @Test
    void validate_mineResource_dangerousBlockLava_rejectsAction() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.MINE)
                .target("flowing_lava")
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action, BOT_Y);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("LAVA");
    }

    // --- Depth limit rejection ---

    @Test
    void validate_mineResource_targetYBelowMaxDepth_rejectsAction() {
        int targetY = BOT_Y - MAX_DEPTH - 1; // one below the allowed limit
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.MINE)
                .target("diamond_ore")
                .y(targetY)
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action, BOT_Y);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("Mining depth limit");
        assertThat(result.reason()).contains(String.valueOf(MAX_DEPTH));
    }

    @Test
    void validate_mineResource_targetYAtExactLimit_allowsAction() {
        int targetY = BOT_Y - MAX_DEPTH; // exactly at the allowed limit
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.MINE)
                .target("iron_ore")
                .y(targetY)
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action, BOT_Y);

        assertThat(result.allowed()).isTrue();
        assertThat(result.reason()).isNull();
    }

    // --- Normal mine accepted ---

    @Test
    void validate_mineResource_safeMine_allowsAction() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.MINE)
                .target("stone")
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action, BOT_Y);

        assertThat(result.allowed()).isTrue();
        assertThat(result.reason()).isNull();
    }

    // --- Non-mine action types always approved ---

    @Test
    void validate_navigate_alwaysAllowed() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.NAVIGATE)
                .target("base")
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action, BOT_Y);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void validate_follow_alwaysAllowed() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.FOLLOW)
                .target("Alice")
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action, BOT_Y);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void validate_rememberLocation_alwaysAllowed() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.REMEMBER_LOCATION)
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action, BOT_Y);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void validate_query_alwaysAllowed() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.QUERY)
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action, BOT_Y);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void validate_cancel_alwaysAllowed() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.CANCEL)
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action, BOT_Y);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void validate_build_safeBlock_isAllowed() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.BUILD)
                .target("minecraft:oak_planks")
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action, BOT_Y);

        assertThat(result.allowed()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void validate_build_tntBlock_rejectsAction() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.BUILD)
                .target("minecraft:tnt")
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action, BOT_Y);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("TNT");
        assertThat(result.reason()).contains("dangerous in safety config");
    }

    @Test
    void validate_build_lavaBlock_rejectsAction() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.BUILD)
                .target("minecraft:lava")
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action, BOT_Y);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("LAVA");
        assertThat(result.reason()).contains("dangerous in safety config");
    }

    @Test
    void validate_build_nullTarget_isAllowed() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.BUILD)
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action, BOT_Y);

        assertThat(result.allowed()).isTrue();
        assertThat(result.reason()).isNull();
    }
}
