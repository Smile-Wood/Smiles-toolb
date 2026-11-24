# Smile's toolb

[中文文档](README.zh-CN.md)

A Fabric mod for Minecraft 1.20.1 – 26.2, **one jar for all versions**, depending only on the prerequisite library `Smile's Lib Box`. Its original feature is **replacing the narrator's voice with a system speech engine**.

Voices come from two sources, switchable in the config screen:

- **Local**: the OneCore voice store and legacy SAPI5 desktop voices, synthesised fully offline;
- **Online**: Microsoft Edge neural voices (Xiaoxiao, Yunxi and others), synthesised in the cloud and requiring a network connection.

## How it works

| Layer | Description |
| --- | --- |
| Hook | `@Mixin(com.mojang.text2speech.Narrator.class)` injects into the static factory `getNarrator()` and wraps the vanilla implementation in `SystemNarrator` on return |
| Speech | `SystemNarrator` only cleans the text and forwards it; vanilla's on / all / chat / system state machine, chat narration and screen narration are untouched |
| Local synthesis | The JVM has neither a WinRT nor a SAPI binding, so a long-lived child process drives both local engines at once: **OneCore** (`Windows.Media.SpeechSynthesis`) and **SAPI5** (`System.Speech`), with audio played through `SoundPlayer` |
| Online synthesis | Edge's read-aloud service only accepts WebSocket and only returns MP3: the protocol and the `Sec-MS-GEC` token are implemented on the JVM side (`java.net.http.WebSocket`) with connection reuse; the MP3 is written to disk and played by the same child process through WinRT `MediaPlayer`, and `STOP` cancels both paths at once |
| Configuration | The screen is a **preset** (`SmileToolbConfig`, declaring only pages, rows, ranges and read/write callbacks); rendering is done by `smile_libbox.api.ui.ConfigScreen` from the prerequisite library: tabs, toggles, sliders and reset keys are all vanilla widgets, with textures and hover states matching the game's own screens. The actual values still live in `config/smile_toolb/tts.json` |

Failure fallback: if the engine is not ready, is switched off, or synthesis fails, everything falls back to the wrapped vanilla narrator — there is never silence.

## Hot reload

Saving the configuration takes effect immediately, **without restarting the game**:

- Voice, rate, volume and the per-utterance character limit apply to the next utterance;
- Master switch: enabling it starts the helper process, disabling it stops it in the background;
- After changing the voice, a sample sentence is spoken with the new voice automatically; press **F9** to preview at any time;
- Enabling "turn narrator on at startup" switches the vanilla narrator mode to "all" immediately;
- If the engine fails to start, saving the configuration again retries at once; if the helper process exits unexpectedly it is restarted on the next utterance.

## Usage

1. The mod is a **single jar** (`smile_toolb-1.0.36.jar`, works on 1.20.1 – 26.2).
   It also needs one prerequisite: **Smile's Lib Box** (install the jar matching the target game version). The screen needs no
   third-party GUI library; Mod Menu is only an optional entry point that adds a "Config" button in the mod list.
2. Press **F8** in game to open the config screen, **F9** to preview the current voice, or rebind in "Controls → Smile's toolb".
3. Vanilla narration is off by default; toggle it with **Ctrl + B**, or enable "turn narrator on at startup" in the config.
4. The voice list is the union of both engines and broadly matches the "Choose a voice" list under Windows *Accessibility → Narrator*.
5. The config screen is titled **Smile's toolb 配置** and starts with a row of tab buttons (the current tab is greyed out). There are two pages:
   - **Narrator**: master switch and startup behaviour, rate and volume, per-utterance character limit; at the bottom are two buttons this mod adds —
     "Voice: <current voice>" and "Preview" — because the voice list is runtime-enumerated dynamic data and does not fit a normal config row;
   - **Misc**: "use legacy experience bar behaviour", "advanced status bar control" (only shown on 1.21.9+ or with VanillaBackport installed,
     filtered by the runtime probe `BarFeatureDetector`, and hidden entirely on 1.20.1), "keep chat box contents" and "draft retention",
     "summon count", "exit key mapping" and the exit key list, plus the bottom row "Summon dummies / Remove dummies (N)"
     (available only when the server exposes Carpet's `/player` command; while a batch job runs both buttons are disabled
     and a red line underneath shows "批量执行中，剩余 N 条命令").
6. The "Voice" button opens a separate **voice picker screen**: a search box at the top, All / Local / Online filters, a scrollable list
   (each row annotated on the right with OneCore / SAPI5 / Online and the language), and "online voices", "preview", "done" at the bottom.
   Clicking a row switches and previews immediately and writes the choice to disk; `↑` / `↓` change the voice and Enter replays.
   This keeps the config page short no matter how many voices exist.
7. The "online voices" switch sits at the bottom-left of the picker and controls whether Edge voices join the list, marked "online"
   there. Online voices are filtered by game language (14 of them in a Chinese environment), so the service's 300-odd voices do not flood the list.

## Status bar control (Misc, 1.21.9+ / VanillaBackport)

From 1.21.9 the strip above the hotbar became the "contextual bar": whenever a teammate with waypoints is nearby
(`WaypointManager#hasWaypoints()` is true) it shows the **locator bar** and pushes the experience bar out; the experience bar only
returns for 100 ticks (5 seconds) after gaining experience. And while riding a jumpable mount vanilla unconditionally shows the jump bar,
whether or not you jump. "Play with teammates and lose your experience bar" is exactly what those rules produce.

Misc has two switches, the second a stronger version of the first:

- **Use legacy experience bar behaviour** (on by default): suppresses the locator bar so this strip is always the experience bar, with everything else
  vanilla (still the jump bar while riding). Equivalent to 1.21.8 and earlier.
- **Advanced status bar control** (off by default, requires the previous one): switches automatically by situation —
  - default: experience bar;
  - holding the **player list key** (Tab by default) with teammate waypoints nearby: locator bar (it would be empty without waypoints,
    so the experience bar stays);
  - while riding a jumpable mount and holding **jump**: charge bar, kept for **2.5 seconds** after release, then back to the experience bar.

Versions below 1.21.9 without VanillaBackport have no contextual bar, so neither switch is shown; the fields remain in the config file
so settings survive moving between versions. The 1.20.1 + VanillaBackport differences are covered in the next section.

Gating looks only at `GameVersion.atLeast("1.21.9")`: year-based versions such as 26.2 compare semantically,
`26 > 1` holds, so newer versions take the `VANILLA` path directly. Measured: the 26.2 config screen shows
12 rows / 4 switches with both tracker-bar switches visible; 1.20.1 shows 9 rows / 2 switches with the tracker-bar switches hidden,
every other option intact and the screen opening normally. Both reach the main menu with zero exceptions; the log lines are
`状态条特性检测` and `配置界面自检`, see section 0 of `VERIFY.md`.

Implementation: inject into the RETURN of `Gui#nextContextualInfoState()` and replace the final state outright. The reason for no longer
patching `willPrioritizeExperienceInfo()` is that vanilla takes the unconditional `if (jumpable) return
JUMPABLE_VEHICLE` branch when there are no waypoints, so that switch is never evaluated at all; making "riding without jumping returns to the experience bar"
requires changing the return value. `Gui.ContextualInfo` is a package-private enum whose type cannot be referenced in source, so the return value is received as
`Object` and `ContextualBarController` reflectively reads the four constants and caches them (production constant names are intermediary
names; when they cannot be recognised the constants are placed in declaration order EMPTY / EXPERIENCE / LOCATOR / JUMPABLE_VEHICLE). The 2.5-second window
is tracked by `ClientTickEvents.END_CLIENT_TICK` on jump key press / release. This injection is compiled per game version by the prerequisite library
and gated by `LibBoxMixinPlugin`; older versions do not enable that mixin.

