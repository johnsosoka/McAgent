package com.mcagent.core.tools;

import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PlayerInfo;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.ChatService;
import com.mcagent.core.memory.LocationMemoryService;
import com.mcagent.core.memory.PlayerNoteService;
import com.mcagent.core.memory.VectorMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinecraftToolsTest {

    private BotOperations bot;
    private ChatService chatService;
    private MinecraftTools tools;

    @BeforeEach
    void setUp() {
        bot = mock(BotOperations.class);
        LocationMemoryService locationMemory = mock(LocationMemoryService.class);
        VectorMemoryService vectorMemory = mock(VectorMemoryService.class);
        PlayerNoteService playerNoteService = mock(PlayerNoteService.class);
        chatService = mock(ChatService.class);
        tools = new MinecraftTools(bot, locationMemory, vectorMemory, playerNoteService, chatService);
    }

    @Test
    void locatePlayer_shouldReturnFormattedString_whenPlayerFound() {
        PlayerInfo mockPlayer = PlayerInfo.builder()
                .name("testplayer")
                .location(new BotOperations.Location(50, 64, 50))
                .distance(50.0)
                .direction("SE")
                .build();
        when(bot.findPlayer("testplayer")).thenReturn(mockPlayer);

        String result = tools.locatePlayer("testplayer");

        assertThat(result).isEqualTo("Player testplayer is at (50, 64, 50), 50 blocks SE");
        verify(bot).findPlayer("testplayer");
    }

    @Test
    void locatePlayer_shouldReturnNotFoundMessage_whenPlayerNotFound() {
        when(bot.findPlayer("unknown")).thenReturn(null);

        String result = tools.locatePlayer("unknown");

        assertThat(result).isEqualTo("I can't see unknown right now. They may be out of range or offline.");
        verify(bot).findPlayer("unknown");
    }

    @Test
    void scanForPlayers_shouldReturnFormattedList_whenPlayersFound() {
        PlayerInfo mockPlayer = PlayerInfo.builder()
                .name("testplayer")
                .location(new BotOperations.Location(50, 64, 50))
                .distance(50.0)
                .direction("SE")
                .build();
        when(bot.getNearbyPlayers(50)).thenReturn(List.of(mockPlayer));

        String result = tools.scanForPlayers(50);

        assertThat(result).isEqualTo("Player testplayer is at (50, 64, 50), 50 blocks SE");
        verify(bot).getNearbyPlayers(50);
    }

    @Test
    void scanForPlayers_shouldReturnEmptyMessage_whenNoPlayers() {
        when(bot.getNearbyPlayers(50)).thenReturn(List.of());

        String result = tools.scanForPlayers(50);

        assertThat(result).isEqualTo("No players found within 50 blocks.");
        verify(bot).getNearbyPlayers(50);
    }

    @Test
    void scanForEntities_shouldReturnFormattedList_whenEntitiesFound() {
        EntityInfo mockEntity = EntityInfo.builder()
                .type("Creeper")
                .location(new BotOperations.Location(30, 64, 30))
                .distance(30.0)
                .direction("NW")
                .build();
        when(bot.getNearbyEntities("Creeper", 50)).thenReturn(List.of(mockEntity));

        String result = tools.scanForEntities("Creeper", 50);

        assertThat(result).isEqualTo("Creeper at (30, 64, 30), 30 blocks NW");
        verify(bot).getNearbyEntities("Creeper", 50);
    }

    @Test
    void scanForEntities_shouldReturnEmptyMessage_whenNoEntities() {
        when(bot.getNearbyEntities("Zombie", 50)).thenReturn(List.of());

        String result = tools.scanForEntities("Zombie", 50);

        assertThat(result).isEqualTo("No Zombie found within 50 blocks.");
        verify(bot).getNearbyEntities("Zombie", 50);
    }

    @Test
    void setSafetyMode_shouldCallBotAndChat_whenEnabled() {
        String result = tools.setSafetyMode(true);

        verify(bot).setSafetyMode(true);
        verify(chatService).send("Safe mode enabled. I'll be careful.");
        assertThat(result).isEqualTo("Safe mode enabled");
    }

    @Test
    void setSafetyMode_shouldCallBotAndChat_whenDisabled() {
        String result = tools.setSafetyMode(false);

        verify(bot).setSafetyMode(false);
        verify(chatService).send("Safe mode disabled. Normal behavior restored.");
        assertThat(result).isEqualTo("Safe mode disabled");
    }

    @Test
    void getStatusReport_shouldReturnFormattedStatus_withThreats() {
        when(bot.getHealthStatus()).thenReturn(new BotOperations.HealthStatus(18.0f, 20.0f, 15, 8));
        when(bot.getNearbyThreats(32)).thenReturn(List.of(
                new BotOperations.ThreatInfo("Creeper", new BotOperations.Location(100, 64, 100), 12.0, "NE")
        ));

        String result = tools.getStatusReport();

        assertThat(result).isEqualTo("Status: Health: 18/20, Food: 15/20, Armor: 8. Nearby threats: Creeper at (100, 64, 100), 12 blocks NE");
        verify(bot).getHealthStatus();
        verify(bot).getNearbyThreats(32);
    }

    @Test
    void getStatusReport_shouldReturnFormattedStatus_whenNoThreats() {
        when(bot.getHealthStatus()).thenReturn(new BotOperations.HealthStatus(20.0f, 20.0f, 20, 10));
        when(bot.getNearbyThreats(32)).thenReturn(List.of());

        String result = tools.getStatusReport();

        assertThat(result).isEqualTo("Status: Health: 20/20, Food: 20/20, Armor: 10. Nearby threats: none");
        verify(bot).getHealthStatus();
        verify(bot).getNearbyThreats(32);
    }

    @Test
    void setPathingBehavior_shouldCallBotAndChat_whenCareful() {
        String result = tools.setPathingBehavior("careful");

        verify(bot).setPathingBehavior("careful");
        verify(chatService).send("Pathing behavior set to careful");
        assertThat(result).isEqualTo("Pathing behavior set to careful");
    }

    @Test
    void avoidBreakingBlock_shouldCallBotAndReturnConfirmation() {
        String result = tools.avoidBreakingBlock("minecraft:glass");

        verify(bot).addBlockToAvoid("minecraft:glass");
        assertThat(result).isEqualTo("Added minecraft:glass to avoid-breaking list");
    }

    @Test
    void clearBlockAvoidance_shouldCallBotAndReturnConfirmation() {
        String result = tools.clearBlockAvoidance();

        verify(bot).clearAvoidedBlocks();
        assertThat(result).isEqualTo("Cleared all block avoidance rules.");
    }
}
