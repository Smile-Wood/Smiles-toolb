# Per-version verification checklist

[中文文档](VERIFY.zh-CN.md)

This checklist is executed manually on a real client before a release. Section 0 of the Chinese edition
([`VERIFY.zh-CN.md`](VERIFY.zh-CN.md)) holds the full auto-verification changelog — several hundred lines recording
what each build verified, the commands used and the artifacts produced. It is kept in Chinese as the historical record.

Current build under test: **1.0.36** (library **1.0.26**; single jar for 1.20.1 – 26.2).

## 1. Startup and injection

- [ ] The game reaches the main menu (no `Mixin apply for mod smile_toolb failed`)
- [ ] The log contains `Smile's toolb 已加载` and `讲述人已切换到系统语音引擎`
- [ ] The log contains `系统语音讲述人已初始化（启用=…，在线音色=…）`

## 2. Config screen

- [ ] `F8` opens **Smile's toolb 配置**; the title must not read `smile_toolb.gui.title.configs`
- [ ] Both tabs — **Narrator** / **Misc** — are present with the current one greyed out; clicking the other switches without errors
- [ ] Every control uses **vanilla assets**: toggles are vanilla buttons, sliders are vanilla sliders (brighten on hover),
      and the reset key on the right is the vanilla `重置` button; no flat colour-block controls should remain anywhere
- [ ] The scrollbar on the far right has **two layers**: a dark track (background) plus a thumb using the vanilla button texture (foreground);
      fully visible, not covered by controls or `重置`; test at 427×240 and at fullscreen
- [ ] At 427×240 / GUI scale 3 the 148 px viewport shows **7 complete rows** (the old build showed 6 plus a sliver),
      leaving at most 1 px below the last row, with no half-drawn control or half-drawn label
- [ ] One wheel notch moves 2 whole rows; dragging the thumb or clicking the track snaps to rows, and every row is complete at rest
- [ ] Switching between the two tabs leaves row height, column boundaries and scrollbar length unchanged
- [ ] Resizing the window, toggling fullscreen and switching tabs several times keeps row widths correct and unshifted
- [ ] Dragging the speech-rate slider changes `config/smile_toolb/tts.json` immediately, and the next utterance uses the new rate
- [ ] Clicking the small button to the right of a slider (placeholder glyph `T`) turns the slider into a number field and focuses it;
      typing `137` and pressing Enter sets the value to 137, restores the slider and updates `tts.json`; clicking again shows `≡`
- [ ] `Esc` in the number field discards only the input and **does not close the screen**
- [ ] **Clicking elsewhere does not revert to the slider**: clicking blank space outside the tabs, another row, or the scrollbar keeps the field
      and its typed content (focus only is lost); clicking back allows continued editing. Only Enter, clicking `≡` again or `Esc` ends edit mode
- [ ] Live validation: typing `abc` turns the text **red** immediately; the red tooltip appears **only with the pointer over the field**
      and is a single line `不合法的字符串！仅支持整数`; typing `9999` gives red text plus
      `不合法的数字！仅支持1~50之间的数据`; clearing gives `不合法的字符串！仅支持整数`.
      **No tooltip when valid** (it would cover the input); moving the pointer away hides the tooltip while the red text stays
- [ ] **Prerequisite linkage**: turning off "keep experience bar" greys out the whole "advanced status bar control" row and makes it inert; hovering it
      shows the row's description first and the red line `要启用此项，你需要开启『保留经验条』` last; re-enabling the prerequisite restores it at once.
      Same for: disabling "enable system speech" → rate / volume / per-utterance character limit / auto-enable narrator on join are all disabled;
      disabling "keep chat box contents" → draft retention disabled; disabling "enable exit key" → the exit key list button disabled
- [ ] Pressing Enter on an invalid value does not write the config and stays in the field; clicking `≡` or closing the screen then discards the input
- [ ] Pressing Enter on a valid value (e.g. `7`) clears the red text, writes the value, restores the slider and updates `tts.json`
- [ ] **Text-field-only rows**: "per-utterance character limit" (Narrator page, 0–1000) and "draft retention"
      (Misc page, 60–3600) have **no slider and no `T`/`≡` on the right**; the whole control column is the field,
      followed directly by `重置`. These rows exist for precise seconds / character counts
