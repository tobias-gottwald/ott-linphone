# OTT launcher icon source

Regenerates every launcher asset in `app/src/main/res/mipmap-*` and
`metadata/en-US/images/icon.png` from `ott_icon_master.png` (1408x1408,
white background — cleaned Grok Imagine output).

Pipeline: classify pixels (magenta handset/wordmark, gray "hören"),
un-mix anti-aliasing into flat colors + alpha, recolor magenta to OTT
pink `#ED0872`, then emit adaptive-icon foreground (RGBA, 108dp) and
legacy square/round icons (48dp) for all densities.

Run: `python generate_icon_assets.py` (needs pillow + numpy) from this
directory. Master must keep content inside the adaptive-icon safe zone
(centered circle, 61% of width).

Generated output lands in the repo working tree; review with git diff.