### Older versions with a locator-bar backport mod

Older versions (1.20.1 – 1.21.8) have no vanilla contextual bar, but modpacks often include a mod that backports the locator bar.
`BarFeatureDetector` decides at startup, by reliability, whether the two switches should appear:

1. Game version ≥ 1.21.9: vanilla contextual bar, `ForceExperienceBarMixin` takes over;
2. **VanillaBackport (1.20.1, id `vanillabackport`)**: specifically adapted, see the next section;
3. Older version but `Gui` contains a method "returning that four-state enum": judged to be another mod that also backported the vanilla contextual-bar API
   (located by return type, not by method name; not yet adapted);
4. Otherwise inferred from the ids / name keywords of loaded mods (`locator` / `waypoint`, plus the known `wayfarer`).

When case 3 or 4 hits, both switches are likewise listed under "Misc" with a compatibility note. **The boundary must be stated clearly**:
those mods draw their own bar and never go through vanilla `Gui`; the library jar for older versions does not enable the
vanilla contextual-bar mixin. The switches therefore remain (config values survive version changes) but do not change the third-party locator
bar, which must be configured in that mod's own settings. There is no common interface between mods; each one needs individual adaptation.

#### VanillaBackport 1.2.2 (specific adaptation)

Neither this mod's id nor its name contains `locator` / `waypoint`, so keyword inference misses it; it is now identified exactly by
mod id plus the presence of the `LocatorBarRenderer` class (log keyword `检测到 VanillaBackport 定位条`).

Its locator bar is **overlay-based**: the experience / jump bar is still drawn, and only when
`LocatorBarRenderer#shouldSkipRendering()` returns false is a locator-bar background and waypoint icons drawn on top at the same position. That method is called by both
`renderBackground()` and `renderWaypoints()`, making it the single master gate.

- `VanillaBackportBarMixin` is a soft-target mixin (`@Mixin(targets = ..., remap = false)` +
  `require = 0`) that injects into the RETURN of that method and rewrites the return value, controlling background and icons together;
- it lives in a separate `smile_toolb.backport.mixins.json` (`required: false`) and is gated by
  `BackportMixinPlugin` on whether `vanillabackport` is loaded. Without that mod the mixin class
  is never parsed, so it cannot compete for the same method with another mod;
- `BackportBridge` reads `ClientWaypointManager#hasWaypoints()` and
  `ClientConfig#enableLocatorBar` reflectively, ensuring "no empty bar without waypoints" and respecting the backport's own master switch.
  Any reflection failure falls back to "do not interfere" instead of crashing.

Both switches work on 1.20.1: with only "use legacy experience bar behaviour" the locator bar is always skipped and the strip is always the experience bar;
enabling "advanced status bar control" adds the default experience bar, locator bar while holding Tab, and the charge bar while riding with jump held, kept for
2.5 seconds after release.

**Known difference**: on 1.20.1 vanilla `Gui#render` calls `renderJumpMeter` unconditionally when `jumpableVehicle() != null`,
so riding without jumping shows the (empty) jump bar, and it cannot switch back to the experience bar as 1.21.9+ can. That is dictated by the vanilla
structure, and this mod does not force a rewrite.

**Configuration conflict**: if the backport's own `locator_display_info_bar` is enabled it skips the locator-bar background early when there is experience or while riding,
leaving only icons and no bar in advanced mode while Tab is held. The config screen detects this and prompts you to turn it off.

## Keeping chat box contents (Misc)

On by default. Closing and reopening chat, rejoining the server shortly after, or logging in from another client restores the
last **unsent** input; once a message is sent the draft is cleared at once.

- Entry: config screen → **Misc** → the "keep chat box contents" switch and "draft retention"
  (60–3600 seconds, default 600 / 10 minutes), with a line below showing how many drafts are cached.
- **Keyed only by server address**: the key is the address the player entered (`ServerData.ip`), case-insensitive,
  with or without the default `:25565` port treated as the same server; different servers never interfere, and player name, world name and client
  instance are irrelevant. The address comes from the library's `Ui.serverAddress()`; when kicked or disconnected it remembers the last address briefly,
  so a draft is not stored under the wrong key at that moment.
- The draft file is `.smile_toolb/chat-drafts.json` in the **user home directory**, not `.minecraft/config/` —
  clients with different game directories or launchers read the same file, which is why "log in from another client" restores drafts.
  Writes use an atomic temporary-file replacement, the file is deleted once drafts are empty, and expired entries are cleaned on every read and write.
- The injection points are compiled per version by the library (`ChatScreen#init` restore, `onClose` capture, `removed` fallback,
  `handleChatInput` clear); this mod registers pure-Java callbacks only, so its side still has zero MC references.
- **Text is captured at the HEAD of `onClose`**, not by waiting for `removed()`: from 1.21.x vanilla processes
  its own in-session draft on close and may clear the box on the way, leaving only an empty string by the time `removed()` runs. One opening captures once
  (after `onClose` has captured, `removed` stays out of the way), and a "clear within 400 ms of saving" is additionally blocked
  so a duplicate callback cannot wipe the draft that was just stored.
- **"Sent" is not "closed"**: vanilla `handleChatInput` only sends the packet and does not clear the `EditBox`; `keyPressed`
  calls `setScreen(null)` right afterwards, so `removed()` still reads the line that was just sent.
  The library therefore records the submitted text and, in `removed()`, treats unchanged content as "closed after sending" and does not persist it
  (1.21.x vanilla achieves the same with `exitReason == DONE`; 1.20.x has no such field).
- Newer vanilla versions have their own in-session draft, so this feature restores only when the box is empty and never overwrites what vanilla just saved.

A per-scenario manual checklist is in section 7 of `VERIFY.md`.

## Combat assist (Combat assist page)

Two features share one **armour lock state machine** (`smile_toolb.client.combat.ArmorLock`):

| State | Meaning |
| --- | --- |
| `IDLE` | Terrain is safe, no interference at all |
| `GUARDED` | Terrain is dangerous, guarding |
| `LOCKED` | Occupied by the mace flow, left only by that flow |

While locked, container actions that would "put an elytra in the chest slot" are dropped **before the packet is sent** (through the library's `Packets` black-box interception,
which touches no MC packet types); should a mod bypass the send path and write the slot directly, the per-tick re-check swaps it back.
When the player opens their own inventory and swaps the elytra, everything is allowed — that is the criterion for "not a manual player action".

In the guarded state three things **must never touch** the chest slot:

- **Gliding**: the elytra is the only thing keeping you alive at that moment, and removing it means a fatal fall (while gliding the rule is "guard, do not strip": the state stays
  `GUARDED` so packet interception keeps working, only the swap-back logic is skipped);
- **Actually falling / already airborne** (≥ 1.75 blocks above the surface, higher than the 1.25-block apex of a normal jump): an automatically equipped elytra is often
  the last resort there, and things can be tidied up after landing. **Being knocked back, launched by a mob or thrown by an explosion**
  is exactly the case this rule yields to — earlier it only yielded above 3 blocks, and under a ceiling
  (clearance ≤ 3) it ignored height entirely and locked all the way down, so "launched but no elytra" was stuck precisely
  in the two-to-three-block range;
- **Wearing an elytra when the guard starts**: that is the player's own gear; the requirement only forbids "automatic switching", not tearing down their setup.
  Only an elytra "put on automatically during the guard" is swapped back for armour.

The criterion is not `onGround` but the scan's `drop` (height above the surface) plus clearance:

