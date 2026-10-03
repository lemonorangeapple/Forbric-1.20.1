# Mapping provenance & the no-MCP guarantee

Fabric mods reference the game through the **intermediary** namespace; Forge mods
reference it through **SRG** member names on top of **Mojang official ("Mojmap")**
class names. Forbric must expose the game in both namespaces and remap every mod to
one **canonical runtime namespace**.

On 1.20.1 that Forge production namespace is not a guess:
`forge-1.20.1-47.4.0-universal.jar` references `net/minecraft/world/...` classes and
`m_`/`f_` members, and no `net/minecraft/src/C_` names at all. So the two things a
Forge jar needs are the intermediary names of its **classes** (joinable from Mojang
official + Fabric intermediary) and the intermediary names of its **members** (which
need the SRG ids).

Which namespace that is depends on the game version. On an obfuscated version
(1.21.11, say) the canonical namespace is **intermediary**, and a Forge mod's
Mojmap bytecode is remapped into it. On a Mojmap-native version — 26.2, whose
vanilla jar is already deobfuscated — Forbric runs the canonical namespace as
identity (`-Dforbric.runtimeNamespace=named`) and no remap happens at all.

## Permitted sources (the only ones Forbric uses)

| Namespace | Source | Notes |
|---|---|---|
| official / Mojmap | Mojang official mappings (the ProGuard `.txt` shipped per MC version) | bundle Mojang's mapping-file licence acknowledgement |
| intermediary | Fabric intermediary | published per MC version by FabricMC |
| named (yarn) | Yarn | dev-time readability only |
| srg | Forge MCPConfig `config/joined.tsrg` (`de.oceanlabs.mcp:mcp_config:<mc>-<stamp>@zip`) | **build / first-run only**; downloaded locally, never committed or bundled. See below. |

## SRG is read from MCPConfig, locally

Earlier revisions of this document claimed the SRG namespace was "synthesized" by
joining Mojang official and Fabric intermediary. That is not possible: the `m_`/`f_`
identifiers are MCPConfig's own id space, disjoint from Fabric's `method_`/`field_`
ids. Reconstructing them would mean guessing, and a guessed name is a
`NoSuchMethodError` in the game.

For 1.20.1 Forbric therefore reads Forge's **MCPConfig** artifact — the coordinate
the Forge `-userdev` `config.json` carries in its `mcp` field, i.e.
`de.oceanlabs.mcp:mcp_config:1.20.1-<stamp>@zip` — and takes `config/joined.tsrg`
(`obf → srg`). It is joined with Fabric intermediary and Mojang official on the
shared **obfuscated** column. Because the tsrg's class column is MCPConfig's
synthetic `net/minecraft/src/C_...` (which Forge production does not use), only its
**member** column is taken; each class's `srg` name is set to its Mojmap name, so
the `srg` namespace is exactly what a published Forge jar speaks.

## Why: MCP data is non-redistributable

FML's licence states that MCP data (its method/field name tables) is **not
redistributable by third parties**. Forbric does not redistribute it:

- The MCPConfig zip is **downloaded on the machine that runs Forbric**, at build or
  first-run time, exactly as the Minecraft jar itself is. It is not committed and it
  is not bundled into any Forbric jar.
- No mapping table of any kind is committed to this repository. The only file in the
  tree carrying Minecraft names is the merge diagnostic report (see below), which is
  a report, not a table.
- The joined tree is assembled in memory from files the user already has for their
  own game version; `tools/dev.py` and the installer cache it under the build
  directory, beside the staged game artifacts, with the rest of the downloaded,
  never-committed inputs.

One committed file does contain Minecraft names, and it is worth being exact
about it. `run/merged-base/merge-conflicts.txt` is the diagnostic report emitted
by the merged-game-base builder; it lists classes, fields and method descriptors
in Mojang official form, e.g.

```
net/minecraft/world/entity/Entity#baseTick()V (forge hook lost)
```

That is a report, not a mapping table. It is a single column of names with no
obfuscated counterpart anywhere in the file, so nothing can be deobfuscated with
it, and it maps nothing to anything. The names in it are the ones already legible
in a Mojmap-native game jar; what is non-redistributable is MCP's name tables,
and none of those are present.

## Enforcement

Today the guarantee is **structural, not automated**:

- No mapping data is committed. The only file in the tree carrying Minecraft
  names is the diagnostic report described above; there is no tiny, ProGuard,
  SRG or TSRG table anywhere in this repository.
- Nothing in the code path ingests MCP or FML output. `ForbricMappings.load`
  takes exactly two inputs — a Fabric intermediary file and Mojang's ProGuard
  file — and everything downstream, SRG included, is joined from those two.

**Not implemented:** there is no build-time provenance gate. A Gradle
verification task that asserted every generated SRG/Mojmap name traces only to
Mojang-official + intermediary inputs, and failed the build if any string came
from an MCP/FML input path, would make this guarantee mechanical instead of
merely reviewable. No such task exists in `build.gradle`; the claim above rests
on reading the code and the tree.
