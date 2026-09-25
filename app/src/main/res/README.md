# Launcher & splash assets

This folder owns every asset that appears before the user reaches Compose:
the **home-screen icon**, the **splash screen** on cold start, and any
**themed-icon** variant used by Android 13+ launchers.

## Files

| File | Role |
|---|---|
| `mipmap-anydpi-v26/ic_launcher.xml` | Adaptive icon (API 26+). Wires background + foreground + monochrome. |
| `mipmap-anydpi-v26/ic_launcher_round.xml` | Round variant of the same. |
| `mipmap-anydpi/ic_launcher.xml` | **Legacy** adaptive icon, kept as a fallback for pre-API-26 launchers. Points at the previous star mark. Safe to remove once minSdk is raised. |
| `mipmap-anydpi/ic_launcher_round.xml` | Legacy round fallback. Same note as above. |
| `drawable/ic_launcher_foreground.xml` | **The mark.** Three concentric arcs in `#3D63F6` on a 108×108 viewport, 18u safe margin. |
| `drawable/ic_launcher_monochrome.xml` | Solid-ink copy of the same arcs, used by Android 13 themed icons. **Must stay solid `#000000`** — Android re-tints it. |
| `values/ic_launcher_colors.xml` | Light-mode icon background + splash background. |
| `values-night/ic_launcher_colors.xml` | Night-mode variant (currently identical to light). |
| `values/styles.xml` | `Theme.Jarvis.Splash` points at `ic_launcher_foreground` for the splash animation. |

## Safe area

Adaptive icons get a **66×66 unit safe circle** centered on the 108×108
viewport (18 unit margin on every side). Anything inside that circle is
guaranteed to show on every launcher; anything outside may be masked,
cropped, or hidden behind parallax. The current mark uses a 48-unit
diameter and is fully inside the safe circle.

## How to redraw the mark

1. Edit `drawable/ic_launcher_foreground.xml` — keep the viewport at
   `108×108` and the mark inside the inner 66×66.
2. Mirror the geometry in `drawable/ic_launcher_monochrome.xml` with all
   paths set to solid `#000000` and **no opacity, gradients, or color**.
3. Use `#3D63F6` (brand accent from `:core:designsystem` —
   `JarvisColors.Accent.primary`) for the foreground strokes/fills. Pull
   new colors from the design system; don't hardcode literals in feature
   code.
4. Rebuild: `./gradlew :app:assembleDebug`.

## How to add a tinted accent

If you want a per-brand-color launcher background, change
`ic_launcher_background` in `values/ic_launcher_colors.xml` and the night
override. Leave the foreground untouched so the mark stays readable on
every background.
