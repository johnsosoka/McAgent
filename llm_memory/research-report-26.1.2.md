# Research Report: McAgent on Minecraft 26.1.2

**Date:** 2026-05-24
**Status:** CRITICAL VERSION MISMATCH IDENTIFIED
**Researcher:** AI Lead (OpenCode)

---

## Executive Summary

The target server runs **Minecraft 26.1.2** (Mojang's new year-based versioning, released April 9, 2026). Our current mod is built for **Forge 1.20.1-47.1.0** with **Java 17**. This is a significant version gap requiring substantial porting effort.

**Key Finding:** Forge 26.1.2-64.0.8 EXISTS, but Baritone (our pathfinding dependency) does NOT yet officially support 26.1.2 on Forge. An open PR exists with Fabric support working, but Forge/NeoForge builds are broken due to Unimined toolchain issues.

---

## 1. Minecraft 26.1.2 Analysis

### What is 26.1.2?
- **Official Name:** "Tiny Takeover" update
- **Release Date:** April 9, 2026
- **Versioning:** Mojang's new year-based scheme (26 = 2026, 1 = major, 2 = minor)
- **Equivalent Era:** Roughly comparable to the 1.21.x codebase evolution
- **Java Requirement:** **Java 25** (majorVersion: 25 in version.json)
- **Asset Index:** 30
- **Log Config:** client-1.21.2.xml

### Critical Difference from 1.20.1
| Aspect | Our Build (1.20.1) | Target (26.1.2) |
|--------|-------------------|-----------------|
| Java Version | 17 | **25** |
| Forge Version | 47.1.0 | **64.0.8** |
| Mappings | MCP/Official | **Mojang Official (Mojmap)** |
| Release Date | June 2023 | April 2026 |
| Mod Loader API | Stable | **Potentially Breaking Changes** |

---

## 2. Forge Availability: ✅ CONFIRMED

**Forge 26.1.2-64.0.8** is available and actively maintained.

- **Download URL:** https://files.minecraftforge.net/net/minecraftforge/forge/index_26.1.2.html
- **Latest Version:** 64.0.8 (released May 4, 2026)
- **Gradle Coordinates:** `net.minecraftforge:forge:26.1.2-64.0.8`
- **Status:** Production-ready, actively updated

The user already has the correct installer (`forge-26.1.2-64.0.8-installer.jar`), but it was blocked by macOS Gatekeeper.

---

## 3. Baritone Availability: ⚠️ BLOCKED

### Current Status
Baritone does **NOT** have an official release for 26.1.2 on Forge.

### Open Development
- **PR #4990:** "Port to 26.1: Java 25, Mojmap" by fnltochka
- **Status:** Open, in progress
- **Repository:** https://github.com/cabaletta/baritone/pull/4990
- **Fork:** https://github.com/fnltochka/baritone/tree/26.1

### What Works
- ✅ **Fabric** build compiles and runs on 26.1.2
- ✅ Java 25 compatibility resolved
- ✅ Mojang mappings (Mojmap) migration complete

### What's Broken
- ❌ **Forge** build fails due to Unimined toolchain issues
- ❌ **NeoForge** build also broken (same Unimined issue)
- **Issue:** Unimined (the Gradle plugin Baritone uses for multi-loader builds) has compatibility problems with ForgeGradle 6.x on 26.1.2

### Available Releases (for reference)
Baritone's latest official release is **v1.15.0** which supports:
- 1.21.6, 1.21.7, 1.21.8 (Forge/Fabric/NeoForge)
- 1.21.4 (v1.13.1)

**No official support for 26.1.2 yet.**

---

## 4. Porting Assessment

### Option A: Wait for Official Baritone Release
**Effort:** Low (wait only)
**Timeline:** Unknown - could be weeks to months
**Risk:** PR #4990 has been open since April 2026 with no merge date. Unimined issue may take significant time to resolve.

### Option B: Port McAgent to Fabric
**Effort:** HIGH
**Timeline:** 2-4 weeks
**Details:**
- Rewrite mod entry point from Forge to Fabric
- Rewrite event handlers (chat events, tick events)
- Baritone Fabric API is available from PR #4990 fork
- Our core logic (Spring Boot + LangChain4j) is loader-agnostic and can be preserved
**Blocker:** We'd need to build Baritone Fabric from the fork ourselves

### Option C: Use Baritone PR Fork Directly
**Effort:** MEDIUM-HIGH
**Timeline:** 1-2 weeks
**Details:**
- Clone fnltochka's fork: `git clone -b 26.1 https://github.com/fnltochka/baritone`
- Build Fabric version locally
- Port our mod to Fabric with the custom Baritone build
**Risk:** Using unreleased/unmerged code, maintenance burden

### Option D: Fix Baritone Forge Build
**Effort:** VERY HIGH
**Timeline:** Unknown (depends on Unimined fix)
**Details:**
- Debug Unimined + ForgeGradle 6.x compatibility
- Contribute fix upstream or maintain patch
- Requires deep Gradle/Forge toolchain knowledge
**Risk:** May be beyond our scope

### Option E: Port to NeoForge
**Effort:** HIGH
**Timeline:** 2-4 weeks
**Details:**
- NeoForge is a Forge fork that may have different compatibility
- PR #4990 shows NeoForge is also broken (same Unimined issue)
- Not a viable alternative until Unimined is fixed

---

## 5. Java 25 Requirement

**Critical:** 26.1.2 requires Java 25, not Java 17.

### Current Environment
- User has Java 17 installed (`/opt/homebrew/opt/openjdk@17`)
- Our build is configured for Java 17

### Required Changes
1. Install Java 25 (Azul Zulu 25 recommended - Temurin 24+ drops jmods, breaking ProGuard)
2. Update `build.gradle` Java toolchain from 17 to 25
3. Update all dependencies to Java 25-compatible versions
4. Verify Spring Boot 3.4.0 supports Java 25 (it should, Spring Boot 3.x targets Java 17+)

---

## 6. Immediate Action Items

### For Research Team
1. **Monitor Baritone PR #4990** - Check for merge or Forge fix
   - URL: https://github.com/cabaletta/baritone/pull/4990
   - Set up GitHub notifications or daily checks

2. **Evaluate Fabric Ecosystem** - Research Fabric mod development:
   - Fabric API for 26.1.2
   - Fabric Loader version compatibility
   - Chat event handling in Fabric (vs Forge)

3. **Assess Java 25 Compatibility** - Verify our dependencies:
   - Spring Boot 3.4.0 on Java 25
   - LangChain4j 1.15.0 on Java 25
   - H2 database driver compatibility
   - All other dependencies

4. **Alternative Pathfinding Libraries** - Research if Baritone can be replaced:
   - MineBot (Baritone's predecessor)
   - Custom A* pathfinding implementation
   - Other pathfinding mods for 26.1.2

### For Development Team
1. **Install Java 25**:
   ```bash
   brew install --cask zulu-jdk25
   # OR
   brew install openjdk@25
   ```

2. **Set up Forge 26.1.2 dev environment**:
   ```bash
   # Download and install
   java -jar forge-26.1.2-64.0.8-installer.jar --installClient
   ```

3. **Test vanilla 26.1.2** - Ensure the game launches without mods

4. **Prototype Fabric port** - Create minimal proof-of-concept:
   - Simple "Hello World" Fabric mod for 26.1.2
   - Integrate with Baritone Fabric fork
   - Test chat event handling

---

## 7. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Baritone never supports 26.1.2 Forge | Medium | CRITICAL | Port to Fabric or replace pathfinding |
| Java 25 breaks dependencies | Low-Medium | HIGH | Test all deps, use compatible versions |
| Fabric API missing features | Medium | MEDIUM | Implement workarounds or hybrid approach |
| Porting takes >1 month | High | MEDIUM | Start with minimal viable mod, iterate |
| Shadow JAR crashes Forge scanner | High (for 1.20.1) | MEDIUM | Use library folder approach instead |

---

## 8. Recommendation

**Primary Path:** Port to **Fabric** using the Baritone PR fork.

**Rationale:**
1. Fabric version of Baritone for 26.1.2 WORKS TODAY (from the fork)
2. Fabric has a simpler, more stable API than Forge
3. Fabric community is often faster to adopt new Minecraft versions
4. Our core logic (Spring + LangChain4j) is completely decoupled from the mod loader

**Secondary Path:** Wait for official Baritone release while preparing the Fabric port in parallel.

**Do NOT:**
- Continue developing for 1.20.1 (version mismatch is fatal)
- Attempt to fix Unimined ourselves (too deep in toolchain internals)
- Port to NeoForge (same Unimined issue as Forge)

---

## 9. Resources for Research Team

### Key URLs
- Minecraft 26.1.2 Wiki: https://minecraft.wiki/w/Java_Edition_26.1.2
- Forge Downloads: https://files.minecraftforge.net/net/minecraftforge/forge/index_26.1.2.html
- Baritone PR #4990: https://github.com/cabaletta/baritone/pull/4990
- Baritone Fork (26.1): https://github.com/fnltochka/baritone/tree/26.1
- Fabric Wiki: https://fabricmc.net/wiki/
- Java 25 Release Notes: https://openjdk.org/projects/jdk/25/

### GitHub Issues to Watch
- Baritone #4990 (26.1 port)
- Baritone #5011 ("anyone have baritone for 26.1.2")

---

## 10. Next Steps

1. **Decision Point:** Does the team want to proceed with Fabric porting, or wait for official Baritone/Forge support?
2. If proceeding: Set up Java 25 + Fabric dev environment + Baritone fork
3. If waiting: Monitor PR #4990 daily, check for merge or Forge fix
4. Either way: Begin documenting Fabric API differences from Forge for our use cases

**Prepared by:** AI Technical Lead
**For:** John Sosoka & Research Team