| Situation | Verdict |
| --- | --- |
| More than 3 blocks above the surface (fully left the cell) | Yield |
| ≥ 1.75 blocks above the surface (airborne, including being launched) | Yield — the elytra is the means of self-rescue |
| Clearance ≤ 3 and hugging the ground (under 1.75) | Guard; running and jumping are intercepted too |
| Open narrow path and genuinely standing on it (≤ 0.6 above) | Guard |
| Open narrow path and already jumped (over 0.6 above) | Yield — that is "wanting to fly", not a violation |

The last two rows fix "yanked down right after take-off": on an open narrow path the player jumps to glide, feet barely a block or two off the ground,
and stripping the just-equipped elytra would feel like intent being judged as a violation and the player being pulled down.

The 1.75 threshold: a normal jump apex is 1.25 blocks, below which it must be "the moment of jumping", when an elytra would only cause a head bump —
exactly what the narrow-path protection must stop. Above it the player was thrown by an external force, and wearing an elytra may not let them fly, but not wearing one
guarantees no chance. After landing from such a launch the guard takes over as usual. The cost is that another mod has a window to insert an elytra, but after landing
`baselineElytra` is still false and `enforce()` swaps it back for armour.

The terrain baseline was fixed as well: snow layers, carpets and slabs have collision heights under one block, so the foot position falls inside that cell.
Previously the cell was taken as a ceiling, clearance computed as 0, and standing on flat ground was judged dangerous (measured logs flip-flopped every tick between
"guard start" and "guard stop"), stripping the automatically equipped elytra as soon as the player jumped. Such blocks are now treated as **ground**.

**Clearance is now a continuous height** (`World.blockHeight()`: carpet 0.0625, snow layer 0.125, slab 0.5, full block 1.0). Previously it was counted as
"whole cells + `isSolid`", so carpets counted both as ground and as ceiling: standing on two carpets lifted the foot position cell by cell to 46 / 47,
and a four-block-high corridor measured as two or even one block — the logs showed `净空=1，离地=0.1` while standing still, followed by a false guard and a stripped
automatically equipped elytra. Now the surface is "the top face of accumulated collision heights" (two carpets = 45.0625), only full blocks count as ceiling,
a four-block corridor reads 3.9 → safe, a three-block one reads 2.9 → guarded as before.

The terrain scan runs **every tick** (the earlier 5-tick interval could not keep up with running and jumping: the player was already inside while the state still said "safe").
The expensive platform check only runs when "clearance sufficient + width ≤ 3", the only case where it can change the verdict.

The swap-back target is the **strongest** armour in the inventory: enchantment count first, then max durability
(leather 81 / gold 113 / iron and chain 241 / diamond 529 / netherite 592).

### Elytra safety guard

The decision lives in `smile_toolb.client.combat.Terrain`; either of two dangerous conditions triggers the guard:

| Condition | Meaning |
| --- | --- |
| Clearance ≤ 3 blocks | Continuous height from the surface to the ceiling underside; an elytra would hit the head (thin blocks only subtract their own collision height) |
| Open sky + surface width ≤ 3 + no 5×5 platform within 5 blocks below on either side | Falling would have nothing to land on |

**A 1×1 shaft is the exception**: a vertical passage one block wide, walled on all four sides and at least 4 blocks tall is judged safe — that is a shaft you can
climb, not a narrow path where "flying out means falling". The two one-block-wide terrains are distinguished by **whether all four sides are walls**: a single-block
path in mid-air has air on all sides → dangerous; a shaft has walls on all four sides → allowed to take off. One-block pits three or four blocks deep are still guarded by clearance.

Both measurements are taken at **the nearest surface under the feet** (the first solid block within 12 blocks down) rather than at the foot position itself:
as soon as the player jumps there is air underfoot, and measuring width at foot height would always give 1, making even a jump on flat ground look like a narrow path.
If no ground is found within 12 blocks (high altitude) the position counts as suspended and the width rule does not apply.

Four baseline scenarios are checked by `python tools/selftest-terrain/run.py` (pure Java, no game launch):

| Scenario | Clearance | Width | Open sky | Result |
| --- | --- | --- | --- | --- |
| Single-block path in mid-air | 64 | 1 | Yes | Dangerous |
| Underground, 2 blocks high, 1 wide | 2 | 1 | No | Dangerous |
| Underground 3×3 corridor | 3 | 3 | No | Dangerous |
| Underground 4×4 corridor | 4 | 4 | No | Safe |
| Jumping 1.5 blocks on open grass | 64 | 17 | Yes | Safe |
| 3 blocks above a single-block path | 64 | 1 | Yes | Dangerous |
| Hovering 20 blocks up | 64 | 8 | Yes | Safe |
| Four-block corridor with two carpets | 3.9 | 3 | No | Safe |
| Three-block corridor with two carpets | 2.9 | 3 | No | Dangerous |
| 1×1 shaft (walls on all four sides) | 64 | 1 | Yes | Safe |
| 1×1 three-deep pit (with ceiling) | 3 | 1 | No | Dangerous |
| Niche with two walls | 64 | 1 | Yes | Dangerous |

The config screen's bottom bar permanently shows a line such as `护甲锁：守卫中|净空=2.0 宽=1 露天=否 平台=有 危险=是`,
and every state flip also writes a `护甲锁：` line to the log.

### Fireworks inserted by a mod while guarding

Fighting in a narrow corridor with no weapon in hand breaks attacks immediately — typically caused by tweakeroo's "auto switch rockets",
which hooks the take-off check and moves the best rocket in the inventory **into the currently held slot**.

The decision (`combat/HandSwap`, pure Java) recognises exactly one shape:

| What happened between two ticks | Who did it | Handling |
| --- | --- | --- |
| Selected slot unchanged, the item in it became a firework | A mod moved a rocket into that slot | Swap the previous weapon back |
| Selected slot changed (switched to the slot holding fireworks) | Player scroll wheel / keys 1–9 | Leave it alone |

No player key can change what is inside a slot **without changing the selected slot**, so that difference is enough to separate
"a mod's automatic switch" from "the player's own choice" — which is the difference between "swap back if a mod switched to fireworks" and
"fireworks may never be in hand".

