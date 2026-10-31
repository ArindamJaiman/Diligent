# Glyph Matrix (Nothing Phone (3))

Diligent can light the 25×25 Glyph Matrix on the back of a Nothing Phone (3):

- **Focus running:** a ring that fills over the planned length, with the minutes left inside
  (open-ended sessions show minutes so far, the ring laps every hour).
- **No session:** a ring of today's goals done, with the count inside.
- **Long press the Glyph button:** your best current streak.

It works as a **Glyph Toy**: pick *Diligent* in Settings → Glyph Interface → Glyph Toys on the
phone. The frames refresh every 30 seconds while the toy is selected.

## Adding the SDK

Nothing ships the client library as an `.aar` in their
[GlyphMatrix Developer Kit](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit).
It isn't on Maven, so it isn't committed here:

1. Download the kit's `.aar` (e.g. `glyph-matrix-sdk-*.aar`).
2. Put it in `app/libs/`. The build already includes any `.aar`/`.jar` there.
3. Build and install.

Diligent calls the SDK through reflection (`GlyphMatrixManager.getInstance`, `init`,
`register("23112")`, `setMatrixFrame(int[])`, `closeAppMatrix`, `unInit`), so without the `.aar`
the app builds and runs normally and the Glyph code does nothing.

## Status

The frames (`glyph/GlyphFrames.kt`) are unit tested and previewed in Hub → Glyph Matrix. The
SDK calls follow the kit's README but haven't been run on a Phone (3) yet; if Nothing renames
a method, adjust `glyph/GlyphBridge.kt`. LED values are 0–4095 (`GlyphBridge.MAX_BRIGHTNESS`).
