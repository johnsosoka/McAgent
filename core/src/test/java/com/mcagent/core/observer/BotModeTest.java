package com.mcagent.core.observer;

import com.mcagent.core.config.BotProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link BotMode} and the legacy string-conversion shims on
 * {@link BotProperties.ObservationProperties}.
 *
 * <p>Covers the round-trip contract defined in the Sprint 14 A1 brief:
 * {@code "passive" → WATCH}, {@code "active" → AUTO}, {@code "off" → STANDBY},
 * and the reverse direction via {@code getModeString()}.</p>
 */
class BotModeTest {

    // ------------------------------------------------------------------
    // BotMode enum basics
    // ------------------------------------------------------------------

    @Test
    void enumHasExactlyThreeValues() {
        assertThat(BotMode.values()).hasSize(3);
    }

    @Test
    void enumNamesMatchSpec() {
        assertThat(BotMode.STANDBY.name()).isEqualTo("STANDBY");
        assertThat(BotMode.WATCH.name()).isEqualTo("WATCH");
        assertThat(BotMode.AUTO.name()).isEqualTo("AUTO");
    }

    // ------------------------------------------------------------------
    // Default mode on ObservationProperties
    // ------------------------------------------------------------------

    @Test
    void defaultModeIsWatch() {
        var props = new BotProperties.ObservationProperties();
        assertThat(props.getMode()).isEqualTo(BotMode.WATCH);
    }

    // ------------------------------------------------------------------
    // setModeFromString — legacy string → BotMode (round-trip forward)
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "legacy ''{0}'' → BotMode.{1}")
    @CsvSource({
        "passive, WATCH",
        "active,  AUTO",
        "off,     STANDBY",
        "PASSIVE, WATCH",
        "ACTIVE,  AUTO",
        "OFF,     STANDBY",
    })
    void setModeFromString_mapsLegacyStringToCorrectEnum(String legacyValue, BotMode expected) {
        var props = new BotProperties.ObservationProperties();
        props.setModeFromString(legacyValue);
        assertThat(props.getMode()).isEqualTo(expected);
    }

    @Test
    void setModeFromString_unknownValue_throwsIllegalArgument() {
        var props = new BotProperties.ObservationProperties();
        assertThatThrownBy(() -> props.setModeFromString("turbo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("turbo");
    }

    // ------------------------------------------------------------------
    // getModeString — BotMode → legacy string (round-trip reverse)
    // ------------------------------------------------------------------

    @Test
    void getModeString_watch_returnsPassive() {
        var props = new BotProperties.ObservationProperties();
        props.setMode(BotMode.WATCH);
        assertThat(props.getModeString()).isEqualTo("passive");
    }

    @Test
    void getModeString_auto_returnsActive() {
        var props = new BotProperties.ObservationProperties();
        props.setMode(BotMode.AUTO);
        assertThat(props.getModeString()).isEqualTo("active");
    }

    @Test
    void getModeString_standby_returnsOff() {
        var props = new BotProperties.ObservationProperties();
        props.setMode(BotMode.STANDBY);
        assertThat(props.getModeString()).isEqualTo("off");
    }

    // ------------------------------------------------------------------
    // isEnabled shim — derived from mode
    // ------------------------------------------------------------------

    @Test
    void isEnabled_standby_returnsFalse() {
        var props = new BotProperties.ObservationProperties();
        props.setMode(BotMode.STANDBY);
        assertThat(props.isEnabled()).isFalse();
    }

    @Test
    void isEnabled_watch_returnsTrue() {
        var props = new BotProperties.ObservationProperties();
        props.setMode(BotMode.WATCH);
        assertThat(props.isEnabled()).isTrue();
    }

    @Test
    void isEnabled_auto_returnsTrue() {
        var props = new BotProperties.ObservationProperties();
        props.setMode(BotMode.AUTO);
        assertThat(props.isEnabled()).isTrue();
    }

    // ------------------------------------------------------------------
    // setEnabled shim — maps boolean to mode
    // ------------------------------------------------------------------

    @Test
    void setEnabled_false_setsStandby() {
        var props = new BotProperties.ObservationProperties();
        props.setMode(BotMode.AUTO);
        props.setEnabled(false);
        assertThat(props.getMode()).isEqualTo(BotMode.STANDBY);
    }

    @Test
    void setEnabled_true_fromStandby_restoresWatch() {
        var props = new BotProperties.ObservationProperties();
        props.setMode(BotMode.STANDBY);
        props.setEnabled(true);
        assertThat(props.getMode()).isEqualTo(BotMode.WATCH);
    }

    @Test
    void setEnabled_true_whenAlreadyAuto_doesNotDowngradeToWatch() {
        // Enabling when already AUTO should not change the mode to WATCH.
        var props = new BotProperties.ObservationProperties();
        props.setMode(BotMode.AUTO);
        props.setEnabled(true);
        assertThat(props.getMode()).isEqualTo(BotMode.AUTO);
    }

    @Test
    void setEnabled_true_whenAlreadyWatch_keepsWatch() {
        var props = new BotProperties.ObservationProperties();
        props.setMode(BotMode.WATCH);
        props.setEnabled(true);
        assertThat(props.getMode()).isEqualTo(BotMode.WATCH);
    }
}
