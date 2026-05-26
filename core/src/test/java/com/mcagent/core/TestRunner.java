package com.mcagent.core;

import com.mcagent.core.memory.LocationMemoryService;
import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PathResult;
import com.mcagent.core.model.PlayerInfo;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.tools.MinecraftTools;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Scanner;

/**
 * Standalone test runner for exercising the McAgent LLM pipeline without Minecraft.
 *
 * <p>Bootstraps the core Spring context with a {@link BotOperations} mock and runs
 * an interactive REPL. Type prompts and watch tool responses. This is useful for
 * verifying Fireworks.ai connectivity, tool wiring, and memory services.</p>
 *
 * <p>Run with:
 * <pre>
 *   export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
 *   export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
 *   export FIREWORKS_API_KEY=...
 *   mvn -pl mc-agent-core exec:java
 * </pre>
 */
@Slf4j
public class TestRunner {

    public static void main(String[] args) {
        log.info("Starting McAgent standalone test runner...");

        String apiKey = System.getenv("FIREWORKS_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("FIREWORKS_API_KEY environment variable is not set.");
            System.err.println("Set it and re-run:");
            System.err.println("  export FIREWORKS_API_KEY=your_key_here");
            System.exit(1);
        }

        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(TestConfig.class);
        ctx.refresh();

        MinecraftTools tools = ctx.getBean(MinecraftTools.class);
        LocationMemoryService locationMemory = ctx.getBean(LocationMemoryService.class);

        printBanner();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine();
            if ("exit".equalsIgnoreCase(input.trim())) {
                break;
            }
            if (input.isBlank()) {
                continue;
            }

            try {
                String result = handleDirectCommand(tools, input);
                System.out.println(result);
            } catch (Exception e) {
                log.error("Error processing command", e);
                System.out.println("Error: " + e.getMessage());
            }
        }

