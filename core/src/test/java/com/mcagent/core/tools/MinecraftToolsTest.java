package com.mcagent.core.tools;

import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PathResult;
import com.mcagent.core.model.PlayerInfo;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.ChatService;
import com.mcagent.core.memory.LocationMemoryService;
import com.mcagent.core.memory.PlayerNoteService;
import com.mcagent.core.memory.VectorMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinecraftToolsTest {

    private BotOperations bot;
    private ChatService chatService;
    private LocationMemoryService locationMemory;
    private VectorMemoryService vectorMemory;
    private PlayerNoteService playerNoteService;
    private MinecraftTools tools;

    @BeforeEach
    void setUp() {
        bot = mock(BotOperations.class);
        locationMemory = mock(LocationMemoryService.class);
        vectorMemory = mock(VectorMemoryService.class);
        playerNoteService = mock(PlayerNoteService.class);
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
    void navigateToSurface_shouldCallBotNavigateToXZ_andSendChat() {
        when(bot.navigateToXZ(100, 200)).thenReturn(
                PathResult.builder()
                        .success(true)
                        .message("Pathing to surface (100, 200)")
                        .type(PathResult.PathResultType.SUCCESS)
                        .build()
        );

        String result = tools.navigateToSurface(100, 200);

        assertThat(result).isEqualTo("Navigating to surface (100, 200)");
        verify(bot).navigateToXZ(100, 200);
        verify(chatService).send("Navigating to surface coordinates (100, 200)");
    }

    @Test
    void navigateToSurface_shouldReturnErrorMessage_whenPathingFails() {
        when(bot.navigateToXZ(100, 200)).thenReturn(
                PathResult.builder()
                        .success(false)
                        .message("No path found")
                        .type(PathResult.PathResultType.ERROR)
                        .build()
        );

        String result = tools.navigateToSurface(100, 200);

        assertThat(result).isEqualTo("Cannot navigate: No path found");
        verify(bot).navigateToXZ(100, 200);
    }

    @Test
    void goToDepth_shouldCallBotNavigateToYLevel_andSendChat() {
        when(bot.navigateToYLevel(12)).thenReturn(
                PathResult.builder()
                        .success(true)
                        .message("Going to Y=12")
                        .type(PathResult.PathResultType.SUCCESS)
                        .build()
        );

        String result = tools.goToDepth(12);

        assertThat(result).isEqualTo("Going to Y=12");
        verify(bot).navigateToYLevel(12);
        verify(chatService).send("Going to Y=12");
    }

    @Test
    void exploreArea_shouldCallBotExploreNear_andSendChat() {
        when(bot.exploreNear(new BotOperations.Location(10, 64, 20), 50)).thenReturn(
                PathResult.builder()
                        .success(true)
                        .message("Exploring near (10, 64, 20)")
                        .type(PathResult.PathResultType.SUCCESS)
                        .build()
        );

        String result = tools.exploreArea(10, 64, 20, 50);

        assertThat(result).isEqualTo("Exploring within 50 blocks of (10, 64, 20)");
        verify(bot).exploreNear(new BotOperations.Location(10, 64, 20), 50);
        verify(chatService).send("Exploring within 50 blocks of (10, 64, 20)");
    }

    @Test
    void fleeFrom_shouldCallBotFleeFrom_andSendChat() {
        when(bot.fleeFrom(new BotOperations.Location(5, 64, 5), 20)).thenReturn(
                PathResult.builder()
                        .success(true)
                        .message("Fleeing from (5, 64, 5)")
                        .type(PathResult.PathResultType.SUCCESS)
                        .build()
        );

        String result = tools.fleeFrom(5, 64, 5, 20);

        assertThat(result).isEqualTo("Fleeing from (5, 64, 5), maintaining 20 blocks");
        verify(bot).fleeFrom(new BotOperations.Location(5, 64, 5), 20);
        verify(chatService).send("Fleeing to maintain 20 blocks from threat");
    }

    @Test
    void navigateToNearestLocation_shouldParseNames_andCallBotNavigateToNearest() {
        com.mcagent.core.memory.LocationMemoryEntry home = new com.mcagent.core.memory.LocationMemoryEntry();
        home.setName("home");
        home.setX(100);
        home.setY(64);
        home.setZ(100);

        com.mcagent.core.memory.LocationMemoryEntry base = new com.mcagent.core.memory.LocationMemoryEntry();
        base.setName("base");
        base.setX(200);
        base.setY(70);
        base.setZ(200);

        when(locationMemory.findByName("home")).thenReturn(Optional.of(home));
        when(locationMemory.findByName("base")).thenReturn(Optional.of(base));
        when(bot.navigateToNearest(List.of(
                new BotOperations.Location(100, 64, 100),
                new BotOperations.Location(200, 70, 200)
        ))).thenReturn(
                PathResult.builder()
                        .success(true)
                        .message("Navigating to nearest of 2 locations")
                        .type(PathResult.PathResultType.SUCCESS)
                        .build()
        );

        String result = tools.navigateToNearestLocation("home, base");

        assertThat(result).isEqualTo("Navigating to nearest of home, base");
        verify(bot).navigateToNearest(List.of(
                new BotOperations.Location(100, 64, 100),
                new BotOperations.Location(200, 70, 200)
        ));
        verify(chatService).send("Navigating to nearest of home, base");
    }

    @Test
    void goToDepth_shouldReturnErrorMessage_whenPathingFails() {
        when(bot.navigateToYLevel(12)).thenReturn(
                PathResult.builder()
                        .success(false)
                        .message("Cannot reach Y=12")
                        .type(PathResult.PathResultType.ERROR)
                        .build()
        );

        String result = tools.goToDepth(12);

        assertThat(result).isEqualTo("Cannot navigate: Cannot reach Y=12");
        verify(bot).navigateToYLevel(12);
    }

    @Test
    void exploreArea_shouldReturnErrorMessage_whenPathingFails() {
        when(bot.exploreNear(new BotOperations.Location(10, 64, 20), 50)).thenReturn(
                PathResult.builder()
                        .success(false)
                        .message("No path found")
                        .type(PathResult.PathResultType.ERROR)
                        .build()
        );

        String result = tools.exploreArea(10, 64, 20, 50);

        assertThat(result).isEqualTo("Cannot explore: No path found");
        verify(bot).exploreNear(new BotOperations.Location(10, 64, 20), 50);
    }

    @Test
    void fleeFrom_shouldReturnErrorMessage_whenPathingFails() {
        when(bot.fleeFrom(new BotOperations.Location(5, 64, 5), 20)).thenReturn(
                PathResult.builder()
                        .success(false)
                        .message("No escape path")
                        .type(PathResult.PathResultType.ERROR)
                        .build()
        );

        String result = tools.fleeFrom(5, 64, 5, 20);

        assertThat(result).isEqualTo("Cannot flee: No escape path");
        verify(bot).fleeFrom(new BotOperations.Location(5, 64, 5), 20);
    }

    @Test
    void navigateToNearestLocation_shouldReturnNotFound_whenNoLocationsMatch() {
        when(locationMemory.findByName("missing")).thenReturn(Optional.empty());

        String result = tools.navigateToNearestLocation("missing");

        assertThat(result).isEqualTo("None of the specified locations were found: missing");
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

    @Test
    void checkInventory_shouldReturnHasEnough_whenHasItem() {
        when(bot.hasItem("minecraft:cobblestone", 32)).thenReturn(true);
        when(bot.countItem("minecraft:cobblestone")).thenReturn(64);

        String result = tools.checkInventory("minecraft:cobblestone", 32);

        assertThat(result).isEqualTo("You have 64 minecraft:cobblestone (need 32)");
        verify(bot).hasItem("minecraft:cobblestone", 32);
        verify(chatService).send("You have 64 minecraft:cobblestone (need 32)");
    }

    @Test
    void checkInventory_shouldReturnNotEnough_whenMissing() {
        when(bot.hasItem("minecraft:diamond", 5)).thenReturn(false);
        when(bot.countItem("minecraft:diamond")).thenReturn(2);

        String result = tools.checkInventory("minecraft:diamond", 5);

        assertThat(result).isEqualTo("You only have 2 minecraft:diamond (need 5)");
        verify(bot).hasItem("minecraft:diamond", 5);
    }

    @Test
    void getInventorySummary_shouldReturnBotSummary() {
        when(bot.getInventorySummary()).thenReturn("cobblestone x64, oak_planks x32");

        String result = tools.getInventorySummary();

        assertThat(result).isEqualTo("cobblestone x64, oak_planks x32");
        verify(bot).getInventorySummary();
    }
}
