# Spiral Knights — gear stat tier-scale (Layer 1)

Source: `com/threerings/projectx/item/data/ItemCodes.java` (`ItemBonus`, `ItemValueKey`).

Formula: `value = sign × step × tier_ordinal`. Tier ordinals:
`CUSTOM=0, LOW=1, MEDIUM=2, HIGH=3, VERY_HIGH=4, ULTRA=5, MAXIMUM=6`.

Per-stat `step` constants:

| ItemValueKey | step    | Unit              |
|--------------|---------|-------------------|
| DAMAGE       | 0.08    | fractional bonus  |
| SPEED        | 0.04    | fractional bonus  |
| CHARGE       | 0.08    | fractional bonus  |
| HEALTH       | 40      | flat HP           |
| TAGGED       | 0.10    | fractional bonus  |

Resolved table (positive direction; flip sign for "decrease" / "increase charge time" variants):

| Tier        | DAMAGE  | SPEED   | CHARGE  | HEALTH | TAGGED  |
|-------------|---------|---------|---------|--------|---------|
| LOW         | +8%     | +4%     | -8%     | +40    | +10%    |
| MEDIUM      | +16%    | +8%     | -16%    | +80    | +20%    |
| HIGH        | +24%    | +12%    | -24%    | +120   | +30%    |
| VERY_HIGH   | +32%    | +16%    | -32%    | +160   | +40%    |
| ULTRA       | +40%    | +20%    | -40%    | +200   | +50%    |
| MAXIMUM     | +48%    | +24%    | -48%    | +240   | +60%    |

CUSTOM tier: the entry carries an explicit raw float in the LevelConfig field
(`damage`, `speed`, `defense`, `health`, `reduction`) and is used **verbatim**
without scaling. The bonus-tier label shown in tooltips for CUSTOM is derived
by reverse-mapping the raw value through `ItemValueKey.key(float)` —
i.e. it's bucketed for *display* but the underlying number is exact.

DefenseIncrease has NO tier — it always uses a raw float `defense` per level
(`LevelConfig.DefenseIncrease`, line 218). The displayed defense bar is the
sum across all levels of an armor's LevelTableConfig.