Four other cases are also left alone: **gliding** (rockets are the means of acceleration), **inventory or container open** (the player's own mouse),
**environment safe** (no interference), **already out of the dangerous cell** (jumping off an open narrow path to fly, exactly when rockets are wanted).

The swap-back uses `World.holdItem(previousWeapon)`: switch slots if it is on the hotbar, otherwise send a container-click packet to move it
(server-authoritative, so it works on multiplayer too). A 10-tick cooldown follows, so clicks are never spammed.
Log: `护甲锁：守卫中手里的烟花是别的模组换上的，已换回 minecraft:xxx`.

### Mace slam

Trigger: falling (vertical velocity < -0.08, Xaero's convention) + sneak held (can be disabled) + a mace in the inventory +
**more than 1.5 blocks above ground / of fall distance** + **something below to hit**. Actions: lock armour → **switch to the mace** (**preferring the highest
density level**, then the most enchantments; switch slots if on the hotbar, otherwise send a container-click packet to move it to the current slot) → if the chest piece is an elytra, swap in armour → swing at the nearest entity
N ticks before predicted landing → unlock on landing → with no target, put the flight gear back on.

**Switch to the mace first, then take the elytra off** — that order is deliberate: the other way round, with no mace in the inventory, the elytra comes off first,
the player is pulled down and starts falling, the mace cannot be equipped, and the result is a pointless fall. Switching to the mace first means a failure leaves the player gliding.

The 1.5-block threshold fixes "a single jump also triggers it": a normal jump apex is only 1.25 blocks, and vanilla's mace bonus damage
also requires a fall distance > 1.5 blocks; below that the swing is a whiff that still locks armour and swaps the mace in and out for nothing.
The criterion is `max(height above ground, vanilla fall distance)`; right after take-off both are 1.25, so either one blocks it.

**Drop in place** (on by default, can be disabled on the page) means **cancelling the glide**, not pinning the player to a vertical line:
while gliding, the elytra is removed from the chest slot (vanilla cannot fly without a usable elytra, so the glide ends immediately),
the player falls from the current position and the same slam flow takes over. **Velocity is untouched**: horizontal glide momentum and WASD control are preserved,
so a misjudged landing can be corrected — glide speed reaches over 1 block/tick, hence aiming by "straight down" rather than by current velocity.

To act while gliding, three conditions must all hold: **sneak held**, **target inside the reach column**, **held long enough**.
"Long enough" has two tiers by view angle (`combat/GlideDive`, pure Java, self-testable):

| View | Hold time | Why |
| --- | --- | --- |
| Looking down ≥ 60° (staring at the landing spot) | 3 ticks | Intent is clear; a passing mouse sweep does not count |
| Level / looking up | 8 ticks (about 0.4 s) | While gliding, shift is vanilla's dive key, so holding it while flying past mobs is normal; but **not looking down must not be read as "no slam intended"** |

"Target inside the reach column" accepts no motion prediction: a passing mob or an approaching boat could make a "predicted hit" true,
and once the elytra is off there is no going back.

This previously required "looking down ≥ 60°", so holding shift while level never triggered — players who want to descend do not
first tilt their head. Now holding shift for 8 ticks while level also counts, while looking down acts after 3 ticks.

While the target drifts between the edges of the reach column the **counter only decays, never resets** (`GlideDive.decay`): horizontal glide speed is
0.4–0.7 blocks/tick, the column is one reach wide on each side, a single pass lasts only a dozen ticks, and resetting the accumulated count whenever the target slips out for a few ticks
would make 8 ticks unreachable — the second cause of "holding shift but still not descending". In the dive flow, a failed condition really is a failure and resets immediately.

While held but not yet due, the log prints a reading every 20 ticks, distinguishing whether it is stuck on view angle, column or hold time:

```
重锤猛击：滑翔中等下砸（目标 手长柱内 minecraft:wolf（4 tick 后落地）；俯仰 12°，需保持 8 tick，已保持 5，离地 6.3 格）
```

If the elytra cannot be removed (no other armour in the inventory) the flow gives up and unlocks instead of being stuck in "mace locked".
With the switch off, gliding is left completely alone. The log prints
`重锤猛击：摘下鞘翅，取消滑翔（目标 …）`.

"Something below" is decided in two stages (`combat/SlamTargets`, pure Java, self-testable outside the game):

1. **Reach column**: centred on the player, horizontal half-width = reach (3 blocks by default, vanilla interaction distance), extending from the feet
   down to the ground currently underfoot; any entity bounding box intersecting the column counts as a hit.
2. **Motion prediction**: within twice the reach (6 blocks), each entity's velocity is extrapolated to "how many ticks until you would land right now",
   and if the extrapolated position falls inside "landing point ± reach" it also counts — so a boat drifting in, a mob walking over,
   or an entity falling alongside you are all included.

Boats and armour stands count as entities (they are `isPickable()`); item entities, arrows and experience orbs do not.
Reach can be configured between 2 and 6 blocks, though the actual swing is still limited by vanilla's 3-block interaction distance.

Vanilla's slam requires the player to still be falling when attacking; a hit on the landing frame does not count, so the swing must be early. That lead is
**computed automatically from latency by default** (`combat/LeadEstimate`, pure Java, self-testable outside the game):

```
lead = clamp(ceil(ping / 100) + 1, 1, 8)     // in auto mode take max(manual value, the formula above)
```

| Round-trip latency | Lead | Scenario |
| --- | --- | --- |
| 0 ms | 1 tick (manual value 2 wins → 2) | Single-player, local |
| 50 ms | 2 ticks | Same-city server |
| **200 ms** | **3 ticks** | **Cross-province / overseas route** |
| 430 ms | 6 ticks | Satellite / extreme routes |
| ≥ 700 ms | 8 ticks (capped) | No further increase |

Derivation: round-trip `ping` ms = one-way `ping/2` ms = `ping/100` ticks, the attack packet needs one one-way trip,
rounded up, plus 1 tick of buffer (client prediction is discrete per tick, packets land on tick boundaries, and the server aligns to its own tick).
Without auto mode the manual value is used strictly; with auto mode the manual value is a **lower bound**, only ever raised, never lowered.

Two caveats:

- **More lead is not better**. It eats fall distance directly, and fall distance is the source of the slam damage multiplier, so the cap is 8 ticks.
  At 200 ms the 3 ticks cost roughly 2 ticks of fall height — far better than the whole slam not registering.
- **It cannot fix "out of range"**. At high latency the server sees the player behind their real position; if the swing is judged beyond reach,
  sending earlier does not help — that is what the reach setting (`maceSlamReach`) is for.

The tab-list latency refreshes only once per second, so readings jitter by tens of milliseconds; hence the 1/8 exponential smoothing.
The config screen's bottom-right permanently shows `延迟 200ms（自动 3，实际提前 3 tick）`, and the log carries
`提前 N tick，延迟 Mms`, so the two can be compared.

1.20.x has no mace, so the feature simply never triggers there.

**The mace is checked again right before the swing.** The swap at the start of the flow only covers the moment the dive begins; during the ticks before landing
the slot can still be changed by something else: as soon as the client judges "jump + holding space" as a take-off, tweakeroo's
"auto switch rockets" moves a rocket into the current slot — that swing then whiffs with a rocket, the slam does not register,
and a rocket is wasted. So validation starts 2 ticks before the swing window:

```
hand is not minecraft:mace  →  switch to the mace again (slot switch if on the hotbar, container click otherwise)
                            switched back: evaluate and swing in the same tick
                            not switched back: skip this tick, try the next one
                            robbed 3 times in a row: give up, restore the previous weapon on landing
```

The log line is `重锤猛击：手上是 minecraft:firework_rocket，重新切重锤（本 tick 已生效）`, and the swing line also carries the
`手上 …` of that moment, so the log shows what the hit was actually made with.

Finishing swaps the hand back to whatever was held before the flow started (`重锤猛击：把手换回 …`). The swap is a container click that takes a tick
to land, so if the landing frame fails it is retried every tick for the next 20 ticks; if the player changes to something else in the meantime it lets go at once.
When the mace cannot be equipped (not found in the inventory / rejected by the server) the flow gives up rather than swinging whatever is in hand.

"Changed by the player" and "robbed by a mod" look identical, so the flow sets a flag as soon as the hand is not the mace:
finishing with that flag set restores the previous weapon even if the hand currently holds fireworks (criterion `HandSwap.shouldRestore`,
pure Java, self-testable). This fixes "landing while unknowingly clutching a rocket".

### Launched after the slam: resume flight when there is no target

A full slam chain is "hold sneak → target below → switch to mace → swap armour to cancel the glide → hit on landing".
In real combat, however, **being launched right after the hit** is common — storms, explosions, knockback and mob launch abilities all throw the player up.
At that moment the hand holds a sword and the chest slot holds armour, so flying is impossible and the player can only watch themselves fall.

The flow therefore keeps a **40-tick (two-second) observation window** after finishing: whenever the three conditions below hold, the flight gear goes back on
(`combat/FlightResume`, pure Java, self-testable):

| Condition | Value | Why |
| --- | --- | --- |
| Player is airborne | `onGround == false` | Wearing an elytra while standing is pointless, and narrow-path protection should be in charge |
| ≥ 1.75 blocks above ground | normal jump apex 1.25 | separates "jumped myself" from "thrown by an external force" |
| No target **to slam** below | an entity **and** sneak held | target plus sneak = keep slamming; otherwise = wants to fly |

Putting the gear back on takes two steps (equipment changes go through container clicks and take a tick to land):

```
this tick    chest slot back to elytra   → log "重锤猛击：把鞘翅穿回去，恢复飞行"
next tick    hand to firework rocket     → log "重锤猛击：把烟花火箭换到手上，可以继续飞"
```

With no elytra in the inventory it does not force the issue: the window closes and the weapon is restored as usual — leaving the player hanging in mid-air with a mace in hand
is the worst outcome. If the player stays on the ground throughout the window (a clean landing after the hit) nothing happens and it ends after two seconds.

**The flow also finishes when the target disappears mid-way**: armour is locked, the elytra is off and the mace is in hand, but the target has been gone
for 3 consecutive ticks (filtering out strafing jitter). It then stops holding on and falling, and runs the recovery flow above instead.
That is the "no slam target → restore elytra and rockets" rule.

The config row is "resume flight when there is no target", on by default; turning it off reverts to "restore the weapon only".

## Quick dummy summon (Misc)

A one-click tool for trial chambers: summon a batch of Carpet dummies with random names, and remove them all in one click afterwards.

- Entry: config screen → **Misc** → the "summon count" and "dummy send interval" sliders, and on the bottom row
  (shared with "Reset all / Done") the "Summon dummies" and "Remove dummies (N)" buttons, with a red status hint to their right while a batch job runs.
- Both buttons live in the library's **bottom row** (`page.bottom`): widths computed by the library from the text,
  laid out left to right, sharing the row with the system buttons without covering them, and **not scrolling with the list**,
  so they stay clickable no matter which row is visible. The summon button no longer repeats the count —
  the count lives in the slider above it, and repeating it on the button reads as "one click gives this many".
- **While a batch job runs**: both buttons are disabled together (no new commands are queued, and no "summon then immediately remove"), and the red text
  on the right of the same row shows how many commands remain (`批量执行中，剩余 N 条命令`); both are restored once the queue drains and the record is written.
- **Count is adjustable from 1 to 50, default 20**. The slider is a vanilla widget: click anywhere on the track to jump there, press and drag
  to slide; the number box to its right accepts direct typing (the first keystroke replaces the value), Enter confirms and Esc cancels.
- **Send interval is adjustable from 1 to 100 ticks, default 10** (about 2 commands per second). This became configurable after being kicked
  by server-side anti-spam: 1 tick ≈ 20 commands/second sits right at vanilla `TickThrottler`'s token-bucket limit, and a slight tick offset between
  client and server drains it instantly; with stricter chat/command anti-spam installed, raise it, up to 100 ticks (one command every 5 seconds).
  Changes apply to the next command immediately, no restart needed.
- **Requires Carpet's `/player` command on the server** (Carpet installed, `commandPlayer` not disabled,
  the current player has permission). The check is whether the command tree sent by the server contains a `player` root node,
  not a guess at mod ids; if unavailable, only an explanatory line is shown and the buttons do not appear.
- **Commands are sent as packets only**: through the client connection's `sendCommand`, without opening chat, without writing chat history,
  and without leaving anything in the input box. Commands are queued at the configured interval instead of flooding dozens at once.
- Names are `bot_` plus 4 lowercase alphanumerics (8 characters total, within MC's 3–16 username limit),
  deduplicated within a batch and avoiding already recorded names.
- **Drop items before removing**: for each dummy, send `/player <name> drop` (dropping the whole inventory) and on the next tick
  `/player <name> kill` (removing the dummy), so equipment looted in the trial chamber does not vanish with the dummy.
- **Live count**: the N in "Remove dummies (N)" is not a local record but a count, every 0.5 seconds, of players whose names start with `bot_`
  in the server's online list (the tab list). Dummies killed, manually cleared or added by another client are reflected within half a second;
  while the config screen is open it keeps refreshing, with no need to leave and re-enter or reopen the screen.
- **Removal uses that same live list**: the number on the button and the number of commands actually sent always agree — no `kill` sent into thin air,
  and none of the previous round's dummies missed. Ownership is inferred purely from the name prefix (a Carpet dummy is just an ordinary player server-side,
  with no "who summoned it" marker), so **`bot_` dummies summoned by other clients are removed as well** — be careful on public servers.
- **Re-checked per command during execution**: the live online list is fetched again before each command, names that are no longer present are skipped
  without consuming the interval — so if dummies are killed or cleared mid-batch, a batch of 40 does not wait until the last second;
  if none remain it finishes immediately. An empty list (not yet synced, or already disconnected) leaves the queue untouched rather than guessing.
- Summon records are mirrored per **server address** to `.smile_toolb/dummies.json` in the user home directory: it serves as a fallback while the
  player list has not synced (just joined, just changed dimension), lets another client remove the previous round's dummies, and keeps servers separate.
  When the list is empty only the on-screen number is updated and the file is left alone, so records are never wiped; while a batch job runs nothing
  is written, avoiding a write that would race the queue's own final write.
- `drop` / `kill` are executed by Carpet; commands for dummies that no longer exist report errors but do not affect later entries.


## Exit key mapping (Misc)

Besides narration, the mod provides an accessibility feature: mapping any key (keyboard or mouse) to "press Esc".

- Entry: config screen → **Misc** → "exit key mapping" (the button shows the switch state and the key name, default
  "On · Mouse button 4").
- By default only the **rear side mouse button** is bound, enabled out of the box. In `key.mouse.N`, N is "the Nth mouse button":
  the rear side button (back / XBUTTON1, GLFW button 3) is mouse button 4, the front side button (forward / XBUTTON2,
  GLFW button 4) is mouse button 5. Some vendors swap the two physical side buttons; in that case use "Add key" and press the real
  side button once to confirm its number, then remove the unneeded one.
- The list can be edited: click "Add key" and press the target key (keyboard keys and mouse side / middle / right buttons
  all work; the left button is reserved for clicking the UI and will report that it cannot be bound), Esc cancels recording, and "Remove" at the row's end deletes.
  Every change is written to `tts.json` immediately, no need to return to the config page to save.
- Once enabled, pressing a listed key on any screen is equivalent to pressing Esc to close it; where the screen itself does not allow closing with Esc
  (for example the connecting screen), the custom key does not work either, exactly matching Esc's behaviour.
- Implementation: keyboard input is injected at `Screen#keyPressed` and mouse input at `MouseHandler#onPress`, both at HEAD, reusing
  `shouldCloseOnEsc()` as the criterion. Only open screens are affected, so in-game keys are never swallowed; the recording screen itself skips injection,
  otherwise a just-recorded side button would immediately close the settings screen. If a common character key is mapped, it will also close the screen
  while focused in a text field, so avoid letters and digits.
  (Mouse cannot be injected into `Screen#mouseClicked`: on 1.20.1 that is only an interface default method of `GuiEventListener`,
  `Screen` does not override it, and injecting would crash at startup because the target cannot be found.)
- The legacy `exitKeyCodes` (GLFW key codes) in old configs are migrated to key names on read and are no longer written back.

Configuration and cache files live in `.minecraft/config/smile_toolb/`:

- `tts.json`: mod configuration
- `voices.json`: voice list cache (local + online)
- `cache/edge_*.mp3`: temporary audio from online synthesis, keeping the most recent few and cleaning up automatically
- `narrator_helper.ps1`: helper script, regenerated by the mod on every start (do not edit — it will be overwritten)

## Debugging

The helper script can run standalone to confirm system speech availability (it lists each engine's voices and speaks one sentence with each):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .minecraft\config\smile_toolb\narrator_helper.ps1 -SelfTest
```

Log clues for the screen (`latest.log`):

- `配置界面：注册表 N 个音色，可见 M 个，引擎就绪=...`
- `打开音色列表：可见 N 个（本地 X，在线 Y），列表区域 x[a,b] y[c,d]` (useful for confirming the list area did not compute a negative height)
- `音色切换为 <id>` (written on selection)

## Voice sources and limits

| Voice | Source | Available |
| --- | --- | --- |
| `Microsoft Huihui / Yaoyao / Kangkang` | OneCore voice store (`HKLM\SOFTWARE\Microsoft\Speech_OneCore\Voices`) | Yes (WinRT engine) |
| `Microsoft Huihui Desktop / Zira Desktop` | SAPI5 voice store (`HKLM\SOFTWARE\Microsoft\Speech\Voices`) | Yes (SAPI engine) |
| `Microsoft Xiaoxiao (Natural HD)` and similar | MSIX voice packages `MicrosoftWindows.Voice.*` | **Not available locally**, see below; the same neural voices are available online |
| `Microsoft Xiaoxiao / Yunxi / Xiaoyi …` (online) | Edge read-aloud service | Yes (with network) |

**About Natural HD**: these voices are installed as MSIX packages with "data only, no engine"
(the package contains just `hd_am_*` / `hd_device_vocoder_*` model files and `Tokens.xml`), and their neural renderer
(`NeuralRenderAdapterImpl`) is embedded in `Narrator.exe`; the corresponding CLSID `{a12bdfa1-...}`
**is not registered in system COM**, and the tokens are not in any enumerable voice store. In other words it is a narrator-private channel that
no third-party process can reach — not a matter of picking the right interface, but a deliberately closed one. The mod detects such voices
and mentions them in the config screen, but does not list them as selectable (so you never pick one and get silence).

> **Online voices are the cloud versions of the same neural voices**: `zh-CN-XiaoxiaoNeural` has the same acoustic origin as the narrator's Xiaoxiao,
> it simply must be synthesised in the cloud. For fully offline operation only the five OneCore / SAPI5 voices are available.
>
> If a third-party tool (such as NaturalVoiceSAPIAdapter) later "re-registers" narrator voices as SAPI voices,
> this mod's SAPI engine will list them automatically — no code change needed. That tool is an unofficial hack
> (it reads keys from system files, may be flagged by antivirus, and may break with Windows updates); evaluate the risk yourself.

## Known limitations

- Requires Windows 10 or later; other platforms fall back to the vanilla narrator automatically.
- Every utterance is synthesised locally first, so there is slightly more latency than vanilla's direct SAPI read (typically a few hundred milliseconds).
- The SAPI5 engine maps volume / rate to SAPI's `Volume` (0–100) and `Rate` (-10–10 logarithmic steps); this is not point-for-point equivalent to OneCore's percentages, though the direction matches.
- Online synthesis needs a network connection: the first handshake takes about a second, after which the connection is reused at roughly 0.2–0.4 s per utterance.
- Online voices rely on Edge's non-public interface (including the `Sec-MS-GEC` token) and may stop working entirely if Microsoft changes it;
  in that case the mod logs it and falls back to local voices automatically, never to silence.
- Online voice rate uses SSML `prosody rate` and volume is adjusted on the playback side; neither is exactly equivalent to the local engines.

## Config screen: how presets and rendering divide the work

The screen itself lives in the library (`smile_libbox.api.ui.ConfigScreen`); this mod only provides a data description,
`smile_toolb.client.config.SmileToolbConfig`, and Mod Menu only "signs up" — not even a `Screen` is written
(the factory is generated with `java.lang.reflect.Proxy`, see the "single jar" section):

```java
// ModMenuIntegration
@Override
public ConfigScreenFactory<?> getModConfigScreenFactory() {
    return FACTORY;   // an InvocationHandler: create(parent) → Ui.config(parent, spec)
}
```

`SmileToolbConfig` is entirely declarations like these:

```java
page.toggle("enabled", Ui.tr("smile_toolb.config.enabled"), null,
        config.enabled, true, bool((c, v) -> c.enabled = v));
page.slider("rate", Ui.tr("smile_toolb.config.rate"), null,
        config.rate, 50, 200, 100, integer((c, v) -> c.rate = v));
page.footer(() -> Ui.tr("smile_toolb.gui.button.select_voice", currentVoiceName()),
        () -> true, SmileToolbConfig::openVoiceList);
```

The division is strict:

| Who | Responsible for |
| --- | --- |
| This mod | Which pages exist, which rows each page has, ranges and defaults, how changes reach `tts.json`, bottom action buttons |
| Prerequisite library | Tab bar, row height and scroll snapping, label-column / control-column alignment, reset keys, scrollbar, vanilla button / slider / text-field instances, input dispatch |

So "changing how the screen looks" touches one place in the library and every dependent mod changes with it; on this side, only the
**content** of the config rows follows the game version (for example the tracker-bar switches that only appear on 1.21.9+).

Buttons, sliders, text fields and the scrollbar thumb are **real vanilla widgets** created by `UiHost`
(`Button` / `AbstractSliderButton` / `EditBox`); textures, hover highlight, disabled state and narration are all handled by the game — which is the only way
to be genuinely consistent with vanilla assets.

Slider rows carry a small extra button on the right (vanilla button texture plus a placeholder glyph `T` / `≡`): clicking it turns the slider into a number field.
Typing **validates on every keystroke**, and errors produce a single red tooltip line from one of two templates:

- content the regex rejects → `不合法的字符串！仅支持{regex name}` (the default name is "整数";
  `.regexFile("txt")` turns it into ".txt格式文件", or give your own name such as "本地文件夹");
- a number outside `min~max` → `不合法的数字！仅支持{min}~{max}之间的数据`.

The tooltip **appears only while the pointer is over the field** and not when the content is valid, so it never covers what is being typed. Pressing Enter applies
a valid value; `Esc` discards only this edit without closing the screen; **clicking elsewhere merely removes focus and leaves the field as it is** —
only Enter, clicking the toggle again (or `Esc`) ends edit mode. Invalid content is never written back: closing the screen or switching
back to the slider discards it. The icon is a placeholder for now; replacing it with a texture only touches the button creation in the library.

Rows can declare dependencies: a boolean row acts as a **prerequisite**, and other rows call `requires(prerequisiteRow)` under it
(in JSON, `"requires": "<key of the prerequisite>"`). While the prerequisite is off the dependent row is disabled as a whole — the control in vanilla's
grey disabled state, dimmed label, inert reset key, and an editing text field leaves edit mode; hovering shows the row's description as the
first tooltip line (default colour) and a **red last line** "要启用此项，你需要开启『xxx』".
The linkages this mod uses: system speech → rate / volume / per-utterance character limit / auto-enable narrator on join;
keep experience bar → advanced status bar control; keep chat contents → draft retention; enable exit key → exit key list.

All hint text on the screen uses **vanilla tooltips** (the library's `UiHost#tooltip` / `UiHost#tooltipLines` →
vanilla `renderComponentTooltip` / `setComponentTooltipForNextFrame`), so it is a vanilla dark box that follows the mouse, wraps automatically and flips
to stay on screen, instead of hand-drawn colour blocks. There are two triggers:

- **Row description**: with the pointer over a row's **label area**, the `tooltip` from that row's definition is shown;
- **Numeric input**: when the content is invalid and the pointer is over the field, the whole red line is a templated hint
  (`不合法的字符串！仅支持…` or `不合法的数字！仅支持 a~b 之间的数据`);
  when valid, only the toggle button offers a range hint.

Two rows are **text-field only** (no slider, no `T`/`≡` toggle): "per-utterance character limit" and "draft retention",
built with `page.number(...)`. The whole control column belongs to the field, and typing validates live without writing the config;
the value is applied on Enter or when the screen closes, and invalid content is discarded. Those two ranges are wide enough that dragging a slider
was never precise.

### Two definition routes

The same `ConfigSpec` has two entry points and renders identically:

- **Declarative code** (what this mod uses): the `page.toggle(...)` / `page.slider(...)` calls above, which can use lambdas directly, compute button text dynamically,
  and add rows based on runtime capabilities;
- **JSON** (`smile_libbox.api.ui.ConfigLayout`): suited to mods with a fixed screen; pages and rows live in a resource file,
  and values map to the mod's own config file through the `ConfigValues` interface.

```java
ConfigSpec spec = ConfigLayout.load("mymod/config_gui.json", parent, myValues, actions);
```

```json
{
  "title": "mymod.gui.title",
  "pages": [{
    "title": "mymod.gui.tab.general",
    "rows": [
      { "type": "toggle", "key": "enabled", "label": "mymod.config.enabled", "default": true },
      { "type": "slider", "key": "rate", "label": "mymod.config.rate", "min": 50, "max": 200, "default": 100 },
      { "type": "text", "label": "mymod.gui.hint" },
      { "type": "action", "label": "mymod.gui.voice", "action": "select_voice" }
    ]
  }]
}
```

## Config screen layout formulas

The layout parameters in the library follow the proportions of masa-style screens; check this table before changing them:

| Quantity | Value | Rationale |
| --- | --- | --- |
| Label column width | the widest label across all pages | Computing it per page makes the two pages different widths and the whole table jumps when switching tabs |
| Control width cap | **206** | Beyond that the row is stretched too far and sliders become thin and long on wide screens |
| Control width floor | 120 | Narrower and sliders cannot be dragged, and toggle text no longer fits |
| Right margin | `24 × 3 / GUI scale`, clamped to 8–40, and never below `4+8+6` | Logical pixels vary with the GUI scale; a fixed value collapses onto the scrollbar at large scales |
| List right inset | 4 | Keeps the scrollbar closer to the right edge; the RESET right boundary is fixed at `width − margin` with ≥ 6 px to the scrollbar |
| **Visible rows** | `min(ceil(viewport height / 22), floor(viewport height / 20))` | Rounding up shows one more row than the old floor; it also guarantees at least 20 px for the control in every row |
| **Actual row height** | `viewport height / visible rows`, padding `(row height − 20) / 2` | The row height divides the viewport exactly, so the last row is never clipped; both pages share one row height |
| **Scroll step** | whole rows | `scroll` stores "rows scrolled" rather than pixels; the wheel moves 2 rows, dragging / clicking the scrollbar snaps to rows too |
| **Scrollbar** | dark track (background) + a thumb made from a vanilla button (foreground) | Two layers, as in masa-style screens; using a real widget avoids the wildly differing `blitSprite` signatures across versions |

The layout order is fixed as "label column + 10 + control + 2 + RESET". Slider rows reserve another 18 px in the control area for the toggle button.
Horizontal space left over after the control cap is absorbed by the label column, so the row still ends at the right anchor and no hole appears between control and RESET.

Example: at 427×240 with GUI scale 3 the viewport is 148 px → 7 rows × 21 px = 147 px, filling it exactly;
the old `floor(148/22)=6` also left half of a seventh row visible, which is now shown in full.

Both pages share the same label column, control width, row height and visible rows, so switching tabs only changes row content — column boundaries and the scrollbar stay put.

## Single jar and Stonecutter (regression check)

**There is exactly one published artifact**, `build/libs/smile_toolb-1.0.36.jar`, with `depends.minecraft` set to
`>=1.20.1`, and the same bytecode is used from 1.20.1 to 26.2. This is possible because the mod side has **zero
`net/minecraft` references**:

| Where MC types would normally appear | How they are avoided now |
| --- | --- |
| Config screen | Produces only a `ConfigSpec`; rendering is the library's `ConfigScreen` (compiled per version inside the library) |
| Narrator | `SystemNarrator extends VersionedNarrator`, implementing the library-defined `onSay/clear/active/destroy` hooks; the interface adaptation lives in the library |
| Input events / status bar / chat draft | All through the library's mixins and the `Ui` facade |
| Mod Menu entry | The `Screen` in `ConfigScreenFactory.create(Screen)` is absorbed by `java.lang.reflect.Proxy` (see the comment in `ModMenuIntegration`) |
| Two mixins | Targets are `com.mojang.text2speech.Narrator` and the third-party `LocatorBarRenderer`, neither of which is `net.minecraft.*`, so names are stable across versions |

Stonecutter keeps **two nodes**, whose artifacts are **not published** but serve as a **two-sided regression check**:
anyone slipping an MC reference into the sources makes that node fail to compile.

| Node | Role | Compilation target | Mod Menu (compile-time only) |
| --- | --- | --- | --- |
| 1.20.1 | Baseline, artifact taken from here | Java 17 (class major 61) | 7.2.2 |
| 1.21.11 | Latest intermediary, the other side of the squeeze | Java 21 | 17.0.1 |

The `gradle.properties` of the six intermediate versions (1.20.4 / 1.20.6 / 1.21.1 / 1.21.4 / 1.21.5 / 1.21.8) are archived in
`archived-versions/`. To bring them back, copy them to `versions/<version>/` and add them to the `versions` line in
`settings.gradle` — the bytecode they produced was byte-identical to this jar, so removing them does not affect universality.

```bash
./gradlew build               # two nodes (regression check)
python tools/build_release.py # collapse into build/libs/smile_toolb-<version>.jar
./gradlew :1.20.1:runClient   # launch the development client
```

`build_release.py` uses the **1.20.1 node** as the baseline (Java 17, class major 61, loadable on 17/21/25 runtimes) and does two things:

1. Strips the class-file version from the 1.21.11 node's classes and compares byte for byte — a single differing class means some code
   forks by version, breaking the "one jar" premise, and the script fails immediately;
2. Verifies the artifact has zero `net/minecraft` references, a `minecraft` range of `>=1.20.1`, and no `malilib` in `depends`.

In `fabric.mod.json`, `minecraft` / `java` are now fixed values (`>=1.20.1` / `>=17`) rather than per-node substitutions;
each node's MC / Fabric API / Mod Menu / library coordinates remain in `versions/<version>/gradle.properties`.

**Mod Menu is an optional dependency**: pulled in with `modCompileOnly` + `modLocalRuntime`, declared under
`suggests` rather than `depends`, so the Loader does not resolve that entrypoint when it is absent.

### Why 26.2 runs on a single jar

From 26.x on Mojang no longer ships intermediary; the game jar contains official names such as `net.minecraft.client.*` directly.
A class compiled against 1.20.1 has `net/minecraft/class_437` in its constant pool, and the two cannot be mixed.

After the rework the mod side has not a single `net.minecraft` descriptor (`build_release.py` verifies this every time),
so the same classes work on both the intermediary and named generations; the code that genuinely forks by namespace
(`VersionedScreen` / `UiBackend` / `VersionedNarrator` / the three injection points) all lives in the prerequisite library,
which produces `smile_libbox-1.0.26+26.2.jar` separately via `tools/build_named26.py`.

Put differently: **version forking is concentrated in the library, and consumers are back to one jar** — which is exactly the end state
the config-screen-preset rework was aiming for.

### Preset definition notes

| Location | Handling | Why |
| --- | --- | --- |
| `SmileToolbConfig.miscPage()` | The two tracker-bar switches are added only if `BarFeatureDetector.get().present()` | 1.20.x has no tracker bar, and showing them would look broken; a runtime probe rather than a compile-time version condition, because VanillaBackport changes the capability |
| `page.footer(...)` | Takes a `Supplier<String>` instead of a string | Voice names and dummy counts are only known at runtime; the library re-reads the label every frame, so the dummy count follows the online list live |
| `commit()` | Every change immediately calls `SmileConfig.save()` + `TtsEngine.applyConfig()` | Same cadence as the old `onConfigsChanged`: the effect is audible at once, no "Done" needed |
| `ExitKeyScreen` | Implements `UiScreen` itself | Exit keys are a list of key names rather than a single value, so it needs its own list screen |
| Titles and labels | All `Ui.tr(...)`, translated only when the spec is built | The screen may be constructed when Mod Menu fetches the factory, before resource packs load; translating earlier would freeze key names |

### Version adaptation points (now handled by the library)

| Boundary | Change | Where it is handled |
| --- | --- | --- |
| 1.20.2 | `mouseScrolled` gains a horizontal component, `renderBackground` gains window arguments | `VersionedScreen` |
| 1.21 | `new ResourceLocation(..)` deprecated | This mod removed the unused `SmileSToolb.id()` |
| 1.21.5 | `Narrator.say` gains a volume argument | `VersionedNarrator` (compiled per version) |
| 1.21.9 | Keyboard / mouse callbacks become event objects, window handle renamed, key categories become objects | `LibScreenKeyMixin` / `LibMouseMixin` / `VersionedScreen` / `McCalls.keyMapping` |
| 1.21.9 | The strip above the hotbar becomes the contextual bar (experience / locator / jump bar) | `LibContextualBarMixin` + this mod's `ContextualBarController` (status bar control) |
| 1.20.1 + VanillaBackport | Overlay-style locator bar, `LocatorBarRenderer#shouldSkipRendering()` as the master gate | This mod's `VanillaBackportBarMixin` (soft target + separate optional config) + `BackportBridge` (reflection) |

## Prerequisite library Smile's Lib Box

This mod moved its cross-version compatibility layer into a separate prerequisite mod, **`Smile's Lib Box`** (`smile_libbox`).
It publishes **nine jars per game version family** (eight Stonecutter/Loom nodes + one 26.2 official-name jar,
plus a `+generic.jar` fallback): nothing under `smile_libbox.api` / `api.ui` references a Minecraft class,
so version checks and UI contracts are common to all versions; the adapter classes under `smile_libbox.client.compat` / `mixin`
are compiled together with that version's MC and the matching jar supplies the correct signatures (the intermediary generation also carries a refmap).

- Runtime version checks are in `smile_libbox.api.GameVersion`; game / data pack / resource pack versions come from
  `MinecraftVersions` through a fixed API (reading the client jar's `version.json` and normalising the two
  `pack_version` shapes around 1.21.9 into major + minor).
- Adapters: `VersionedScreen`, `UiBackend`, `McCalls`, `VersionedNarrator`, plus
  `LibScreenKeyMixin` / `LibMouseMixin` / `LibContextualBarMixin`. The 26.2 adapters live in
  `named26/src` and are compiled with official names by `tools/build_named26.py`.
- This mod depends only on the `smile_libbox.api.ui.Ui` facade, the `ConfigSpec` / `ConfigScreen` screen presets, and
  the two zero-MC-reference event buses `InputEvents` / `BarEvents`. All of that is common to every version, so the TTS engine,
  the audio child process, config I/O, dummies, status bar control and the config screen are written once.
- Dependency: `modImplementation "smile_libbox:smile_libbox:1.0.26+1.20.1"` (run
  `./gradlew build publishToMavenLocal` in the library first), and declare
  `"depends": { "smile_libbox": "*" }` in `fabric.mod.json`. At runtime put **the library jar matching the target game version**
  into `mods/`; version jars declare `minecraft: ~<version>`, so a mismatch is rejected by the Loader.
- **26.2**: the library's `+26.2.jar` ships four client mixins with official-name descriptors (custom exit key,
  contextual-bar takeover, screen / global mouse input, chat draft); this mod's 26.2 build is produced by
  `tools/build_named26.py`, as described in the previous section.

