# Legacy pixel-vision compatibility core

The compatibility core is isolated in `pixel-vision`; string parsing never runs in a pixel hot
loop. Its semantics were recovered from the decompiled `funXunTu.java`, `generateParams.java` and
the parameter-generation UI rather than guessed from function names.

Verified legacy signatures:

```text
DuoDianZhaoSe   -> XUNTU (INT,INT,INT,INT,CHAR,INT,INT)
DuoDianBiSe     -> INT   (CHAR,INT)
GetRectColorNum -> INT   (INT,INT,INT,INT,CHAR)
GetRGBColor     -> INT   (INT,INT,INT)
```

Direction mapping is exact: `0` bottom-left to top-right, `1` top-left to bottom-right, `2`
bottom-right to top-left, `3` top-right to bottom-left and `4` center-out. `stepX/stepY` are engine
safety/performance controls layered below the old signatures and default to one.

Accepted parameter grammars are deliberately strict, with one optional final `#` because the old
generator emits it and the copy UI removes it:

```text
# DuoDianZhaoSe: anchor followed by signed relative points
(r,g,b)-(dr,dg,db)#(dx,dy)|(r,g,b)-(dr,dg,db)

# DuoDianBiSe: fixed absolute points
(x,y)|(r,g,b)-(dr,dg,db)#(x,y)|(r,g,b)-(dr,dg,db)

# GetRectColorNum: one or more alternative colors
(r,g,b)-(dr,dg,db)#(r,g,b)-(dr,dg,db)
```

All RGB/tolerance channels are `0..255`; relative offsets are signed 32-bit; absolute coordinates
are unsigned 32-bit. Whitespace, missing fields, extra separators and overflow fail closed. Point
percentage uses ceiling arithmetic, so with ten points every value from 71 through 80 requires
eight matches, exactly as the legacy help states.

Structured samples can be serialized back with `format_relative_pattern`, `format_fixed_pattern`
and `format_color_list`. The serializer emits the copy-UI canonical form without a trailing `#` and
fails rather than dropping alpha, a non-zero anchor offset or excess items. Parse-format-parse is
covered as a lossless round trip.

## Lua boundary

The four functions are exposed only through the explicit `Legacy` namespace:

```lua
local result = Legacy.duoDianZhaoSe(
    frame, left, top, width, height, parameters, direction, minimumMatchPercent
)
local compared = Legacy.duoDianBiSe(frame, parameters, minimumMatchPercent) -- 1 or 0
local count = Legacy.getRectColorNum(frame, left, top, width, height, parameters)
local rgb = Legacy.getRgbColor(red, green, blue)
```

`DuoDianZhaoSe` returns `XunTuResult`: `count` is the bounded result count, `first` is the first
`{x,y}` point or `nil`, and `points` is the complete result array in the selected legacy search
order. The explicit immutable `frame` replaces hidden global screen state. Top-level functions such
as `DuoDianZhaoSe` are deliberately not registered; a future source importer must rewrite verified
old names and result properties to this namespace instead of silently changing semantics.

The API contracts use the separate `vision.pixel.legacy` capability. Simple aliases to modern
`Screen.*` are intentionally not treated as compatibility because their ROI, thresholds and result
shapes differ.
