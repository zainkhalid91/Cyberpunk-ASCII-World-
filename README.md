# NEON//ASCII

A playable rainy cyberpunk side-scroller rendered as live ASCII art: Compose Multiplatform for
Windows and Android (13+).

## How it works

1. `city/CityRenderer.kt` draws the city with Compose `Canvas`: a parallax skyline, neon signs,
   billboards, flying cars, storm clouds, a hologram koi, cables and street lamps. Static
   buildings and clouds are cached as half-resolution bitmaps, and glows use one shared sprite.
2. `game/` holds the playable layer. `Level.kt` lays out crate stacks, dumpsters with awnings,
   fire escapes, catwalks, moving hover pads, a ramen stall, steam vents, data shards and an exit.
   `Runner.kt` is the physics (variable jump, double jump, coyote time, one-way platforms,
   drop-through). `GameRenderer.kt` poses the hooded courier procedurally and draws their
   holo-canopy, which stops the rain.
3. `ascii/AsciiShader.kt` is one runtime shader (SkSL, which is the same language as AGSL) on
   that layer. It turns each cell into a glyph by brightness, mirrors the street, glitches rows
   and drives the tap shockwave.
4. Near rain, splashes, street ripples and the HUD are drawn crisp on top.
5. `audio/Synth.kt` mixes a calm, slowed and low-passed CC0 rain recording, drips triggered by
   splashes near the middle of the screen, rare recorded thunder, a generated pad, footsteps and
   a pickup chime.

## Street life and weather

`city/Ambience.kt` keeps the city inhabited without touching gameplay:

- **Crowd**: pedestrians under glowing LED umbrellas (or hooded, with lit visors) walk the pavement, stop under shop lights and scatter when a fight breaks out. They are drawn on the fine hero grid so they read as characters.
- **Sky-train**: an elevated train with lit, occupied windows, a headlight beam and pantograph sparks crosses every 16–34 s with a soft low rumble. It lights the scene as it passes.
- **Searchlight drone**: a police drone sweeps a spotlight over the street, and the pool of light is a real light for the ASCII pass.
- **Wet street**: every light also shines from its reflection, so neon pools under lamps and signs. Puddles hold a smear of colour and ripple in the rain.
- **Fog, drips, wind**: low fog that only shows where light catches it, water dripping off every awning and catwalk edge, and gusts that tilt the rain and blow flyers down the street.

## Glyphs by shape and material

Characters are chosen by what a cell contains, not only how bright it is:

- **Edges** (outlines bigger than a cell) become `| - / \` following their direction.
- **Water**: everything below the street line is sparse `- ~ _` dashes that drift with the ripples.
- **Neon** (saturated and bright) is round, glowing `o O 0 @`.
- **Walls, haze and rain** are quiet punctuation `. : ; =`; warm light (windows, lamps) keeps the full letter ramp.

## Things that react

`game/Reactive.kt`: weeds along the street sway in the wind and bend away from anyone walking through; loose cables with bulbs hang from catwalks and swing when brushed or slashed; cans get kicked and bounce down the street; footsteps send oval ripples through puddles; vent steam parts around the runner. Moths circle every lamp, motes drift and blink like fireflies, and stuttering signs spit sparks.

## Run

- Windows: `./run-desktop.ps1` (F11 toggles fullscreen)
- Android: `./run-android.ps1 [-Serial <adb id>]` (locks the display to 60 Hz)
- Offscreen PNGs: `gradlew :shared:snapshot --no-configuration-cache`. Add `-Pperf` for draw
  timings only plus a Flight Recorder profile in `snapshots/perf.jfr`.

## Controls

- Keyboard: A/D or arrows move, Space/W jump (again in the air to double jump), S drops through a
  platform, J swings the saber (press repeatedly for a 3-hit combo; swings deflect enemy bolts),
  K fires the blaster, Shift (or L) dashes with invulnerability, and landing on enemies stomps them.
  The mouse wheel zooms at the cursor and dragging pans while zoomed (0 resets). T or a click calls
  lightning, P toggles autopilot, 1/2/3 switch ASCII/SPLIT/RAW, C changes cell size, M mutes, H hides the HUD.
- Phone: on-screen ◀ ▶ ▼ JUMP SABER SHOOT DASH buttons, pinch to zoom, tap the sky for lightning.

## Combat

Troopers telegraph with a laser sight and fire bursts; blade duelists guard frontal hits (break it
with the combo finisher, or strike from behind or after they swing); hounds crouch and leap; drones
fire slow orbs. Three arenas lock you in with holo-barriers for three waves each. 5 HP, health cells
drop from enemies, and arenas are checkpoints. Hit-stop, screen shake and a slow-motion last kill
sell the impacts (`game/Combat.kt`, `game/EnemyRenderer.kt`).

Zooming magnifies the ASCII image itself: the world and the glyph cells grow together, so you
can zoom in anywhere (wheel or pinch at the pointer) and read the individual characters.

JetBrains Mono is bundled under the SIL Open Font License. The audio sources are listed in
`shared/src/commonMain/composeResources/files/SOURCES.txt`.