### Detecting versions (for other mod authors)

Mods depending on the library have four kinds of criteria to choose from:

| What you want to know | Which API |
| --- | --- |
| Game version range | `GameVersion.atLeast("1.21.9")` / `Conditional.matches(">=1.21.9")` |
| Data pack / resource pack format version | `MinecraftVersions.dataPackAtLeast(15, 0)`, `resourcePackAtLeast(75, 0)`, `Conditional.matches("datapack:>=15.0")` |
| Protocol / world / Java version | `MinecraftVersions.protocol()`, `world()`, `java()`, `summary()` |
| ABI capability / mod / class presence | fixed booleans such as `Compat.eventBasedInput()`, `cap:`, `mod:`, `class:` expressions |

Then split the version-bound implementation into separate classes and wire them by string class name:

```java
BarHooks hooks = Conditional.choose(BarHooks.class,
        ">=1.21.9 => mymod.compat.v1219.ModernBarHooks",
        "*        => mymod.compat.legacy.LegacyBarHooks");
```

String class names produce no symbolic references, and classes whose condition never holds are never loaded, so all implementations can ship
in the same jar. On the mixin side, extend `smile_libbox.api.VersionedMixinPlugin` and write your own gating rules.

**But "split classes + lazy loading" only holds within one namespace generation.** From 26.x on Mojang uses
official class names (`net/minecraft/client/KeyMapping`), the launch log shows `Mappings not present!`,
while a class compiled against 1.20.1 has intermediary names such as `net/minecraft/class_304` in its constant pool.
Class names are baked in at compile time; lazy loading and reflection cannot change that.

