# Issues #5 + #6 — Combined In-Game Validation Guide

**Branch:** `issue/6-safety-mode-health-monitoring` (includes #5 advanced pathing)
**JAR deployed:** `~/Library/Application Support/minecraft/mods/mc-agent-fabric-0.2.0-SNAPSHOT-all.jar`

---

## Test Commands

### Issue #5 — Advanced Pathing Goals

| # | Command | Expected Result |
|---|---------|----------------|
| 1 | `agent go to surface 100 200` | Bot paths to X=100, Z=200 (any Y) |
| 2 | `agent go to Y=11` | Bot descends to Y=11 (strip mining depth) |
| 3 | `agent explore near here within 50 blocks` | Bot paths to random point within 50 blocks |
| 4 | `agent run away` (when near a creeper) | Bot retreats from threat location |
| 5 | `agent go to the nearest of home, base` | Bot navigates to closest remembered location |

### Issue #6 — Safety Mode & Health Monitoring

| # | Command | Expected Result |
|---|---------|----------------|
| 6 | `agent be careful` | Safe mode ON — no breaking, no parkour, mob avoidance |
| 7 | `agent go inside` (to a house) | Bot paths to door, does NOT break windows |
| 8 | `agent how are you doing` | Reports health, hunger, armor, nearby threats |
| 9 | `agent scan for creepers` | Lists creepers with distance/direction |
| 10 | `agent set behavior to aggressive` | Bot can break blocks, sprint, parkour |
| 11 | `agent avoid breaking glass` | Adds glass to avoid-breaking list |
| 12 | `agent clear block rules` | Resets all avoidance rules |
| 13 | `agent stop being careful` | Safe mode OFF — normal behavior |

### Integration Tests

| # | Command Sequence | Expected Result |
|---|-----------------|----------------|
| 14 | `agent be careful` → `agent go to my house` | Bot uses door, doesn't break anything |
| 15 | `agent how are you doing` → (spawn zombie) → `agent run away` | Bot reports threat, then flees |
| 16 | `agent go to Y=11` → `agent be careful` | Bot stops at Y=11, won't break blocks for pathing |

---

## Log Check

```bash
cat ~/Library/Application\ Support/minecraft/logs/latest.log | grep -i 'error\|exception\|npe\|mcagent'
```

**Expected:** No `NullPointerException`, no `ConcurrentModificationException`, no `IllegalStateException`.

---

## Sign-off

If all tests pass, mark:
```
- [x] Fabric integration validation (in-game test) — PASSED
```
