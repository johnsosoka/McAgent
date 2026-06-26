package com.mcagent.core.tools;

import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PathResult;
import com.mcagent.core.model.PlayerInfo;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.ChatService;
import com.mcagent.core.service.LangChain4jService;
import com.mcagent.core.service.PlanDraft;
import com.mcagent.core.service.Planner;
import com.mcagent.core.service.SafetyValidator;
import com.mcagent.core.memory.LocationMemoryService;
import com.mcagent.core.memory.PlayerNoteService;
import com.mcagent.core.memory.VectorMemoryService;
import com.mcagent.core.task.MineStep;
import com.mcagent.core.task.NavigateStep;
import com.mcagent.core.task.PendingApprovalStore;
import com.mcagent.core.task.Plan;
import com.mcagent.core.task.PlanApprovalGate;
import com.mcagent.core.task.RetryPolicy;
import com.mcagent.core.task.TaskExecutor;
import com.mcagent.core.task.TaskSnapshot;
import com.mcagent.core.task.TaskState;
import com.mcagent.core.task.TerminationPolicy;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.JobTracker;
import com.mcagent.core.telemetry.RunLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinecraftToolsTest {

    private BotOperations bot;
    private ChatService chatService;
    private LangChain4jService langChain4jService;
    private LocationMemoryService locationMemory;
    private VectorMemoryService vectorMemory;
    private PlayerNoteService playerNoteService;
    private SafetyValidator safetyValidator;
    private JobTracker jobTracker;
    private Counters counters;
    private RunLog runLog;
    private TaskExecutor taskExecutor;
    private Planner planner;
    private PlanApprovalGate approvalGate;
    private PendingApprovalStore pendingApprovalStore;
    private MinecraftTools tools;

    @BeforeEach
    void setUp() {
        bot = mock(BotOperations.class);
        locationMemory = mock(LocationMemoryService.class);
        vectorMemory = mock(VectorMemoryService.class);
        playerNoteService = mock(PlayerNoteService.class);
        chatService = mock(ChatService.class);
        langChain4jService = mock(LangChain4jService.class);
        safetyValidator = mock(SafetyValidator.class);
        jobTracker = mock(JobTracker.class);
        counters = mock(Counters.class);
        runLog = mock(RunLog.class);
        taskExecutor = mock(TaskExecutor.class);
        planner = mock(Planner.class);
        approvalGate = mock(PlanApprovalGate.class);
        pendingApprovalStore = mock(PendingApprovalStore.class);
        // Default: all actions approved
        when(safetyValidator.validate(any(), anyInt()))
                .thenReturn(SafetyValidator.ValidationResult.approved());
        when(bot.getCurrentPosition()).thenReturn(new BotOperations.Location(0, 64, 0));
        // Default: no approval required (tests that need it override this)
        when(approvalGate.requiresApproval(any())).thenReturn(false);
        tools = new MinecraftTools(bot, locationMemory, vectorMemory, playerNoteService, chatService,
                safetyValidator, jobTracker, counters, runLog, taskExecutor, planner,
                approvalGate, pendingApprovalStore);
        tools.setLangChain4jService(langChain4jService);
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

    @Test
    void buildArea_shouldCallBotAndChat_whenMaterialsSufficient() {
        when(bot.hasItem("minecraft:oak_planks", 25)).thenReturn(true);
        when(bot.countItem("minecraft:oak_planks")).thenReturn(64);
        when(bot.buildPlatform(0, 64, 0, 4, 64, 4, "minecraft:oak_planks")).thenReturn(
                PathResult.builder()
                        .success(true)
                        .message("Building oak_planks 5x1x5")
                        .type(PathResult.PathResultType.SUCCESS)
                        .build()
        );

        String result = tools.buildArea(0, 64, 0, 4, 64, 4, "minecraft:oak_planks");

        assertThat(result).contains("Building minecraft:oak_planks area 5x1x5");
        verify(bot).hasItem("minecraft:oak_planks", 25);
        verify(bot).buildPlatform(0, 64, 0, 4, 64, 4, "minecraft:oak_planks");
        verify(chatService).send("Building minecraft:oak_planks area 5x1x5");
    }

    @Test
    void buildArea_shouldReturnNotEnough_whenMaterialsShort() {
        when(bot.hasItem("minecraft:oak_planks", 25)).thenReturn(false);
        when(bot.countItem("minecraft:oak_planks")).thenReturn(10);

        String result = tools.buildArea(0, 64, 0, 4, 64, 4, "minecraft:oak_planks");

        assertThat(result).contains("Not enough minecraft:oak_planks");
        assertThat(result).contains("Need 25");
        verify(bot).hasItem("minecraft:oak_planks", 25);
        verify(bot, never()).buildPlatform(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void placeBlockAt_shouldCallBotAndChat_whenMaterialAvailable() {
        when(bot.hasItem("minecraft:torch", 1)).thenReturn(true);
        when(bot.placeBlock(100, 65, 100, "minecraft:torch")).thenReturn(
                PathResult.builder()
                        .success(true)
                        .message("Placing torch at (100, 65, 100)")
                        .type(PathResult.PathResultType.SUCCESS)
                        .build()
        );

        String result = tools.placeBlockAt(100, 65, 100, "minecraft:torch");

        assertThat(result).isEqualTo("Placed minecraft:torch at (100, 65, 100)");
        verify(bot).hasItem("minecraft:torch", 1);
        verify(bot).placeBlock(100, 65, 100, "minecraft:torch");
        verify(chatService).send("Placed minecraft:torch at (100, 65, 100)");
    }

    @Test
    void placeBlockAt_shouldReturnNoMaterial_whenMissing() {
        when(bot.hasItem("minecraft:torch", 1)).thenReturn(false);

        String result = tools.placeBlockAt(100, 65, 100, "minecraft:torch");

        assertThat(result).contains("No minecraft:torch in inventory");
        verify(bot).hasItem("minecraft:torch", 1);
        verify(bot, never()).placeBlock(anyInt(), anyInt(), anyInt(), any());
    }

    // -------------------------------------------------------------------------
    // Sprint 12 C3 — survival tools
    // -------------------------------------------------------------------------

    // --- eatFood ---

    @Test
    void eatFood_withItemId_callsBotEat() {
        when(bot.eat("minecraft:bread")).thenReturn(true);
        when(bot.getHealthStatus())
                .thenReturn(new BotOperations.HealthStatus(20f, 20f, 10, 0))
                .thenReturn(new BotOperations.HealthStatus(20f, 20f, 15, 0));

        String result = tools.eatFood("minecraft:bread");

        verify(bot).eat("minecraft:bread");
        assertThat(result).contains("Ate minecraft:bread").contains("15/20");
    }

    @Test
    void eatFood_withNull_callsBotEatWithNull() {
        when(bot.eat(null)).thenReturn(true);
        when(bot.getHealthStatus())
                .thenReturn(new BotOperations.HealthStatus(20f, 20f, 8, 0))
                .thenReturn(new BotOperations.HealthStatus(20f, 20f, 14, 0));

        String result = tools.eatFood(null);

        verify(bot).eat(null);
        assertThat(result).contains("Ate food").contains("14/20");
    }

    @Test
    void eatFood_failsWhenNoFood() {
        when(bot.eat("minecraft:golden_carrot")).thenReturn(false);
        when(bot.getHealthStatus()).thenReturn(new BotOperations.HealthStatus(20f, 20f, 20, 0));

        String result = tools.eatFood("minecraft:golden_carrot");

        verify(bot).eat("minecraft:golden_carrot");
        assertThat(result).contains("Could not eat").contains("minecraft:golden_carrot");
    }

    // --- placeTorchAtFeet ---

    @Test
    void placeTorchAtFeet_succeeds() {
        when(bot.hasItem("minecraft:torch", 1)).thenReturn(true);

        String result = tools.placeTorchAtFeet();

        verify(bot).hasItem("minecraft:torch", 1);
        verify(bot).placeTorch();
        assertThat(result).isEqualTo("Torch placed.");
    }

    @Test
    void placeTorchAtFeet_failsWhenNoTorch() {
        when(bot.hasItem("minecraft:torch", 1)).thenReturn(false);

        String result = tools.placeTorchAtFeet();

        verify(bot).hasItem("minecraft:torch", 1);
        verify(bot, never()).placeTorch();
        assertThat(result).isEqualTo("No torch in inventory.");
    }

    // --- getWorldTime ---

    @Test
    void getWorldTime_returnsTimeString_duringDay() {
        when(bot.getWorldTime()).thenReturn(6000);
        when(bot.isNight()).thenReturn(false);

        String result = tools.getWorldTime();

        assertThat(result).isEqualTo("World time: 6000 (day).");
    }

    @Test
    void getWorldTime_returnsTimeString_duringNight() {
        when(bot.getWorldTime()).thenReturn(18000);
        when(bot.isNight()).thenReturn(true);

        String result = tools.getWorldTime();

        assertThat(result).isEqualTo("World time: 18000 (night).");
    }

    // --- isNight ---

    @Test
    void isNight_returnsTrueString_whenNight() {
        when(bot.isNight()).thenReturn(true);
        when(bot.getWorldTime()).thenReturn(18000);

        String result = tools.isNight();

        assertThat(result).contains("Yes").contains("night").contains("18000");
    }

    @Test
    void isNight_returnsFalseString_whenDay() {
        when(bot.isNight()).thenReturn(false);
        when(bot.getWorldTime()).thenReturn(6000);

        String result = tools.isNight();

        assertThat(result).contains("No").contains("daytime").contains("6000");
    }

    // --- getLightLevel ---

    @Test
    void getLightLevel_returnsInt_withSafeLabel() {
        when(bot.getLightLevel()).thenReturn(12);

        String result = tools.getLightLevel();

        assertThat(result).isEqualTo("Light level: 12 (safe).");
    }

    @Test
    void getLightLevel_returnsInt_withSpawnRiskLabel() {
        when(bot.getLightLevel()).thenReturn(3);

        String result = tools.getLightLevel();

        assertThat(result).contains("3").contains("mob spawning possible");
    }

    // --- pickupNearbyItems ---

    @Test
    void pickupNearbyItems_returnsCountString() {
        when(bot.pickupNearbyItems(16)).thenReturn("Targeting 4 item(s) within 16 blocks.");

        String result = tools.pickupNearbyItems(16);

        verify(bot).pickupNearbyItems(16);
        assertThat(result).contains("4").contains("16");
    }

    @Test
    void pickupNearbyItems_returnsNoItemsMessage_whenEmpty() {
        when(bot.pickupNearbyItems(8)).thenReturn("No items found within 8 blocks.");

        String result = tools.pickupNearbyItems(8);

        verify(bot).pickupNearbyItems(8);
        assertThat(result).contains("No items");
    }

    // --- cancelCurrentOperation ---

    @Test
    void cancelCurrentOperation_callsBotCancelAndClearsJob() {
        String result = tools.cancelCurrentOperation();

        verify(bot).cancel();
        verify(jobTracker).clearJob();
        assertThat(result).isEqualTo("Cancelled current operation.");
    }

    // --- SafetyValidator wiring ---

    @Test
    void mineResource_safetyValidatorRejects_returnsReasonWithoutCallingBot() {
        when(safetyValidator.validate(any(), anyInt()))
                .thenReturn(SafetyValidator.ValidationResult.rejected("Cannot interact with TNT — listed as dangerous in safety config."));

        String result = tools.mineResource("TNT", 1, null);

        assertThat(result).contains("Cannot interact with TNT");
        verify(bot, never()).mine(any(), anyInt(), any());
    }

    @Test
    void mineResource_safetyValidatorApproves_callsBot() {
        when(safetyValidator.validate(any(), anyInt()))
                .thenReturn(SafetyValidator.ValidationResult.approved());
        when(bot.mine(anyString(), anyInt(), any())).thenReturn(
                PathResult.builder()
                        .success(true)
                        .message("Mining started")
                        .type(PathResult.PathResultType.SUCCESS)
                        .build()
        );

        String result = tools.mineResource("iron_ore", 32, 64);

        assertThat(result).contains("Mining iron_ore");
        verify(bot).mine("iron_ore", 32, 64);
    }

    @Test
    void buildArea_safetyValidatorRejects_returnsReasonWithoutCallingBot() {
        when(safetyValidator.validate(any(), anyInt()))
                .thenReturn(SafetyValidator.ValidationResult.rejected("Cannot interact with TNT — listed as dangerous in safety config."));

        String result = tools.buildArea(0, 64, 0, 4, 64, 4, "tnt");

        assertThat(result).contains("Cannot interact with TNT");
        verify(bot, never()).buildPlatform(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void placeBlockAt_safetyValidatorRejects_returnsReasonWithoutCallingBot() {
        when(safetyValidator.validate(any(), anyInt()))
                .thenReturn(SafetyValidator.ValidationResult.rejected("Cannot interact with LAVA — listed as dangerous in safety config."));

        String result = tools.placeBlockAt(0, 64, 0, "minecraft:lava");

        assertThat(result).contains("Cannot interact with LAVA");
        verify(bot, never()).placeBlock(anyInt(), anyInt(), anyInt(), any());
    }

    // -------------------------------------------------------------------------
    // Sprint 13 C1 — submitPlan tool
    // -------------------------------------------------------------------------

    @Test
    void submitPlan_successPath_callsExecutorAndReturnsConfirmation() {
        Plan capturedPlan[] = new Plan[1];
        UUID taskId = UUID.randomUUID();

        when(taskExecutor.submit(any())).thenAnswer(inv -> {
            Plan plan = inv.getArgument(0);
            capturedPlan[0] = plan;
            return new TaskSnapshot(taskId, plan, TaskState.RUNNING, 0, "starting",
                    Instant.now(), Optional.empty());
        });

        String stepsJson = """
                [{"type":"Navigate","x":100,"y":64,"z":200,"timeout":"PT5M","retry":{"maxRetries":0,"backoff":"PT0S"}}]
                """;

        String result = tools.submitPlan("Navigate to base", stepsJson);

        assertThat(result).contains("Plan submitted");
        assertThat(result).contains("Navigate to base");
        assertThat(result).contains("1 steps");
        verify(taskExecutor).submit(any());
        verify(chatService).send(contains("Plan submitted"));
    }

    @Test
    void submitPlan_invalidJson_returnsErrorMessage() {
        String result = tools.submitPlan("Bad plan", "not-valid-json");

        assertThat(result).contains("Invalid steps JSON");
        verify(taskExecutor, never()).submit(any());
    }

    @Test
    void submitPlan_emptySteps_returnsErrorMessage() {
        String result = tools.submitPlan("Empty plan", "[]");

        assertThat(result).contains("empty");
        verify(taskExecutor, never()).submit(any());
    }

    @Test
    void submitPlan_alreadyRunning_returnsConflictMessage() {
        when(taskExecutor.submit(any())).thenThrow(new IllegalStateException("A plan is already running"));

        String stepsJson = """
                [{"type":"Navigate","x":10,"y":64,"z":20,"timeout":"PT1M","retry":{"maxRetries":0,"backoff":"PT0S"}}]
                """;

        String result = tools.submitPlan("Second plan", stepsJson);

        assertThat(result).contains("already running");
    }

    @Test
    void getPlanStatus_returnsStatusWhenActive() {
        Plan plan = new Plan(UUID.randomUUID(), "Mine iron", "player", Instant.now(),
                List.of(new NavigateStep(10, 64, 20, Duration.ofMinutes(5), RetryPolicy.NONE)),
                TerminationPolicy.DEFAULT);
        TaskSnapshot snap = new TaskSnapshot(plan.id(), plan, TaskState.RUNNING, 0,
                "10 blocks away", Instant.now(), Optional.empty());

        when(taskExecutor.currentTask()).thenReturn(Optional.of(snap));

        String result = tools.getPlanStatus();

        assertThat(result).contains("Mine iron");
        assertThat(result).contains("RUNNING");
    }

    @Test
    void getPlanStatus_returnsNoneWhenIdle() {
        when(taskExecutor.currentTask()).thenReturn(Optional.empty());

        String result = tools.getPlanStatus();

        assertThat(result).contains("No active plan");
    }

    @Test
    void cancelPlan_cancelsRunningPlan() {
        UUID taskId = UUID.randomUUID();
        Plan plan = new Plan(taskId, "Mine iron", "player", Instant.now(),
                List.of(new NavigateStep(10, 64, 20, Duration.ofMinutes(5), RetryPolicy.NONE)),
                TerminationPolicy.DEFAULT);
        TaskSnapshot snap = new TaskSnapshot(taskId, plan, TaskState.RUNNING, 0,
                "", Instant.now(), Optional.empty());

        when(taskExecutor.currentTask()).thenReturn(Optional.of(snap));
        when(taskExecutor.cancel(taskId)).thenReturn(true);

        String result = tools.cancelPlan();

        assertThat(result).contains("cancelled");
        assertThat(result).contains("Mine iron");
        verify(taskExecutor).cancel(taskId);
    }

    @Test
    void cancelPlan_noActivePlan_returnsNothingToCancel() {
        when(taskExecutor.currentTask()).thenReturn(Optional.empty());

        String result = tools.cancelPlan();

        assertThat(result).contains("No active plan");
        verify(taskExecutor, never()).cancel(any());
    }

    // -------------------------------------------------------------------------
    // Sprint 13 A5 — post-condition framework messages
    // -------------------------------------------------------------------------

    @Test
    void mineResource_emitsAsyncPendingMessage_notImmediateDelta() {
        // mineResource is async (Baritone) — Fix 3 changed it to emit "verification pending"
        // instead of an immediate (and incorrect) delta against an unchanged inventory.
        when(safetyValidator.validate(any(), anyInt()))
                .thenReturn(SafetyValidator.ValidationResult.approved());
        when(bot.countItem("minecraft:iron_ore")).thenReturn(0);
        when(bot.mine(anyString(), anyInt(), anyInt())).thenReturn(
                com.mcagent.core.model.PathResult.builder()
                        .success(true)
                        .message("Mining started")
                        .type(com.mcagent.core.model.PathResult.PathResultType.SUCCESS)
                        .build()
        );

        tools.mineResource("minecraft:iron_ore", 4, 32);

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(langChain4jService).addFrameworkContext(captor.capture());
        String msg = captor.getValue();
        assertThat(msg).contains("tool:mineResource");
        assertThat(msg).contains("expected:");
        assertThat(msg).contains("async, verification pending");
        // Must NOT report a delta that would be a false negative.
        assertThat(msg).doesNotContain("observed:");
        assertThat(msg).doesNotContain("short");
    }

    @Test
    void mineResource_asyncBehavior_inventoryNotSampledAfterReturn() {
        // Verify that bot.countItem is NOT called after the tool returns (no "after" snapshot).
        when(safetyValidator.validate(any(), anyInt()))
                .thenReturn(SafetyValidator.ValidationResult.approved());
        when(bot.countItem("minecraft:iron_ore")).thenReturn(0);
        when(bot.mine(anyString(), anyInt(), anyInt())).thenReturn(
                com.mcagent.core.model.PathResult.builder()
                        .success(true)
                        .message("Mining started")
                        .type(com.mcagent.core.model.PathResult.PathResultType.SUCCESS)
                        .build()
        );

        tools.mineResource("minecraft:iron_ore", 4, 32);

        // For async tools no before snapshot is captured either — countItem called 0 times.
        verify(bot, never()).countItem("minecraft:iron_ore");
    }

    @Test
    void followPlayer_doesNotEmitFrameworkMessage_forNoOpTool() {
        when(bot.followPlayer(anyString())).thenReturn(
                com.mcagent.core.model.PathResult.builder()
                        .success(true)
                        .message("Following")
                        .type(com.mcagent.core.model.PathResult.PathResultType.SUCCESS)
                        .build()
        );

        tools.followPlayer("steve");

        verify(langChain4jService, never()).addFrameworkContext(anyString());
    }

    // -------------------------------------------------------------------------
    // Sprint 13 A2 — decomposeAndExecute tool
    // -------------------------------------------------------------------------

    @Test
    void decomposeAndExecute_callsPlannerAndSubmitsToExecutor() {
        // Planner returns a single-navigate draft
        PlanDraft draft = new PlanDraft(
                "Navigate to base",
                List.of(Map.of("type", "Navigate", "x", 100, "y", 64, "z", 200))
        );
        when(planner.propose("go to base")).thenReturn(draft);

        UUID taskId = UUID.randomUUID();
        when(taskExecutor.submit(any())).thenAnswer(inv -> {
            Plan plan = inv.getArgument(0);
            return new TaskSnapshot(taskId, plan, TaskState.RUNNING, 0, "starting",
                    Instant.now(), Optional.empty());
        });

        String result = tools.decomposeAndExecute("go to base");

        verify(planner).propose("go to base");
        verify(taskExecutor).submit(any());
        verify(chatService).send(contains("Plan submitted"));
        assertThat(result).contains("Plan submitted");
        assertThat(result).contains("Navigate to base");
        assertThat(result).contains("1 steps");
    }

    @Test
    void decomposeAndExecute_returnsPlanRejectedMessage_whenDraftIsInvalid() {
        // Planner returns a draft with a null summary — converter will reject it
        PlanDraft badDraft = new PlanDraft(null, List.of(Map.of("type", "Navigate", "x", 0, "y", 64, "z", 0)));
        when(planner.propose("bad request")).thenReturn(badDraft);

        String result = tools.decomposeAndExecute("bad request");

        verify(planner).propose("bad request");
        verify(taskExecutor, never()).submit(any());
        assertThat(result).contains("Plan rejected");
    }

    @Test
    void decomposeAndExecute_returnsPlanRejectedMessage_whenStepTypeIsUnknown() {
        PlanDraft draft = new PlanDraft(
                "Craft a pickaxe",
                List.of(Map.of("type", "Craft", "item", "minecraft:iron_pickaxe"))
        );
        when(planner.propose("craft a pickaxe")).thenReturn(draft);

        String result = tools.decomposeAndExecute("craft a pickaxe");

        verify(planner).propose("craft a pickaxe");
        verify(taskExecutor, never()).submit(any());
        assertThat(result).contains("Plan rejected");
        assertThat(result).contains("Craft");
    }

    // -------------------------------------------------------------------------
    // Sprint 13 Q3 — pre-execution approval gate
    // -------------------------------------------------------------------------

    @Test
    void decomposeAndExecute_pendingApprovalForComplexPlan_returnsPreviewNotSubmission() {
        // 4-step plan (exceeds threshold of 3)
        PlanDraft draft = new PlanDraft(
                "Mine and store iron",
                List.of(
                    Map.of("type", "Navigate", "x", 100, "y", 64, "z", 200),
                    Map.of("type", "Mine", "blockType", "minecraft:iron_ore",
                           "quantity", 64, "searchRadius", 32),
                    Map.of("type", "Navigate", "x", 0, "y", 64, "z", 0),
                    Map.of("type", "Mine", "blockType", "minecraft:iron_ore",
                           "quantity", 64, "searchRadius", 32)
                )
        );
        when(planner.propose("mine and store iron")).thenReturn(draft);
        // Override: this plan requires approval
        when(approvalGate.requiresApproval(any())).thenReturn(true);

        String result = tools.decomposeAndExecute("mine and store iron");

        // Executor must NOT be called
        verify(taskExecutor, never()).submit(any());
        // Plan must be stored for later approval
        verify(pendingApprovalStore).set(any());
        // Result is a preview, not a submission confirmation
        assertThat(result).doesNotContain("Plan submitted");
        assertThat(result).contains("Mine and store iron");
        assertThat(result).contains("agent go");
        // Preview length must be bounded
        assertThat(result.length()).isLessThanOrEqualTo(240);
    }

    @Test
    void formatPlanPreview_includesSummaryStepsAndEstimate() {
        Plan plan = new Plan(UUID.randomUUID(), "Explore base", "player", Instant.now(),
                List.of(
                    new NavigateStep(10, 64, 20, Duration.ofMinutes(3), RetryPolicy.NONE),
                    new MineStep("minecraft:iron_ore", 16, 32, Duration.ofMinutes(4), RetryPolicy.NONE)
                ),
                TerminationPolicy.DEFAULT);

        String preview = tools.formatPlanPreview(plan);

        assertThat(preview).contains("Explore base");
        assertThat(preview).contains("1.");
        assertThat(preview).contains("2.");
        assertThat(preview).contains("7m");   // 3+4 = 7 minutes
        assertThat(preview).contains("agent go");
        assertThat(preview.length()).isLessThanOrEqualTo(240);
    }

    @Test
    void formatPlanPreview_truncatesLongPlanToMaxLength() {
        // Build a plan with many long step descriptions
        List<com.mcagent.core.task.Step> steps = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            steps.add(new NavigateStep(i * 100, 64, i * 100, Duration.ofMinutes(10), RetryPolicy.NONE));
        }
        Plan plan = new Plan(UUID.randomUUID(),
                "A very long plan with many steps to exercise truncation", "player", Instant.now(),
                steps, TerminationPolicy.DEFAULT);

        String preview = tools.formatPlanPreview(plan);

        assertThat(preview.length()).isLessThanOrEqualTo(240);
    }

    // -------------------------------------------------------------------------
    // Fix 3 — async tool must not report false failure
    // -------------------------------------------------------------------------

    @Test
    void withToolScope_asyncTool_doesNotReportFalseFailure() {
        when(safetyValidator.validate(any(), anyInt()))
                .thenReturn(SafetyValidator.ValidationResult.approved());
        // Inventory count unchanged (Baritone hasn't run yet — as expected for async).
        when(bot.countItem("minecraft:iron_ore")).thenReturn(0);
        when(bot.mine(anyString(), anyInt(), anyInt())).thenReturn(
                com.mcagent.core.model.PathResult.builder()
                        .success(true)
                        .message("Mining started")
                        .type(com.mcagent.core.model.PathResult.PathResultType.SUCCESS)
                        .build()
        );

        tools.mineResource("minecraft:iron_ore", 4, 32);

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(langChain4jService).addFrameworkContext(captor.capture());
        String msg = captor.getValue();
        // Must say "verification pending", NOT report "observed: 0 (4 short)".
        assertThat(msg).contains("verification pending");
        assertThat(msg).doesNotContain("short");
        assertThat(msg).doesNotContain("observed: +0");
    }

    // -------------------------------------------------------------------------
    // Fix 4 — planner retry on failure
    // -------------------------------------------------------------------------

    @Test
    void decomposeAndExecute_plannerFailsTwice_returnsUserFriendlyMessage() {
        when(planner.propose(anyString()))
                .thenThrow(new RuntimeException("LLM timeout"))
                .thenThrow(new RuntimeException("LLM timeout again"));

        String result = tools.decomposeAndExecute("mine 64 iron ore");

        verify(planner, times(2)).propose("mine 64 iron ore");
        verify(taskExecutor, never()).submit(any());
        assertThat(result).contains("Planner failed");
        assertThat(result).contains("rephrasing");
    }

    // -------------------------------------------------------------------------
    // Fix 8 — replacement notice when pending plan already exists
    // -------------------------------------------------------------------------

    @Test
    void decomposeAndExecute_replacesExistingPendingPlan_prependsNotice() {
        // Existing pending plan
        Plan existingPlan = new Plan(UUID.randomUUID(), "Old plan", "player", Instant.now(),
                List.of(new NavigateStep(0, 64, 0, Duration.ofMinutes(2), RetryPolicy.NONE)),
                TerminationPolicy.DEFAULT);
        when(pendingApprovalStore.peek()).thenReturn(Optional.of(existingPlan));

        // New plan requiring approval
        PlanDraft draft = new PlanDraft(
                "New plan",
                List.of(
                    Map.of("type", "Navigate", "x", 100, "y", 64, "z", 200),
                    Map.of("type", "Mine", "blockType", "minecraft:iron_ore",
                           "quantity", 64, "searchRadius", 32),
                    Map.of("type", "Navigate", "x", 0, "y", 64, "z", 0),
                    Map.of("type", "Mine", "blockType", "minecraft:iron_ore",
                           "quantity", 64, "searchRadius", 32)
                )
        );
        when(planner.propose(anyString())).thenReturn(draft);
        when(approvalGate.requiresApproval(any())).thenReturn(true);

        String result = tools.decomposeAndExecute("mine and store more iron");

        assertThat(result).startsWith("Replacing previous pending plan.");
        verify(pendingApprovalStore).set(any());
    }
}