        ctx.close();
        System.out.println("Goodbye.");
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("=== McAgent Standalone Test Runner ===");
        System.out.println("Available commands:");
        System.out.println("  navigate <x> <y> <z>    — path to coordinates");
        System.out.println("  mine <block> <qty> [r]  — mine block type (e.g. DIAMOND_ORE)");
        System.out.println("  remember <name> [desc]  — save current location");
        System.out.println("  find <query>             — search saved locations");
        System.out.println("  note <content>           — save a note for testplayer");
        System.out.println("  recall [query]           — recall notes for testplayer");
        System.out.println("  cancel                   — cancel current operation");
        System.out.println("  pos                      — show mock current position");
        System.out.println("  exit                     — quit");
        System.out.println();
    }

    private static String handleDirectCommand(MinecraftTools tools, String input) {
        String lower = input.toLowerCase().trim();
        String[] tokens = lower.split("\\s+", 2);
        String cmd = tokens[0];
        String rest = tokens.length > 1 ? tokens[1] : "";

        return switch (cmd) {
            case "navigate", "goto" -> {
                String[] parts = rest.trim().split("\\s+");
                if (parts.length >= 3) {
                    yield tools.navigateTo(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                }
                yield "Usage: navigate <x> <y> <z>";
            }
            case "mine" -> {
                String[] parts = rest.trim().split("\\s+");
                if (parts.length >= 2) {
                    String block = parts[0].toUpperCase();
                    int qty = Integer.parseInt(parts[1]);
                    Integer radius = parts.length > 2 ? Integer.parseInt(parts[2]) : null;
                    yield tools.mineResource(block, qty, radius);
                }
                yield "Usage: mine <block_type> <quantity> [radius]";
            }
            case "remember" -> {
                String[] parts = rest.split(" ", 2);
                String name = parts[0];
                String desc = parts.length > 1 ? parts[1] : "";
                yield tools.rememberLocation(name, desc);
            }
            case "find" -> tools.findLocation(rest);
            case "note" -> tools.rememberNote("testplayer", rest, "");
            case "recall" -> tools.recallNotes("testplayer", rest.isBlank() ? null : rest);
            case "cancel" -> tools.cancelCurrentOperation();
            case "pos" -> tools.getCurrentPosition();
            default -> "Unknown command. Try: navigate, mine, remember, find, note, recall, cancel, pos, exit";
        };
    }

    /**
     * Test-only Spring configuration that provides a mock {@link BotOperations} bean
     * before importing the real core so all downstream beans (tools, assistant) get
     * injected with the mock.
     */
    @Configuration
    @Import(CoreApplication.class)
    static class TestConfig {

        @Bean
        public BotOperations botOperations() {
            return new MockBotOperations();
        }
    }

    static class MockBotOperations implements BotOperations {
        @Override
        public PathResult navigateTo(int x, int y, int z) {
            log.info("[MOCK] navigateTo({}, {}, {})", x, y, z);
            return PathResult.builder()
                    .success(true)
                    .message("Mock pathing to (" + x + ", " + y + ", " + z + ")")
                    .build();
        }

        @Override
        public PathResult navigateTo(String locationName) {
            return PathResult.builder()
                    .success(false)
                    .message("Mock: use coordinates")
                    .build();
        }

        @Override
        public PathResult followPlayer(String playerName) {
            log.info("[MOCK] followPlayer({})", playerName);
            return PathResult.builder()
                    .success(true)
                    .message("Mock following " + playerName)
                    .build();
        }

        @Override
        public PathResult mine(String blockType, int maxBlocks, Integer radius) {
            log.info("[MOCK] mine({}, {}, {})", blockType, maxBlocks, radius);
            return PathResult.builder()
                    .success(true)
                    .message("Mock mining " + blockType)
                    .build();
        }

        @Override
        public void cancel() {
            log.info("[MOCK] cancel");
        }

        @Override
        public void pause() {
            log.info("[MOCK] pause");
        }

        @Override
        public void resume() {
            log.info("[MOCK] resume");
        }

        @Override
        public Location getCurrentPosition() {
            return new Location(100, 64, 200);
        }

        @Override
        public java.util.Optional<Location> getPlayerPosition(String playerName) {
            log.info("[MOCK] getPlayerPosition({})", playerName);
            return java.util.Optional.of(new Location(50, 64, 50));
        }

        @Override
        public void setProgressCallback(java.util.function.Consumer<String> callback) {
            // no-op in test mock
        }

        @Override
        public PlayerInfo findPlayer(String playerName) {
            if ("testplayer".equalsIgnoreCase(playerName)) {
                return PlayerInfo.builder()
                        .name("testplayer")
                        .location(new Location(50, 64, 50))
                        .distance(50.0)
                        .direction("SE")
                        .build();
            }
            return null;
        }

        @Override
        public List<PlayerInfo> getNearbyPlayers(int radius) {
            return List.of(
                    PlayerInfo.builder()
                            .name("testplayer")
                            .location(new Location(50, 64, 50))
                            .distance(50.0)
                            .direction("SE")
                            .build()
            );
        }

        @Override
        public List<EntityInfo> getNearbyEntities(String entityType, int radius) {
            return List.of(
                    EntityInfo.builder()
                            .type(entityType)
                            .location(new Location(30, 64, 30))
                            .distance(30.0)
                            .direction("NW")
                            .build()
            );
        }

        @Override
        public PathResult navigateToXZ(int x, int z) {
            log.info("[MOCK] navigateToXZ({}, {})", x, z);
            return PathResult.builder()
                    .success(true)
                    .message("Mock pathing to surface (" + x + ", " + z + ")")
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        }

        @Override
        public PathResult navigateToYLevel(int y) {
            log.info("[MOCK] navigateToYLevel({})", y);
            return PathResult.builder()
                    .success(true)
                    .message("Mock going to Y=" + y)
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        }

        @Override
        public PathResult exploreNear(Location center, int radius) {
            log.info("[MOCK] exploreNear({}, {})", center, radius);
            return PathResult.builder()
                    .success(true)
                    .message("Mock exploring near " + center)
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        }

        @Override
        public PathResult fleeFrom(Location threat, int safeDistance) {
            log.info("[MOCK] fleeFrom({}, {})", threat, safeDistance);
            return PathResult.builder()
                    .success(true)
                    .message("Mock fleeing from " + threat)
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        }

        @Override
        public PathResult navigateToNearest(List<Location> candidates) {
            log.info("[MOCK] navigateToNearest({} locations)", candidates.size());
            return PathResult.builder()
                    .success(true)
                    .message("Mock navigating to nearest of " + candidates.size() + " locations")
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        }

        @Override
        public void setSafetyMode(boolean enabled) {
            log.info("[MOCK] setSafetyMode({})", enabled);
        }

        @Override
        public HealthStatus getHealthStatus() {
            return new HealthStatus(18.0f, 20.0f, 15, 8);
        }

        @Override
        public List<ThreatInfo> getNearbyThreats(int radius) {
            return List.of(
                    new ThreatInfo("Creeper", new Location(100, 64, 100), 12.0, "NE")
            );
        }

        @Override
        public void setPathingBehavior(String mode) {
            log.info("[MOCK] setPathingBehavior({})", mode);
        }

        @Override
        public void addBlockToAvoid(String blockType) {
            log.info("[MOCK] addBlockToAvoid({})", blockType);
        }

        @Override
        public void clearAvoidedBlocks() {
            log.info("[MOCK] clearAvoidedBlocks");
        }

        @Override
        public boolean hasItem(String itemId, int count) {
            log.info("[MOCK] hasItem({}, {})", itemId, count);
            return List.of("minecraft:cobblestone", "minecraft:oak_planks", "minecraft:bread")
                    .contains(itemId.toLowerCase());
        }

        @Override
        public int countItem(String itemId) {
            log.info("[MOCK] countItem({})", itemId);
            return List.of("minecraft:cobblestone", "minecraft:oak_planks", "minecraft:bread")
                    .contains(itemId.toLowerCase()) ? 64 : 0;
        }

        @Override
        public String getInventorySummary() {
            return "cobblestone x64, oak_planks x32, bread x16, iron_pickaxe x1";
        }

        @Override
        public PathResult buildPlatform(int x1, int y1, int z1, int x2, int y2, int z2, String blockType) {
            log.info("[MOCK] buildPlatform({}, {}, {}, {}, {}, {}, {})", x1, y1, z1, x2, y2, z2, blockType);
            int width = Math.abs(x2 - x1) + 1;
            int height = Math.abs(y2 - y1) + 1;
            int length = Math.abs(z2 - z1) + 1;
            return PathResult.builder()
                    .success(true)
                    .message("Mock building " + blockType + " " + width + "x" + height + "x" + length)
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        }

        @Override
        public PathResult placeBlock(int x, int y, int z, String blockType) {
            log.info("[MOCK] placeBlock({}, {}, {}, {})", x, y, z, blockType);
            return PathResult.builder()
                    .success(true)
                    .message("Mock placing " + blockType + " at (" + x + ", " + y + ", " + z + ")")
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        }
    }
}
