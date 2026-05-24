package com.mcagent.core.service;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.model.BotAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyValidatorTest {

    private SafetyValidator validator;

    @BeforeEach
    void setUp() {
        BotProperties properties = new BotProperties();
        properties.getSafety().setDangerousBlocks(List.of("TNT", "LAVA"));
        validator = new SafetyValidator(properties);
    }

    @Test
    void shouldApproveSafeMining() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.MINE)
                .target("stone")
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action);
        assertThat(result.isApproved()).isTrue();
        assertThat(result.needsConfirmation()).isFalse();
    }

    @Test
    void shouldRequireConfirmationForTnt() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.MINE)
                .target("TNT_BLOCK")
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action);
        assertThat(result.isApproved()).isTrue();
        assertThat(result.needsConfirmation()).isTrue();
        assertThat(result.reason()).contains("TNT");
    }

    @Test
    void shouldRequireConfirmationForLava() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.MINE)
                .target("flowing_lava")
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action);
        assertThat(result.isApproved()).isTrue();
        assertThat(result.needsConfirmation()).isTrue();
        assertThat(result.reason()).contains("LAVA");
    }

    @Test
    void shouldApproveNavigate() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.NAVIGATE)
                .target("base")
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action);
        assertThat(result.isApproved()).isTrue();
        assertThat(result.needsConfirmation()).isFalse();
    }

    @Test
    void shouldApproveFollow() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.FOLLOW)
                .target("Alice")
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action);
        assertThat(result.isApproved()).isTrue();
        assertThat(result.needsConfirmation()).isFalse();
    }

    @Test
    void shouldApproveRememberLocation() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.REMEMBER_LOCATION)
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action);
        assertThat(result.isApproved()).isTrue();
    }

    @Test
    void shouldApproveQuery() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.QUERY)
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action);
        assertThat(result.isApproved()).isTrue();
    }

    @Test
    void shouldApproveCancel() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.CANCEL)
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action);
        assertThat(result.isApproved()).isTrue();
    }

    @Test
    void shouldApproveDefaultAction() {
        BotAction action = BotAction.builder()
                .type(BotAction.ActionType.BUILD)
                .target("house")
                .build();

        SafetyValidator.ValidationResult result = validator.validate(action);
        assertThat(result.isApproved()).isTrue();
        assertThat(result.needsConfirmation()).isFalse();
    }
}