- [ ] Typing a valid number (e.g. `450`) in those rows and pressing Enter writes it to `tts.json`; pressing "Done" or closing with Esc
      also writes it (closing the screen is a save point)
- [ ] Typing an out-of-range value (e.g. `5000`) in those rows turns it red at once and does not write; closing the screen from there discards
      the input, restores the previous value and leaves `tts.json` unchanged
- [ ] **Tooltips are vanilla**: dark background with the vanilla border texture (not a hand-drawn purple edge), following the mouse,
      flipping to the other side near the right / bottom edge, and wrapping long text
- [ ] **Row descriptions**: with the pointer over any row's **label area** (not the slider, not the reset key) that row's description appears;
      for example "keep chat box contents", "summon count" and "dummy send interval" each have their own text;
      moving to another row swaps it; moving over the control area shows nothing; moving far from the label area hides it
- [ ] The `重置` key is grey while the value equals the default and becomes clickable after a change; clicking restores the row's default
- [ ] "Reset all" at the bottom restores every row and persists it
- [ ] The "Voice: <current voice>" button shows the current voice name; clicking opens a separate list, not a long dropdown

## 3. Voice picker screen (important on 1.21.9+)

- [ ] The list renders and scrolls with the wheel
- [ ] Clicking a row switches, previews and updates the button text immediately
- [ ] The search box accepts typed text (on 1.21.9+ input events go through the forwarding shell; this is where problems show up first)
- [ ] `↑` / `↓` / Enter work, `Esc` returns

## 4. Exit key mapping (Misc)

- [ ] "exit key mapping" is on by default; after disabling it the side button no longer closes screens
- [ ] On any screen, pressing the rear side mouse button equals pressing Esc (closes the current screen)
- [ ] Pressing the side button while recording does not close the settings screen itself
- [ ] **On 1.20.1 / 1.20.4 clicking the edit button next to the exit key list does not crash** (in the MaLiLib era recursive text input
      needed two mixins to avoid a crash; the screen is now rendered by the library with vanilla input widgets, so this is a regression item)
- [ ] The button on the exit key row reflects the entry count (`编辑（N 个）`), and clicking it opens a separate key list screen
- [ ] Editing `exitKeys` in `tts.json` by hand and restarting has the same effect as editing in the screen

## 5. Status bar control (1.21.9+ / VanillaBackport, Misc)

### General

- [ ] **Sitting on an interactive object does not crash** (key regression): sit for at least 3 seconds on a **furniture chair**
      (mcwfurnitures), a **boat** and a **minecart**. All three are "has a vehicle but it is not jumpable", so
      `LocalPlayer.jumpableVehicle()` is `null`; the old implementation made 26.2's `JumpableVehicleBar`
      crash at `requireNonNull`
- [ ] While sitting on a chair / boat / minecart the strip above the hotbar shows the **experience bar** (or the locator bar), **not** an empty jump charge bar
- [ ] In the same situation, holding and releasing Tab switches to the locator bar and back without crashing
- [ ] Riding a real **horse / camel** and pressing space shows the charge bar as usual, returning to the experience bar about 2.5 s after release
      (confirming the crash fix did not break the normal path)
- [ ] **Misc** shows "use legacy experience bar behaviour" (on) and "advanced status bar control" (off);
      **on 1.21.8 and below without a locator-bar backport mod, neither is shown**
- [ ] On an older version with another locator-style mod (name containing locator / waypoint, or Wayfarer) both switches still appear,
      with an extra line "only takes over vanilla and VanillaBackport"; the log contains `检测到外部定位条特性`
- [ ] On an older version with an unrelated mod whose name contains locator: the switches appearing is expected (heuristic detection) and affects nothing else
- [ ] Disabling the first switch and saving returns fully to vanilla / the backport's own behaviour
- [ ] Spectator mode never draws an empty experience bar

### 1.21.9+ (vanilla contextual bar)

- [ ] With only the first switch on: the strip stays the experience bar near teammates with waypoints; the vanilla jump bar still appears while riding
- [ ] With both on: the default is the experience bar; holding Tab switches to the locator bar and releasing returns
- [ ] With both on: riding a horse / camel and holding space shows the charge bar, and releasing returns to the experience bar after about 2.5 s
- [ ] With both on: riding without pressing space shows the experience bar, not a permanently attached charge bar
- [ ] With both on: holding Tab on a server with no teammate waypoints does not switch to the locator bar

