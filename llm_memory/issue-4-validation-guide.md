# Issue #4 — In-Game Validation Guide

**Branch:** `issue/4-player-entity-scanning`
**Commit:** `c7e3923`
**JAR deployed:** `~/Library/Application Support/minecraft/mods/mc-agent-fabric-0.2.0-SNAPSHOT-all.jar`

---

## Prerequisites

- Baritone JAR already installed in `~/Library/Application Support/minecraft/mods/`
- `.env` or `mc-agent.env` already in `~/Library/Application Support/minecraft/config/`
- Bot will respond to trigger words: `bot`, `agent`, `mcagent`, `hey bot`

---

## Test Commands

### 1. `locatePlayer` — Find a specific player

**Command:**
```
agent where is <your_player_name>?
```
*(Replace with your actual Minecraft username, e.g. `lexicon_social`)*

**Expected bot response:**
```
Player <name> is at (X, Y, Z), <distance> blocks <direction>
```

**Pass criteria:**
- [ ] Coordinates match your actual position
- [ ] Distance is reasonable (should be small if you're standing near the bot)
- [ ] Direction makes sense (e.g. if you're standing to the bot's east, it says "E")
- [ ] No crash, no NPE in logs

---

### 2. `locatePlayer` — Player not found (offline / far away)

**Command:**
```
agent where is Herobrine?
```

**Expected bot response:**
```
I can't see Herobrine right now. They may be out of range or offline.
```

**Pass criteria:**
- [ ] Graceful not-found message
- [ ] No crash

---

### 3. `scanForPlayers` — Discover nearby players

**Command:**
```
agent who is nearby?
```

**Expected bot response:**
```
Player <name> is at (X, Y, Z), <distance> blocks <direction>
```
*(One line per player found within radius, excluding the bot itself)*

**Pass criteria:**
- [ ] Lists all players in render distance
- [ ] Does NOT list the bot itself
- [ ] Sorted by distance (closest first)
- [ ] No crash

---

### 4. `scanForPlayers` — Empty result

**Command:**
```
agent scan for players within 5 blocks
```
*(Stand far away from the bot so nobody is within 5 blocks)*

**Expected bot response:**
```
No players found within 5 blocks.
```

**Pass criteria:**
- [ ] Empty result message is clear
- [ ] No crash

---

### 5. `scanForEntities` — Find hostile mobs

**Setup:** Spawn or find a hostile mob (Creeper, Zombie, Skeleton, Spider).

**Command:**
```
agent scan for creepers within 50 blocks
```
*(Adjust entity type and radius as needed)*

**Expected bot response:**
```
Creeper at (X, Y, Z), <distance> blocks <direction>
```

**Pass criteria:**
- [ ] Entity type matches what you asked for
- [ ] Coordinates and distance look reasonable
- [ ] Direction is correct
- [ ] No crash

---

### 6. `scanForEntities` — Find passive mobs

**Setup:** Stand near cows, pigs, or chickens.

**Command:**
```
agent scan for cows within 30 blocks
```

**Expected bot response:**
```
Cow at (X, Y, Z), <distance> blocks <direction>
Cow at (X, Y, Z), <distance> blocks <direction>
```
*(One line per cow found)*

**Pass criteria:**
- [ ] Lists multiple entities if present
- [ ] Sorted by distance
- [ ] No crash

---

### 7. `scanForEntities` — Empty result

**Command:**
```
agent scan for endermen within 10 blocks
```
*(In an area with no Endermen)*

**Expected bot response:**
```
No Enderman found within 10 blocks.
```

**Pass criteria:**
- [ ] Graceful empty message
- [ ] No crash

---

### 8. Integration — Pronoun resolution still works

**Command:**
```
agent what is my location?
```

**Expected bot response:**
```
Player <name> is at (X, Y, Z), <distance> blocks <direction>
```

**Pass criteria:**
- [ ] Bot uses `getPlayerPosition()` (not `getCurrentPosition()`)
- [ ] Returns YOUR coordinates, not the bot's

---

### 9. Integration — Navigation still works

**Command:**
```
agent come here
```

**Expected:**
- Bot says "Following player <name>" in chat
- Bot physically follows you

**Pass criteria:**
- [ ] Follow starts immediately
- [ ] `agent stop` still works (CANCEL priority)
- [ ] No crash on follow / cancel

---

### 10. Stress test — Rapid commands

Send these in quick succession:
```
agent scan for zombies
agent where is <name>?
agent scan for players
agent scan for skeletons
```

**Pass criteria:**
- [ ] All commands process without dropping
- [ ] Queue doesn't overflow (inbound capacity = 8)
- [ ] No ConcurrentModificationException

---

## Log Check

After testing, check the log for errors:

```bash
cat ~/Library/Application\ Support/minecraft/logs/latest.log | grep -i 'error\|exception\|npe\|mcagent'
```

**Expected:** No `NullPointerException`, no `ConcurrentModificationException`, no `IllegalStateException`.

---

## Sign-off

If all tests pass, update the logbook and mark:

```
- [x] Fabric integration validation (in-game test)
```

Then the branch is ready for merge to `main`.
