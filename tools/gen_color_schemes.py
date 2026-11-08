# Usage: pip install materialyoucolor; python gen_color_schemes.py "#3D5A98" > schemes.kt
# Generates Velin's static fallback color schemes (tonal spot, 3 contrast levels).
import sys
from materialyoucolor.hct import Hct
from materialyoucolor.scheme.scheme_tonal_spot import SchemeTonalSpot
from materialyoucolor.dynamiccolor.material_dynamic_colors import MaterialDynamicColors
def argb_from_hex(h): return 0xFF000000 | int(h.lstrip("#"), 16)

seed = Hct.from_int(argb_from_hex(sys.argv[1]))
roles = ["primary","onPrimary","primaryContainer","onPrimaryContainer","inversePrimary",
 "secondary","onSecondary","secondaryContainer","onSecondaryContainer",
 "tertiary","onTertiary","tertiaryContainer","onTertiaryContainer",
 "background","onBackground","surface","onSurface","surfaceVariant","onSurfaceVariant",
 "surfaceTint","inverseSurface","inverseOnSurface","error","onError","errorContainer","onErrorContainer",
 "outline","outlineVariant","scrim","surfaceBright","surfaceContainer","surfaceContainerHigh",
 "surfaceContainerHighest","surfaceContainerLow","surfaceContainerLowest","surfaceDim",
 "primaryFixed","primaryFixedDim","onPrimaryFixed","onPrimaryFixedVariant",
 "secondaryFixed","secondaryFixedDim","onSecondaryFixed","onSecondaryFixedVariant",
 "tertiaryFixed","tertiaryFixedDim","onTertiaryFixed","onTertiaryFixedVariant"]
mdc = MaterialDynamicColors(spec="2021")
out = []
for dark in (False, True):
    for name, level in (("", 0.0), ("MediumContrast", 0.5), ("HighContrast", 1.0)):
        scheme = SchemeTonalSpot(seed, dark, level, spec_version="2021")
        fn = ("dark" if dark else "light") + "Scheme" + name
        out.append(f"internal val {fn} = {'darkColorScheme' if dark else 'lightColorScheme'}(")
        for r in roles:
            dc = getattr(mdc, r, None)
            if dc is None:
                raise SystemExit("missing role " + r)
            argb = dc.get_argb(scheme) & 0xFFFFFFFF
            out.append(f"    {r} = Color(0x{argb:08X}),")
        out.append(")\n")
print("\n".join(out))