### 1.20.1 + VanillaBackport 1.2.2 (specific adaptation)

- [ ] The log contains `检测到 VanillaBackport 定位条` and `VanillaBackport 定位条接口解析成功`
- [ ] Below the switches under **Misc** the line `已适配 VanillaBackport 1.2.2：两个开关可直接控制定位条。` is shown
- [ ] With only the first switch on: near waypoints the experience bar background and level number remain fully visible, and neither the locator-bar background nor the waypoint icons appear
- [ ] With both on: the default is the experience bar; holding Tab shows the locator-bar background plus waypoint icons together, and releasing restores the experience bar at once
- [ ] With both on: pressing Tab with no waypoints does not produce an empty locator-bar background
- [ ] If the backport's `locator_display_info_bar` is on, a hint appears on the screen; after turning it off, Tab shows the full locator bar
- [ ] Riding without jumping shows the (empty) jump bar — a 1.20.1 vanilla limitation, not a regression; holding jump does not let the locator bar cover the charge bar
- [ ] With `F1` hiding the HUD, no locator bar is drawn

## 6. Speech

- [ ] After enabling narration with `Ctrl + B`, chat / screen text is spoken in a system voice (not vanilla SAPI's robotic voice)
- [ ] `F9` previews the current voice
- [ ] Switching to an online (Edge) voice produces audio; after disconnecting from the network it falls back to a local voice rather than going silent

## 7. Keeping chat box contents (Misc)

Configuration: **Misc → keep chat box contents** (on by default), **draft retention** (600 s by default).
The draft file is `user home/.smile_toolb/chat-drafts.json`; different client instances (different game directories /
different launchers) read the same file — that is how "log in again from another client" works. The key is the connection address the player entered
(case-insensitive; with or without the default `:25565` port counts as the same server). The file is deleted automatically once drafts are empty.
With two clients online, every read and write re-reads and merges from disk, but concurrent edits to the same address are still not recommended.
**Boundaries**: text is persisted only in `ChatScreen#removed()` (screen closed / disconnected / kicked) and on message submission;
force-killing the process skips `removed()`, so the last edit is lost. If two clients run under different system accounts,
`user.home` differs and they cannot read the same file.

**Sending does not persist**: vanilla `handleChatInput` only sends the packet and does not clear the `EditBox`; `setScreen(null)` follows,
so `removed()` still reads the line just sent — an early implementation therefore stored sent content as a draft
(Enter to send → reopen → content still there). The library now records the submitted text and skips persisting when the content is unchanged;
1.21.x vanilla uses its own `exitReason == DONE` for the same decision, and 1.20.x has no such field.

- [ ] Join a server, press `T`, type something **unsent**, close with Esc, press `T` again — the content is still there
- [ ] Leave the server in that state, rejoin the same address within 10 minutes, press `T` — the content is still there
- [ ] Log in from another client / launcher with the same account, join the same address, press `T` — the content is still there
- [ ] Set retention to 60 seconds, wait past it, rejoin — the draft should be gone
- [ ] After sending a message with Enter, reopen chat — the box is empty (nothing duplicated)
- [ ] Leave one draft on each of two different addresses — they do not leak into each other
- [ ] After disabling "keep chat box contents", nothing is written or restored
- [ ] Run the first three steps once each on 1.20.1 / 1.21.11 / 26.2: the injection points are compiled per version and must be confirmed separately

## 8. Quick dummy summon / removal (Misc)

Configuration: **Misc → summon count** (slider, 1–50, default 20), **dummy send interval** (slider, 1–100 ticks,
default 10), **quick dummy summon**, **remove dummies**.
The server must have Carpet installed, `commandPlayer` enabled and the current player authorised; otherwise Misc shows only the line
`未检测到 Carpet 的 /player 命令` and the buttons do not appear. The record file is
`user home/.smile_toolb/dummies.json`, grouped by connection address and deleted once everything is removed.

- [ ] Join a server with Carpet, open the config screen and confirm both sliders and both buttons are present
- [ ] **The bottom row does not scroll**: scroll the middle list to the bottom and to the top — the two buttons and the red hint do not move
- [ ] The button reads "召唤假人" (no count); the removal button carries the current record count "删除假人（N）"
- [ ] Drag the slider to 7 and the summon button text **does not change** (the count lives only in the slider above)
- [ ] **Both buttons grey out during a batch**: click "Summon dummies" and look at the bottom immediately — neither button responds,
      and a red line `有进行中的批量执行任务` appears to the right on the same row; both recover and the red text disappears when the queue drains (about 10 s)
- [ ] A greyed button shows a tooltip on hover; without a specific reason it reads `该选项暂不可用`
- [ ] Pressing and dragging the slider changes the value continuously and stops on release
- [ ] **The interval is adjustable and takes effect immediately**: set "dummy send interval" to 20 ticks, summon, and observe a clearly slower
      packet cadence in chat / log (about 1 per second); at 1 tick it is clearly faster (and more likely to be kicked — short test only)
- [ ] Clicking "Summon" creates 20 dummies in about 10 s at the default 10 ticks, and `bot_xxxx` players appear on the server
      (recognisable by name in the tab list); the client must never be kicked for spam
- [ ] Summoning again adds a new batch with names not repeating the previous batch
- [ ] Let a dummy pick up / wear a few items, then click "Remove": the items drop on the ground first, then the dummy disappears
- [ ] After removal the button reads "删除假人（0）" and `dummies.json` is deleted
- [ ] Log in from another client, join the same address — the button shows the previous round's count (if not fully removed)
- [ ] **Calibration on join**: summon a few dummies → leave the server → kill 2 of them manually server-side with `/player <name> kill`
      (or restart the server so they vanish) → rejoin, wait about 5 s, and the log shows
      `假人：进服校准，移除 N 个已不存在的记录 [...]` with the button count decreasing accordingly
- [ ] Calibration must not over-delete: within the first 5 s of joining, dummies still online must not be cleared; if the server has not yet sent the
      tab list, the log should read `在线名单为空……本次跳过校准` and records stay unchanged
- [ ] Opening the screen on a server without Carpet (or with `commandPlayer` disabled): both buttons are grey and inert,
      with the hover hint `该选项暂不可用`
- [ ] Run steps 1–3 once each on 1.20.1 / 1.21.11 / 26.2: slider interaction is implemented by the library's `ConfigScreen`,
      shared by both generations, but the host's `mouseDragged` / `mouseReleased` signatures differ
- [ ] Verify the "online player list" once on each of 1.20.1 / 1.21.11 / 26.2: no `NoSuchMethodError` in the log
      (`GameProfile.getName()` became `name()` in authlib 7, and the library branches per version)

**Boundaries**: commands are sent at the configured interval (default 10 ticks ≈ 2 per second), so 20 dummies take about 10 s; removal takes twice as long
(one `drop` plus one `kill` each). Disconnecting mid-summon voids the remaining commands while dummies already created stay on the server
(the next join calibration corrects the record from the online list). If the server still flags spam, raise the interval further
(maximum 100 ticks = one per 5 s); lowering it is faster but risks a kick (vanilla `disconnect.spam`'s token bucket is
about 20 per second, and with client / server tick misalignment "one per tick" already drains it — measured).
Dummy names are random and could in theory collide with a real player; the probability is very low, Carpet rejects that entry and logs a warning.

## Version-specific notes

| Version | Focus |
| --- | --- |
| 1.20.1 | Baseline version; everything should work normally |
| 1.20.4 / 1.20.6 | Background rendering became the blurred variant; no pure black or ghosting should appear |
| 1.21.1 / 1.21.4 | Same as above |
| 1.21.5 / 1.21.8 | Narrator `say` gained a volume argument; check that the fallback to vanilla also passes volume |
| 1.21.11 | Input goes entirely through event objects: check the config screen, voice list and exit key; the controls screen should show a separate "Smile's toolb" category (registration failure falls back to "Misc") |

## Combat assist (new page)

Run the pure-Java terrain check first; no game launch needed:

```bash
python tools/selftest-terrain/run.py     # expect 84 / 84 (terrain 15 + guard criteria 11 + equip 9 + fireworks 5 + cleanup recovery 4 + glide 9 + resume flight 6 + lead 11 + impact 9 + shaft 5)
```

The nine pure-Java impact cases (player at (0.5, 30, 0.5), vertical velocity -1.2, ground 10 blocks below, reach 3):

| Case | Entity | Expected |
| --- | --- | --- |
| S1 | boat 9 blocks straight below | hit in reach column |
| S2 | armour stand 8.5 blocks straight below | hit in reach column |
| S3 | boat 2.5 blocks beside the landing point (at ground height) | hit in reach column |
| S4 | stationary boat within twice the reach (5 blocks) | no hit |
| S5 | boat 12 blocks away | no hit |
| S6 | boat 7 blocks away approaching at 0.5 blocks/tick | hit by motion prediction |
| S7 | armour stand above the player falling alongside at 1.5 blocks/tick | hit by motion prediction |
| S8 | boat flying past laterally 12 blocks away | no hit |
| S9 | boat below the ground (10 blocks under the landing point) | no hit |

Expected results for the baseline scenarios (the self-test and in-game behaviour must agree):

| Scenario | Clearance | Surface width | Expected |
| --- | --- | --- | --- |
| Single-block path in mid-air | 64 | 1 | Dangerous (width 1 + no platform on either side) |
| Underground path 2 high, 1 wide | 2 | 1 | Dangerous (clearance 2) |
| Underground 3×3 corridor | 3 | 3 | Dangerous (clearance 3) |
| Underground 4×4 corridor | 4 | 4 | Safe (clearance 4 and not open sky) |
| Jumping 1.5 blocks on open grass | 64 | 17 | Safe (the reference is the nearest ground underfoot, not the foot position) |
| 3 blocks above a single-block path | 64 | 1 | Dangerous (the surface is still 1 wide) |
| Hovering 20 blocks up | 64 | 8 | Safe (no ground within 12 blocks below, so the width rule does not apply) |
| Jumping 1.5 blocks inside a 3×3 corridor | 3 | 3 | Dangerous (less than a full jump above the surface, still in that cell) |
| 1×1 shaft (walls on all four sides, open to the sky) | 64 | 1 | **Safe** (vertical passage, take-off allowed) |
| 1×1 three-deep pit (with ceiling) | 3 | 1 | Dangerous (being a shaft does not help; clearance 3) |
| Niche with two walls | 64 | 1 | Dangerous (not a shaft, still a width-1 path) |

`Width 1` covers two terrains, separated by **whether all four sides are walls**: a single-block path in mid-air has air on all sides → dangerous;
a 1×1 shaft (both the foot and head cell enclosed by full blocks) → safe. Opening an elytra in a shaft and flying up is normal play;
locking it would seal every vertical mineshaft.

The guard criterion `inside()` = dangerous + within 3 blocks of the surface + (**a ceiling above** or **genuinely standing on the surface**):

| Situation | Clearance | Above surface | Verdict |
| --- | --- | --- | --- |
| Standing / running / jumping / jumping to the top in a 3×3 corridor | 3 | 0 – 2.9 | Guard (ceiling above, cannot fly out) |
| Standing on a single-block path | 64 | 0 | Guard |
| **Jumping** off a single-block path (intending to glide) | 64 | 1 – 3 | Yield (open narrow path; take-off is not intercepted) |
| 6 blocks above a single-block path (genuinely falling) | 64 | 6 | Yield (that elytra may be the only means of self-rescue) |

"Standing on a snow layer" is safe too: snow layers / carpets / slabs have collision heights under one block, the foot position falls inside that cell,
and it is ground, not a ceiling. Previously clearance computed as 0, so standing on snow flip-flopped every tick between "guard start" and "guard stop",
stripping the just-equipped elytra on a jump — the felt behaviour being "yanked down right after take-off".

In-game walkthrough (the bottom bar permanently shows a `护甲锁：…` line while the config screen is open, and every state flip also logs one):

- [ ] Stand on a **single-block path in mid-air** and open the config screen: the bottom bar shows `守卫中|净空=64 宽=1 露天=是 平台=无 危险=是`
- [ ] Walk into an **underground path 2 high and 1 wide**: `净空=2 宽=1`, guarding
- [ ] Walk into an **underground 3×3** corridor: `净空=3 宽=3`, guarding
- [ ] Walk into an **underground 4×4** corridor: `净空=4 宽=4 危险=否`, state returns to idle
- [ ] In dangerous terrain, let an auto-equip mod put on an elytra: the chest slot does not become an elytra (log `护甲锁：拦下一条自动换鞘翅的操作` or `已换回护甲`)
- [ ] In dangerous terrain, **open your own inventory** and equip the elytra manually: it goes on normally (manual actions are allowed)
- [ ] Equip the elytra in the air and then enter a dangerous area: the chest slot is swapped for the strongest armour in the inventory
- [ ] **Normal gliding** (not sneaking, pressing nothing) over narrow paths / low altitude: the chest slot is **not** swapped for armour,
      and the bottom bar does not turn red (state stays `空闲`)
- [ ] **Jumping in place** on open flat ground: no guard (previously misjudged as a width-1 path and the elytra was stripped)
- [ ] **Running and jumping back and forth inside an underground 3×3 corridor**: the state stays `守卫中`, `离地` on the bottom bar oscillates between 0 and 2.5,
      and an auto-equip mod **never gets the elytra on** (previously the guard yielded as soon as the feet left the ground, so an elytra appeared within two jumps)
- [ ] Walking through a door / jumping onto a slab in that corridor: state and `净空/宽` update on the same tick (no longer every 5 ticks)
- [ ] Free-falling from 20 blocks (no elytra): nothing is intercepted during the fall (more than 3 blocks above the surface),
      and the guard resumes within 1 tick of landing
- [ ] **Standing on snow layers / carpets / slabs** (foot position inside a thin block): the bottom bar reads `空闲` with `净空=64` or so,
      no more per-tick `守卫中` flapping
- [ ] **Jumping off an open narrow path** (single-block path / bridge) to glide: the elytra is not stripped (yields above 0.6 blocks)
- [ ] If the log shows `护甲锁：胸甲槽的鞘翅被换走了（换成 …）——不是本模组做的`,
      another mod or the server touched the chest slot; paste that line with a few lines of context
- [ ] Landing and standing in dangerous terrain while wearing an elytra: the chest slot keeps the elytra (it was the player's own gear on entry);
      only an elytra "put on automatically during the guard" is swapped back for armour
- [ ] On 1.21+, falling with sneak held, a mace in the inventory and a mob straight below: the log shows, in order,
      `重锤猛击：锁定护甲并切出重锤（…，目标 手长柱内 minecraft:zombie（N tick 后落地））`
      → `落地前挥击` → `已落地，解除护甲锁`, and the landing frame deals slam area damage
- [ ] The same operation with a **boat** or an **armour stand** as the target: it triggers too (the id in the log is
      `minecraft:oak_boat` / `minecraft:armor_stand`)
- [ ] A target approaching laterally from 6 blocks away: the log's `目标` says `运动预判命中 …`, and the swing still happens around landing
- [ ] Only **item entities / arrows / experience orbs** below: no trigger (they are not in the entity snapshot)
- [ ] A target below your landing point but behind the ground (e.g. at the bottom of a pit): no trigger
- [ ] Both a **Density V mace** and an unenchanted mace in the inventory (both on the hotbar, the unenchanted one earlier):
      after triggering, the hand holds the **Density V** one (confirm the held item right after "切出重锤" in the log)
- [ ] A more realistic pair: `Breach IV + Unbreaking III + Mending` versus `Wind Burst I + Density V + Fire Aspect II`
      (the Breach one earlier in the inventory) → the hand must hold the **Density V** one (26.2 once had the condition
      comments eaten, reading every density level as 0 and always switching to the earlier Breach mace)
- [ ] The Density mace only in the inventory while the hotbar holds the unenchanted one: the Density one is still selected (one swap packet is sent)
- [ ] One slam on a high-latency (200 ms) server: the bottom-right of the config screen shows `延迟 200ms（自动 3，实际提前 3 tick）`,
      the log shows `重锤猛击：落地前挥击（距地 … 格，提前 3 tick，延迟 200ms）`, and the slam registers
- [ ] Turning "auto lead from latency" off on the same server: the bottom bar becomes `手动 2` and the lead returns to 2 ticks (for comparison)
- [ ] Manual value 5 with auto on: the bottom bar reads `实际提前 5 tick` (the manual value is a floor; if the auto value is lower, the manual one wins)
- [ ] With tweakeroo's "auto switch rockets" on, spam-attack in mid-air (sneak held) until landing:
      the log shows `重锤猛击：手上是 minecraft:firework_rocket，重新切重锤（…）`,
      the subsequent `落地前挥击（… 手上 minecraft:mace）` used the mace, and the previous weapon is restored after landing
- [ ] While gliding, **looking level** (view unchanged) and holding shift for about half a second: the elytra comes off after 8 ticks and the player falls, with the log
      `滑翔中等下砸（… 需保持 8 tick，已保持 …）` → `摘下鞘翅，取消滑翔`
- [ ] While gliding, **looking down at a mob** and holding shift: it acts after 3 ticks (no half-second wait)
- [ ] While gliding, tapping shift briefly (one dive pulse): **no trigger** (the accumulated count decays)
- [ ] While gliding with no entity below, holding shift: only a reading such as `滑翔中等下砸（…无…）` appears and the elytra stays on
- [ ] The same situation with the mace only in the inventory (hotbar slot left empty): the first check returns "swing next tick",
      takes effect the following tick and still lands the hit (there is only that much slack when the lead is ≥ 2 ticks)
- [ ] No leftover rocket in hand after landing: `重锤猛击：把手换回 …` appears, even if the mace was lost mid-flow
- [ ] **Jumping once in place** (mob at your feet, sneak held, mace in the inventory): **no trigger** (1.25 blocks < 1.5),
      the hand does not switch to the mace and there is no `锁定护甲并切出重锤`; falling from 2 blocks or more does trigger
- [ ] While guarding (standing in a 3×3 corridor), with tweakeroo's "auto switch rockets" on, pressing space to fly:
      the rocket is swapped back to the weapon the instant it is inserted, with the log `护甲锁：守卫中手里的烟花是别的模组换上的，已换回 …`
- [ ] In the same position, **scrolling / pressing a number key** to the firework slot yourself: **not swapped back** (the bottom bar still says guarding, but the hand does not move)
- [ ] While gliding (low flight) with tweakeroo switching to rockets automatically: no interference, the rocket stays in hand for acceleration
- [ ] Being knocked back by a mob to two or three blocks up in a 3×3 corridor / under a ceiling and immediately equipping an elytra to fly: **it goes on**
      (≥ 1.75 blocks yields), and the log no longer shows `护甲锁：拦下一条往胸甲槽放鞘翅的操作`
- [ ] Standing in the same spot (0 blocks above) and pressing the hotkey to equip an elytra: still intercepted (that is the narrow-path protection's job)
- [ ] Running and jumping quickly in a 2×2 corridor (1.25 blocks up): still intercepted, the elytra does not sneak in
- [ ] **Launched immediately** after a successful glide slam (storm / explosion / mob launch) with no living entity left below:
      the log shows `把鞘翅穿回去，恢复飞行` → `把烟花火箭换到手上，可以继续飞`,
      and pressing space takes off again
- [ ] Same situation with a living entity below and **sneak held**: flight is not restored; a second slam follows
- [ ] Same situation with a living entity below but **sneak not held**: flight is restored (there is a target but no intent to slam, so flying is correct)
- [ ] A clean landing after the slam: during the two-second window no elytra is equipped and the hand does not change (no "resume flight" in the log)
- [ ] Killing the target after the flow enters `重锤锁定`: `解除护甲锁（下方没有可砸的目标）` within 3 ticks,
      followed by the flight recovery above
- [ ] No other armour and no mace in the inventory: pressing sneak should **leave you gliding** (the mace switch fails first and the flow gives up,
      instead of yanking you down), with the log `重锤切不到手上，放弃这次`
- [ ] The chest piece never becomes an elytra during a slam (packets are intercepted while `LOCKED`)
- [ ] **Drop in place = cancel the glide**: glide over a mob and hold sneak: the log shows
      `重锤猛击：摘下鞘翅，取消滑翔（目标 …）`, the elytra comes off and the player drops immediately,
      but **horizontal velocity remains** — WASD corrects the landing point (the player is not pinned to a vertical line)
- [ ] As above but with only one armour piece in the inventory (the elytra itself): the log shows `背包里没有别的护甲，取消滑翔失败`,
      the bottom bar returns to `空闲`, and the flow does not stick in "mace locked"
- [ ] Landing on the target's head lands the hit and takes no fall damage (a slam hit resets fall height)
- [ ] With "drop in place" disabled on the page: no trigger while gliding no matter how long sneak is held; ordinary falling still locks armour / switches
      to the mace / swings before landing
- [ ] The standalone "fall protection" was removed in 1.0.24 (it misfired); the fall tests above only cover the mace flow
- [ ] Standing / jumping in a **1×1 shaft**: the bottom bar reads `空闲` with `竖井=是`, and the elytra is not stripped
- [ ] 1.20.1 has no mace: the feature stays silent and raises no errors
- [ ] With `需要潜行` turned off on the sneak key, any fall triggers it
