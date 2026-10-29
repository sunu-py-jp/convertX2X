"""Reproduce the checked-in static fonts; this is a maintenance tool, not a runtime dependency."""
import hashlib
import sys
from pathlib import Path

import fontTools
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

SOURCE_SHA256 = "c2f3b4d463500a2ddcd3849cded1fceeb9fd6d1c32e6cbecd568453ba50fc68f"
FONTTOOLS_VERSION = "4.68.0"

if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: python instantiate.py downloaded-variable.ttf output-directory")
    source, output = Path(sys.argv[1]), Path(sys.argv[2])
    if fontTools.version != FONTTOOLS_VERSION:
        raise SystemExit(f"Use fonttools=={FONTTOOLS_VERSION} for reproducible output")
    if hashlib.sha256(source.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise SystemExit("Pinned upstream font hash mismatch")
    output.mkdir(parents=True, exist_ok=True)
    for weight, style in [(400, "Regular"), (700, "Bold")]:
        variable = TTFont(source, recalcTimestamp=False)
        font = instantiateVariableFont(variable, {"wght": weight}, updateFontNames=True, static=True)
        font.recalcTimestamp = False
        if "fvar" in font or font["OS/2"].usWeightClass != weight:
            raise SystemExit("Expected a static instance at the requested weight")
        names = font["name"]
        family = names.getDebugName(16) or names.getDebugName(1)
        subfamily = names.getDebugName(17) or names.getDebugName(2)
        if family != "Noto Sans JP" or subfamily != style:
            raise SystemExit(f"Unexpected family/subfamily: {family}/{subfamily}")
        destination = output / f"NotoSansJP-{style}.ttf"
        font.save(destination, reorderTables=True)
        print(destination.name, hashlib.sha256(destination.read_bytes()).hexdigest())