This mod takes **another route**: all MC types are isolated into the library's per-generation adapter layer,
and the main jar depends only on the pure-Java `smile_libbox.api.ui` facade with zero `net/minecraft` references in its constant pool,
so a single jar enters both 1.20.1 and 26.2. Halfway through the rework, using `+generic.jar` crashed at
`McCalls.keyMapping` → `NoClassDefFoundError: net/minecraft/class_304`, which is exactly what an incomplete isolation of that layer looks like.

**The route re-opened once the config screen became a preset**: the screen no longer extends `Screen`, and apart from the factory method in
`ModMenuIntegration` that takes a `Screen` parameter, the main jar's constant pool has no `net/minecraft` descriptor at all. Per-version jars remain necessary (mixins and version gating must be compiled against concrete signatures),
but the jars differ only in a handful of classes — which is why the 26.2 package can reuse everything from the 1.20.1 build.
For the full conclusion see section 0 of [`VERIFY.md`](VERIFY.md) and
[`Smile's Lib Box/README.md`](../Smile's%20Lib%20Box/README.md).

## Building

Build and publish the prerequisite library first, then build this mod (Gradle resolves the dependency coordinate during configuration):

```bash
cd "../Smile's Lib Box" && ./gradlew build publishToMavenLocal
cd "../Smile's toolb" && ./gradlew build          # two nodes (regression check)
cd "../Smile's toolb" && python tools/build_release.py   # collapse into one jar
```

A local JDK 21+ is enough to build this mod (the two nodes compile for 17 / 21 respectively);
only the library's 26.2 package additionally needs JDK 26's `javac`. Mod Menu is fetched by Loom from the
Modrinth / Terraformers repositories, so nothing needs preparing by hand.
To run a single version use `./gradlew :1.20.1:runClient`.

When you need to check the screen on a real client, add the self-test flag so the development client opens the config screen once at startup and read the
`配置界面自检` line from the log (tab count / rows per page / whether the tracker-bar switches are shown):

```bash
./gradlew :1.20.1:runClient -Dsmile_toolb.selftest.open_config=true
```

In a real modpack use the JVM argument `-Dsmile_toolb.selftest.open_config=true` or the environment variable
`SMILE_TOOLB_SELFTEST_OPEN_CONFIG=1`; it is off by default, so normal play never pops the screen.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
