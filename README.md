# sk-stat-extractor

Pulls the raw numerical gear/equipment stats out of Spiral Knights.

Stats live in two layers:

1. **Tier-scale constants** (hard-coded in the game's Java) — see [`TIER_SCALE.md`](./TIER_SCALE.md). Maps `LOW`/`MEDIUM`/`HIGH`/… to actual percentages.
2. **Per-item configuration** in `rsrc/config/*.dat` binary Clyde files. `DumpDats.java` decodes them to XML.

**Modders:** see [`EXTRACTION_GUIDE.md`](./EXTRACTION_GUIDE.md) — full background on the Clyde framework, how the dump works, obfuscation gotchas, every `.dat` → Config class mapping, and how to round-trip XML back to `.dat` for patching.

## Run

```bash
bash build_and_run.sh
```

Defaults to `SK=/home/alois/.local/share/Steam/steamapps/common/Spiral Knights`; override with `SK=… bash build_and_run.sh`. Output XMLs go to `out/`.

## Files dumped

| Source `.dat`        | Contains                                                      |
|----------------------|---------------------------------------------------------------|
| `item.dat`           | Item catalog with bonus-tier assignments + CUSTOM raw floats  |
| `level_table.dat`    | Per-level defense ramps and bonus deltas                      |
| `forge_property.dat` | Forge upgrades                                                |
| `item_property.dat`  | Misc item properties                                          |
| `accessory.dat`      | Accessory configs                                             |
| `attack.dat`         | Raw attack/damage data per swing/shot                         |

## Reading the output

- **Weapon bonus tiers** → `out/item.xml`, look for the weapon name and inspect `<bonus>` inside its `<globalModifier>` block.
- **Armor / shield defense per level** → `out/level_table.xml`, `<DefenseIncrease><defense>…</defense>`.
- **CUSTOM exact values** → `out/item.xml`, `<damage>` / `<speed>` / `<reduction>` / `<chargeTime>` / `<health>` floats (used as-is, no tier scaling).
- **Raw attack damage per swing/shot** → `out/attack.xml`.
- **Forge upgrade values** → `out/forge_property.xml`.

Tier label → number: multiply the step from `TIER_SCALE.md` by the tier ordinal (`LOW=1` … `MAXIMUM=6`).
