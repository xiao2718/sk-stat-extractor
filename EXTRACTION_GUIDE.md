# Spiral Knights data-extraction guide for modders

How the gear-stat extraction in this repo actually works, with enough background
to extend it to any other Spiral Knights config file.

The writeup is split into:

1. [The data model — where stats live](#1-the-data-model)
2. [The Clyde / three-rings serialization framework](#2-the-clyde--three-rings-serialization-framework)
3. [The minimal extraction strategy used here](#3-the-minimal-extraction-strategy)
4. [Classpath and build details](#4-classpath-and-build-details)
5. [Obfuscation gotchas — naming drifts between builds](#5-obfuscation-gotchas)
6. [Reading the XML output](#6-reading-the-xml-output)
7. [Extending: pulling any other `.dat`](#7-extending)
8. [Going the other way — round-tripping XML back to `.dat`](#8-round-tripping)
9. [Reference: every `.dat` file and its Config class](#9-reference-every-dat-file-and-its-config-class)
10. [Source-code map](#10-source-code-map)

---

## 1. The data model

Spiral Knights stats exist in **two layers**, and you generally need both to
reproduce an in-game tooltip number.

### Layer A — tier-scale constants (hard-coded in Java)

In the decompiled tree at
`com/threerings/projectx/item/data/ItemCodes.java`, the enums
`ItemBonus` and `ItemValueKey` define how a tier label (`LOW`, `MEDIUM`, `HIGH`,
`VERY_HIGH`, `ULTRA`, `MAXIMUM`) translates to a number:

| ItemValueKey | step  | LOW | MED  | HIGH | V.HIGH | ULTRA | MAX  |
|--------------|-------|-----|------|------|--------|-------|------|
| DAMAGE       | 0.08  | +8% | +16% | +24% | +32%   | +40%  | +48% |
| SPEED        | 0.04  | +4% | +8%  | +12% | +16%   | +20%  | +24% |
| CHARGE       | 0.08  | -8% | -16% | -24% | -32%   | -40%  | -48% |
| HEALTH       | 40    | +40 | +80  | +120 | +160   | +200  | +240 |
| TAGGED       | 0.10  | +10%| +20% | +30% | +40%   | +50%  | +60% |

The formula in `ItemCodes$ItemValueKey.value(bonus, positive, raw)`:

```
bonus == CUSTOM       → use raw
bonus != CUSTOM       → sign × step × bonus.ordinal()
```

A `CUSTOM` entry uses the raw float in its `damage` / `speed` / `reduction` /
`health` field directly, **without** tier scaling. Tier-mode entries ignore the
raw float — even if it's present in the data, the runtime uses the tier.

`DefenseIncrease` is the exception: it has no tier, always a raw float
(`defense`). This is the only path that lets armor tune defense per level
freely.

These constants do not exist in any `.dat` file — they're compiled into the
game. To get them, read `ItemCodes.java`, period.

### Layer B — per-item data (binary `.dat` files)

Everything else (which item has which bonus tiers, raw defense numbers, attack
damages, status resists, status condition masks, recipes, accessory configs,
etc.) lives in Clyde-serialized binary files at:

```
…/Spiral Knights/rsrc/config/*.dat
```

There are 55+ `.dat` files; the [reference table below](#9-reference) lists
the Config class each one deserializes into.

The six relevant to gear stats:

| .dat                  | Config class                                                          |
|-----------------------|-----------------------------------------------------------------------|
| `item.dat`            | `com.threerings.projectx.item.config.ItemConfig`                      |
| `level_table.dat`     | `com.threerings.projectx.item.config.LevelTableConfig`                |
| `forge_property.dat`  | `com.threerings.projectx.item.config.ForgePropertyConfig`             |
| `item_property.dat`   | `com.threerings.projectx.item.config.ItemPropertyConfig`              |
| `accessory.dat`       | `com.threerings.projectx.item.config.AccessoryConfig`                 |
| `attack.dat`          | `com.threerings.projectx.dungeon.config.AttackConfig`                 |

Each `.dat` deserializes to a `ManagedConfig[]` (named entries). Inside each
entry, fields are typed Java objects — primitives, strings, enums, nested
configs, or `ConfigReference<T>` (a by-name pointer to another entry).

---

## 2. The Clyde / three-rings serialization framework

Spiral Knights is built on the [Clyde](https://github.com/threerings/clyde)
game library by Three Rings. Clyde defines a generic XML/binary serialization
format for `Exportable` objects, plus a `ConfigManager` that loads named config
entries from `.dat` groups.

### Key classes you'll touch

| Class                                       | Role                                          |
|---------------------------------------------|-----------------------------------------------|
| `com.threerings.export.BinaryImporter`      | Reads one `.dat` → `Object`                   |
| `com.threerings.export.XMLExporter`         | Writes any object → human-readable XML        |
| `com.threerings.export.XMLImporter`         | Reads XML back (lets you round-trip)          |
| `com.threerings.export.BinaryExporter`      | Writes back to `.dat`                         |
| `com.threerings.config.ConfigManager`       | Owns all loaded groups, resolves references   |
| `com.threerings.config.ConfigGroup<T>`      | One named-entry table (one `.dat`)            |
| `com.threerings.config.ManagedConfig`       | Base class for every entry (`.name`)          |
| `com.threerings.config.ConfigReference<T>`  | Symbolic pointer (string name + arguments)    |

### Read API in one line

```java
Object root;
try (BinaryImporter bi = new BinaryImporter(new FileInputStream("item.dat"))) {
    root = bi.pL();   // top-level: usually a ManagedConfig[]
}
```

### Write API in one line

```java
try (XMLExporter xe = new XMLExporter(new FileOutputStream("item.xml"))) {
    xe.bf(root);      // dump the whole tree to XML
}
```

That's everything `DumpDats.java` does.

### What `BinaryImporter.pL()` returns for a `.dat`

For every `.dat` produced by `ConfigGroup.save()`, the top-level object is a
typed array (`ManagedConfig[]` or a subclass array). Element 0 of the XML
serialization makes this explicit:

```xml
<object class="[Lcom.threerings.projectx.item.config.ItemConfig;">
  <entry>
    <name>Gear/Armor/Chaos Cloak</name>
    ...
```

So one `.dat` ≈ one Config class ≈ one named-entry table.

### What about `ConfigManager`?

`ConfigManager` provides the higher-level API: you give it a `rsrc/` root and a
config path, it reads `manager.properties` (which lists every Config class per
type), discovers every `.dat`, loads them all, and lets you resolve
`ConfigReference<T>` by name.

For pure extraction, `ConfigManager` is **overkill and dangerous**:

* it transitively pulls in `ResourceManager`, message managers, expression
  evaluators, OpenGL config classes, sound configs, and more — many of which
  call into native LWJGL bindings on initialization;
* most of its API is obfuscated to single letters (`kK`, `kR`, `bb`, `bc`, …);
* it needs a real `rsrc/` layout, not just the `.dat` files.

For research/extraction the **simpler, more robust** path is:
**`BinaryImporter` → `XMLExporter` → grep/parse the XML.** That's what this
repo does. The 75-line `DumpDats.java` is the entire data path.

You'd only reach for `ConfigManager` if you need to resolve
`ConfigReference<T>` chains at runtime — e.g. follow an item's
`globalModifier` pointer through a `Derived` parent to a base item. The XML
already contains those references by name, so you can do the resolution
post-hoc in Python/jq instead of fighting `ConfigManager`.

---

## 3. The minimal extraction strategy

The whole approach:

```text
.dat ──BinaryImporter.pL()──▶ Object tree ──XMLExporter.bf()──▶ readable XML
```

Two tiny advantages of going through XML rather than reflecting on the live
object tree:

1. **No obfuscation surface.** Field names in the XML are real (e.g. `defense`,
   `bonus`, `chargeTime`) because `XMLExporter` reads `@editor.c`-annotated
   fields by reflection and `ProGuard` keeps those names (they're load-bearing
   for the editor). Method names are obfuscated; field names are not.
2. **Default-suppression.** `XMLExporter` only emits non-default values, which
   makes the output sparse and grep-friendly. An empty `<entry
   class=".LevelConfig$ChargeTimeReduction"/>` means "all defaults":
   `bonus=CUSTOM, reduction=0.0, positive=true`.

`DumpDats.java` is just a loop over the six `.dat` filenames calling those two
methods. ~75 lines including imports.

### Why not just dump every `.dat`?

You can — adding entries to the `FILES` list in `DumpDats.java` works for any
of the 55+ files. We picked six because that's what answers gear-stat
questions; `actor.dat`, `tile.dat`, `placeable.dat` etc. are huge and not
relevant to gear stats.

---

## 4. Classpath and build details

### Which jar has the runtime classes?

Spiral Knights ships its compiled classes split across jars in `code/`:

| Jar                              | Purpose                                    |
|----------------------------------|--------------------------------------------|
| `projectx-pcode.jar`             | **The big one.** Game logic, configs, Clyde framework, `BinaryImporter`, `XMLExporter`. 8200+ classes. |
| `config.jar`                     | Tiny — 8 classes, ProGuard-flattened config shims. |
| `projectx-config.jar`            | Resources only (`manager.properties`, GUI configs). No `.class`. |
| `commons-*.jar`, `lwjgl-*.jar`   | Standard third-party deps. Needed transitively. |

The decompiled tree at `code/20260511133836/` is `.java` only — **not usable as
a runtime classpath**, only as a reading reference. Compile against the
**jars**, not the source.

### Why the `(N).jar` files in `code/` are dangerous

The Spiral Knights launcher keeps old versions of each jar around as
`projectx-pcode (1).jar`, `projectx-pcode (2).jar`, `projectx-pcode (3).jar`,
plus `.bak_pre_…` backups. They contain the **same classes with different
obfuscated method names** (each rebuild re-shuffles the name pool).

If your classpath includes a stale jar, `javac` may pick up the older one
first and complain it can't find `pL()` (the current method name) even though
the current jar has it. The fix in `build_and_run.sh`:

```bash
case "$j" in
    *.bak_*|*-natives-*) continue ;;
    *"("*) continue ;;         # skip projectx-pcode (1).jar etc.
    *-new.jar) continue ;;
esac
```

This is mildly fragile — Knight Launcher's backup conventions could change.
If extraction breaks after a game update, **check classpath first**.

### Why include native LWJGL jars?

You don't strictly need them for pure data extraction (no rendering), so
`build_and_run.sh` excludes `*-natives-*`. Including them is harmless but
slows compile.

### Putting it together

```bash
SK="/home/alois/.local/share/Steam/steamapps/common/Spiral Knights"
CP="."
for j in "$SK/code/"*.jar; do
    case "$j" in *.bak_*|*-natives-*|*"("*|*-new.jar) continue ;; esac
    CP="$CP:$j"
done
javac -cp "$CP" -d . DumpDats.java
java  -cp "$CP" DumpDats "$SK/rsrc" out
```

Compile finishes in <2 s. Dumping the six files takes ~2.5 s total
(`item.dat` is the slow one at ~2 s for 11 MB of XML).

---

## 5. Obfuscation gotchas

ProGuard renames classes, methods, and (some) fields to short identifiers.
What survives in Spiral Knights builds, and what doesn't:

| Element                          | Renamed? | How to find the real name           |
|----------------------------------|----------|--------------------------------------|
| Public class names               | No       | Read the decompile.                  |
| Inner classes you reference      | No       | (e.g. `ItemConfig$Armor`)            |
| Internal classes                 | **Yes**  | e.g. `com.threerings.b.f`. Avoid.    |
| Public method names              | Mostly **yes**  | `pL`, `bf`, `kR`, `aV`, …     |
| `@editor.c`-annotated fields     | No       | Field names are load-bearing for editor reflection — they stay. **This is why XML output uses real names like `defense`, `bonus`, `damage`.** |
| Enum constants                   | No       | `LOW`, `MEDIUM`, `HIGH`, `STUN`, `POISON`, … |
| Static constants used by name    | No       | `ItemValueKey.DAMAGE` etc.           |

Practical rules:

* **Read the decompile at `code/20260511133836/`** for class/field structure.
* **Run `javap -public` against the live jar** when you need a method name
  (decompile may be out of sync with the jar that ships in the current build).
* **Field names = stable.** Method names = volatile. If you call any non-trivial
  method, verify it exists in the *current* jar before relying on it.

Example real-name vs. decompiled-name drift:

```text
BinaryImporter.pL()    // current jar  (2026-05-11 build)
BinaryImporter.pN()    // older jar    (2026-04-25 build)
```

Both still return `Object`, both still read the same `.dat` byte-format — only
the method symbol changed.

---

## 6. Reading the XML output

### Top-level structure

Every `out/*.xml` looks like:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<java class="com.threerings.export.XMLImporter" version="1.0">
<object class="[L<TYPE>;">
  <entry>
    <name><CONFIG_NAME></name>
    <implementation class="<Subclass>">
      ... fields ...
    </implementation>
  </entry>
  ...
</object>
</java>
```

* `<object class="[L…;">` — Java array-type encoding for `ManagedConfig[]`.
* Each `<entry>` is one named config; `<name>` is the lookup key
  (e.g. `"Gear/Armor/Chaos Cloak"`).
* `<implementation>` carries the actual data subclass (e.g. `ItemConfig$Armor`,
  `ItemConfig$Weapon`, `ItemConfig$Derived`).

### `ItemConfig$Derived` — the inheritance pattern

Many items use `Derived`, which references another item by name with an
argument map for overrides:

```xml
<implementation class="com.threerings.projectx.item.config.ItemConfig$Derived">
  <item>
    <name>Accessory/Armor/Parts/Aura</name>
    <arguments>
      <key class="java.lang.String">Accessory</key>
      <value class="com.threerings.config.ConfigReference">
        <name>Armor/Aura/Aggro Aura</name>
      </value>
      ...
    </arguments>
  </item>
</implementation>
```

To resolve the real stats, follow the `name` pointer to the base entry and
apply argument substitutions. This is what the in-game `ConfigManager` does at
runtime; in extraction you do it as a post-processing step.

### Defaults are omitted

If a field equals its Java default (declared in the `*Config.java` source),
`XMLExporter` skips it. So an empty `<entry class="…ChargeTimeReduction"/>`
encodes `reduction=0.0f, bonus=CUSTOM, positive=true` — all defaults.
**To know what "default" means for each field, read the corresponding
`*Config.java` in `code/20260511133836/`.**

### Decoding `<bonus>` values

* `<bonus>MEDIUM</bonus>` → look up Layer A table, multiply by ordinal.
* `<bonus>CUSTOM</bonus>` (often implicit/omitted) → use the raw float in the
  sibling field (`<damage>`, `<speed>`, `<reduction>`, `<health>`).

When **both** are set in the XML (common in `item.xml`), the **tier wins**.
The raw float is data left over from the in-editor authoring — `ItemCodes`'s
`value()` method ignores it when `bonus != CUSTOM`.

### Decoding `<conditionMask>`

Status condition masks are bitwise-OR of `StatusCondition.mask()` values from
`com/threerings/projectx/dungeon/data/StatusCondition.java`:

```
STUN   = 1
POISON = 2
FIRE   = 4
FREEZE = 8
SHOCK  = 16
CURSE  = 32
SLEEP  = 256
```

So `conditionMask=62` = 2|4|8|16|32 = Poison+Fire+Freeze+Shock+Curse.

### Decoding `<resist>`

Integers, often negative for "weakness". The display in-game has its own
bucket labels (Low/Medium/High); the raw int is what the damage formula uses.

### Decoding `<depthScale>` references

```xml
<entry class="com.threerings.projectx.dungeon.config.DefenseConfig$DepthScale">
  <depthScale>
    <name>PC/Defense/Armor/Armor Base -10</name>
  </depthScale>
</entry>
```

This is a `ConfigReference<DepthScaleConfig>` — the actual numerical curve
lives in `depth_scale.dat`. To get real defense numbers you'd dump that too
and follow the reference. Add `"config/depth_scale.dat"` to `FILES[]` in
`DumpDats.java` and re-run.

### Useful grep patterns

```bash
# Every Chaos Cloak field
awk '/<name>Gear\/Armor\/Chaos Cloak</,/^  <\/entry>/' out/item.xml

# All weapons with a HIGH damage bonus
grep -B 20 '<bonus>HIGH</bonus>' out/item.xml | grep -A 1 RelativeDamageBonus

# Bonus tier histogram per .dat
grep -hoE '>(LOW|MEDIUM|HIGH|VERY_HIGH|ULTRA|MAXIMUM)<' out/item.xml | sort | uniq -c
```

For structured queries, **convert the XML to JSON once** with a tool like
[xq](https://github.com/sibprogrammer/xq) or a 30-line Python script using
`xml.etree`, and then use `jq` / pandas. The XML structure is regular enough
that you don't need an XSD.

---

## 7. Extending: pulling any other `.dat`

To dump, say, `recipe.dat` and `mission.dat`:

1. **Look up the Config class** in [§ 9](#9-reference-every-dat-file-and-its-config-class)
   or in `projectx-config.jar:rsrc/config/manager.properties` (the
   authoritative `.dat`→Config mapping inside the game).
2. **Append the path** to `FILES[]` in `DumpDats.java`:
   ```java
   "config/recipe.dat",
   "config/mission.dat",
   ```
3. **Re-run** `bash build_and_run.sh`.

`BinaryImporter` doesn't need to know the Config class — it reads the type
information from the binary stream and resolves classes via the JVM
classloader. As long as your classpath includes `projectx-pcode.jar`,
deserialization works for every `.dat` the game can read.

### Some `.dat` won't decode

A handful of files in `rsrc/config/` are 60–80 bytes — they're empty stubs
representing config groups with no entries (e.g. `crucible/rsrc/config/`
contains stub `.dat`s for crucible-only configs). `BinaryImporter.pL()` will
return a zero-length array; `XMLExporter` will write a 100-byte XML with no
`<entry>` children. That's expected.

### Editor `.dat`s

`rsrc/config/editor/` contains editor-only configs (e.g. tool palette,
keyboard shortcuts). They use the same format but the relevant Config classes
are in `com.threerings.editor.*` — usually nothing of gameplay interest.

---

## 8. Round-tripping (XML → `.dat`)

`XMLImporter` + `BinaryExporter` are the inverses. Sketch:

```java
Object tree;
try (XMLImporter xi = new XMLImporter(new FileInputStream("item.xml"))) {
    tree = xi.<method>();          // mirror of pL() — name varies per build
}
try (BinaryExporter be = new BinaryExporter(new FileOutputStream("item.dat"))) {
    be.<method>(tree);             // mirror of bf() — name varies per build
}
```

Find the real method names with:

```bash
javap -public -cp "$SK/code/projectx-pcode.jar" \
  com.threerings.export.XMLImporter com.threerings.export.BinaryExporter
```

**For mods, this is the canonical path**: dump → edit XML → re-encode → drop
into `rsrc/config/` (or into a patch jar). Spiral Knights' modding scene
already does this; see Knight Launcher's mod loader (`/modutils`) for examples
of binary-config patching at runtime.

Caveats:

* Class hashes and exporter version bytes must match. If `BinaryExporter` in
  your jar version disagrees with the game's loader, the game will reject the
  patched `.dat`. Always re-encode with the **same jar** the game uses.
* Server-side configs (anything authoritative for combat math) are also
  validated on the SK server — local edits to weapon damage won't help in
  online play.

---

## 9. Reference: every `.dat` file and its Config class

Pulled from `projectx-config.jar:rsrc/config/manager.properties`. The map is
the authoritative source — if the game adds a config in a future build,
re-read that file.

`.dat` filenames are derived from the Config class by lower-snake-casing the
name minus the `Config` suffix (e.g. `ForgePropertyConfig` →
`forge_property.dat`).

**`types = global, model, user_interface, scene`** — each type owns its own
class list. Most gameplay configs live under `global`. The full `global` list
(abridged for relevance to data-mining):

| `.dat`                       | Config class (`com.threerings.…`)                          | What's in it |
|------------------------------|------------------------------------------------------------|--------------|
| `accessory.dat`              | `projectx.item.config.AccessoryConfig`                     | Accessories (auras, capes, helms parts) |
| `actor.dat`                  | `tudey.config.ActorConfig`                                 | NPC/enemy/projectile actor configs |
| `area.dat`                   | `tudey.config.AreaConfig`                                  | Map area definitions |
| `attack.dat`                 | `projectx.dungeon.config.AttackConfig`                     | Raw attack/damage definitions |
| `battle_sprite.dat`          | `projectx.sprites.config.BattleSpriteConfig`               | Battle-sprite (player/enemy) configs |
| `behavior.dat`               | `tudey.config.BehaviorConfig`                              | AI behaviors |
| `catalog.dat`                | `projectx.shop.config.CatalogConfig`                       | In-game shop catalog |
| `conversation.dat`           | `projectx.config.ConversationConfig`                       | NPC dialogue |
| `cursor.dat`                 | `opengl.gui.config.CursorConfig`                           | UI cursors |
| `depot_catalog.dat`          | `projectx.mission.config.DepotCatalogConfig`               | Depot vendor catalog |
| `depth_scale.dat`            | `projectx.dungeon.config.DepthScaleConfig`                 | **Defense/HP/damage curves vs. depth — needed to resolve "Armor Base -10" etc.** |
| `description.dat`            | `projectx.config.DescriptionConfig`                        | UI description strings |
| `effect.dat`                 | `tudey.config.EffectConfig`                                | Game effects |
| `emote.dat`                  | `projectx.config.EmoteConfig`                              | Emotes |
| `event.dat`                  | `projectx.event.config.EventConfig`                        | Event triggers |
| `fire_action.dat`            | `projectx.dungeon.config.FireActionConfig`                 | Weapon-firing behaviors |
| `font.dat`                   | `opengl.gui.config.FontConfig`                             | Fonts |
| `forge_property.dat`         | `projectx.item.config.ForgePropertyConfig`                 | Forge-upgrade properties |
| `gift.dat`                   | `projectx.uplink.config.GiftConfig`                        | Steam-uplink gift items |
| `ground.dat`                 | `tudey.config.GroundConfig`                                | Tile-ground definitions |
| `harness.dat`                | `projectx.sprites.config.HarnessConfig`                    | Sprite harnesses |
| `interact.dat`               | `projectx.config.InteractConfig`                           | World-interaction configs |
| `interface_script.dat`       | `opengl.gui.config.InterfaceScriptConfig`                  | UI scripts |
| `item.dat`                   | `projectx.item.config.ItemConfig`                          | **Master item catalog** |
| `item_depth_weight.dat`      | `projectx.dungeon.config.ItemDepthWeightConfig`            | Loot weighting curves |
| `item_property.dat`          | `projectx.item.config.ItemPropertyConfig`                  | Misc per-item properties |
| `level_table.dat`            | `projectx.item.config.LevelTableConfig`                    | **Per-level stat ramps (defense progressions, etc.)** |
| `material.dat`               | `opengl.material.config.MaterialConfig`                    | Rendering materials |
| `mission.dat`                | `projectx.mission.config.MissionConfig`                    | Missions / quest definitions |
| `mission_group.dat`          | `projectx.mission.config.MissionGroupConfig`               | Mission groupings |
| `mission_property.dat`       | `projectx.mission.config.MissionPropertyConfig`            | Misc mission settings |
| `parameterized_handler.dat`  | `tudey.config.ParameterizedHandlerConfig`                  | Scriptable handlers |
| `path.dat`                   | `tudey.config.PathConfig`                                  | Movement paths |
| `placeable.dat`              | `tudey.config.PlaceableConfig`                             | Placeable scene objects |
| `recipe.dat`                 | `projectx.craft.config.RecipeConfig`                       | Crafting recipes |
| `recipe_property.dat`        | `projectx.craft.config.RecipePropertyConfig`               | Recipe properties |
| `render_effect.dat`          | `opengl.compositor.config.RenderEffectConfig`              | Render effects |
| `render_queue.dat`           | `opengl.compositor.config.RenderQueueConfig`               | Render queue config |
| `random_layout.dat`          | `projectx.dungeon.config.RandomLayoutConfig`               | Randomized dungeon layouts |
| `spawn_table.dat`            | `projectx.dungeon.config.SpawnTableConfig`                 | Enemy spawn tables |
| `status_condition.dat`       | `projectx.dungeon.config.StatusConditionConfig`            | Status-condition definitions |
| `style.dat`                  | `opengl.gui.config.StyleConfig`                            | UI styles |
| `texture.dat`                | `opengl.renderer.config.TextureConfig`                     | Textures |
| `tile.dat`                   | `tudey.config.TileConfig`                                  | Map tiles |
| `tile_replacement.dat`       | `projectx.event.config.TileReplacementConfig`              | Tile-replacement events |
| `uplink.dat`                 | `projectx.uplink.config.UplinkConfig`                      | Steam-uplink config |
| `variant.dat`                | `projectx.item.config.VariantConfig`                       | Item-model variants |
| `variant_table.dat`          | `projectx.item.config.VariantTableConfig`                  | Variant lookup tables |
| `wall.dat`                   | `tudey.config.WallConfig`                                  | Wall tiles |

(Plus `model`, `user_interface`, and `scene` types — typically read from `.dat`
files alongside `.xml` in the same directory.)

---

## 10. Source-code map

The decompiled tree at
`/home/alois/.local/share/Steam/steamapps/common/Spiral Knights/code/20260511133836/`
is your friend. Pin these files when working on gear stats:

| Path                                                                  | Why |
|-----------------------------------------------------------------------|-----|
| `com/threerings/projectx/item/data/ItemCodes.java`                    | Tier-scale constants (Layer A). |
| `com/threerings/projectx/item/data/ImmutableEquipmentModifiers.java`  | Runtime stat bundle (chargeTime, speed, attackSpeed, defendingSpeed). |
| `com/threerings/projectx/item/config/LevelConfig.java`                | Every kind of stat-delta subclass: `RelativeDamageBonus`, `TaggedDamageBonus`, `AttackSpeedChange`, `DefenseIncrease`, `HealthBonus`, `SpeedChange`, `ChargeTimeReduction`, `Compound`, `WeaponClass`, `Pill`. |
| `com/threerings/projectx/item/config/LevelTableConfig.java`           | Wraps `LevelConfig[]` per item. |
| `com/threerings/projectx/item/config/ItemConfig.java`                 | The big one (3.6k lines): every item kind. |
| `com/threerings/projectx/item/config/AccessoryConfig.java`            | Accessory schema. |
| `com/threerings/projectx/item/config/ForgePropertyConfig.java`        | Forge upgrades. |
| `com/threerings/projectx/dungeon/config/AttackConfig.java`            | Raw attack damage definitions. |
| `com/threerings/projectx/dungeon/data/StatusCondition.java`           | Status-condition bit values (`STUN=1`, `POISON=2`, …). |
| `com/threerings/projectx/dungeon/config/DefenseConfig.java`           | Defense subclasses (`DepthScale`, `StatusResist`, `Compound`). |
| `com/threerings/projectx/dungeon/config/DepthScaleConfig.java`        | Depth-scale curve definitions (resolves `Armor Base -10` to a number per depth). |
| `com/threerings/export/BinaryImporter.java`                           | Read-side. |
| `com/threerings/export/XMLExporter.java`                              | Write-side. |
| `com/threerings/config/ConfigManager.java`                            | High-level config API (read only when you must). |

### Quick orientation: how a tooltip number is computed

For "Calibur — Damage Bonus: Medium":

1. `item.dat` has entry `Gear/Weapon/Sword/Calibur` →
   `ItemConfig$Weapon.globalModifier = Compound[ RelativeDamageBonus{bonus=MEDIUM} ]`.
2. At display time, `LevelConfig$RelativeDamageBonus.Ua()` calls
   `ItemCodes$ItemValueKey.DAMAGE.value(MEDIUM, true, …)` →
   `0.08 × 2 = +0.16` (+16%).
3. The base damage comes from `attack.dat` (the weapon's attack chain),
   scaled by `(1 + 0.16)` to produce the displayed damage at that depth.

For "Chaos Cloak — Defense":

1. `item.dat` entry `Gear/Armor/Chaos Cloak` →
   `ItemConfig$Armor.defense = Compound[ DepthScale("Armor Base -10"), DepthScale("Armor Base -10", ELEMENTAL), StatusResist(mask=62, resist=-20) ]`.
2. At display time, the depth-scale name is resolved against `depth_scale.dat`
   into a per-depth float curve. (Numeric values not in this dump — add
   `depth_scale.dat` to `DumpDats.java` if you need them.)
3. The status-resist mask is decoded against `StatusCondition` (see §6).

---

## Appendix: extending `DumpDats.java` to do post-processing

If you want CSVs instead of XML, the easiest path is **dump to XML once, then
post-process with Python/jq**:

```python
import xml.etree.ElementTree as ET

t = ET.parse("out/item.xml").getroot()
for entry in t.iter("entry"):
    name = entry.findtext("name")
    impl = entry.find("implementation")
    if impl is None or "Weapon" not in (impl.get("class") or ""):
        continue
    for bonus in entry.iter("bonus"):
        print(name, bonus.text)
```

Reflecting against the live Java tree is also possible but you'd be fighting
ProGuard the whole way. XML is the path of least resistance.

If you do want a Java-side extractor for ergonomics (e.g. emit CSV with
resolved tier→% values inline), the recipe is:

1. Walk the array `BinaryImporter.pL()` returns.
2. For each `ManagedConfig`, use plain reflection (`getDeclaredFields()`) to
   read the values. Field names survive obfuscation, so this is stable.
3. For tier resolution, call `ItemCodes$ItemValueKey.DAMAGE.value(bonus, positive, raw)`
   — those methods are public and their names survive.

Pseudo-code:

```java
for (Object o : (Object[]) bi.pL()) {
    ManagedConfig mc = (ManagedConfig) o;
    Object impl = mc.getClass().getField("implementation").get(mc);
    // ... reflectively walk impl fields ...
}
```

Field-by-field reflection is more brittle than XML grep but lets you stay in
one language. Pick the path that fits your downstream pipeline.

---

*Resource for the Spiral Knights modding community. The `out/` XMLs in this
repo were generated from the 2026-05-11 client build.*
