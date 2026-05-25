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
    private MinecraftTools tools;

    @BeforeEach
    void setUp() {
        bot = mock(BotOperations.class);
        LocationMemoryService locationMemory = mock(LocationMemoryService.class);
        VectorMemoryService vectorMemory = mock(VectorMemoryService.class);
        PlayerNoteService playerNoteService = mock(PlayerNoteService.class);
        ChatService chatService = mock(ChatService.class);
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
}
