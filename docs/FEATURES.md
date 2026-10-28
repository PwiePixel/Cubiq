# Cubiq features

This page describes the features Cubiq adds on top of Purpur and Paper. Everything else behaves as in
Purpur; see [COMPATIBILITY.md](COMPATIBILITY.md).

## Configuration check

CraftBukkit, Spigot and Purpur silently ignore settings they do not know and settings whose value has the
wrong type. A misspelled setting therefore has no effect, and nothing tells the server owner about it.

Once the server has started, Cubiq compares `bukkit.yml`, `spigot.yml` and `purpur.yml` with the settings the
server actually used while starting, and logs every setting that has no effect as a warning of the `Cubiq`
logger, for example:

```
Configuration check: found 3 problems in bukkit.yml, purpur.yml, spigot.yml (37 ms). The following settings have no effect:
  bukkit.yml: Unknown setting 'spawn-limits.monster' has no effect. Did you mean 'spawn-limits.monsters'?
  purpur.yml: Unknown setting 'world-settings.default.mobs.zombie.ridabel' has no effect. Did you mean 'world-settings.default.mobs.zombie.ridable'?
  spigot.yml: 'world-settings.default.mob-spawn-range' must be a number, but is "eight", so it is ignored.
Fix or remove these settings. This check runs on every start and can be disabled with -Dcubiq.configCheck=false
```

When nothing is wrong, a single informational line confirms which files were checked. Both messages include how
long the check took.

What is reported:

- **Unknown settings and sections**: settings that the server neither read nor wrote while starting and that have
  no default. A section that is unknown as a whole is reported once, with the number of settings in it.
- **Suggestions**: the most similar setting at the same place in the file, ignoring case and `-`/`_`
  differences, or the same setting in another section when it was put in the wrong place.
- **Values of the wrong type**: for example text where a number or `true`/`false` is expected. The server uses the
  default value instead of such a value. The expected type is taken from how the server read the setting, or, for
  settings that are only read later (for example when a player joins), from the type of their default value.

How it works:

- While the server starts, the configuration objects of these files record which settings are read, with which
  expected type, and which settings the server writes. Recording stops when the check runs.
- Settings in a world section (`world-settings.<world>.<setting>`) are checked against the settings of
  `world-settings.default`, so settings for worlds that are not loaded are checked as well.
- Sections that the server reads as a whole, such as maps of blocks or entity types, may contain any key.
- The check only reads the files and logs; it never changes them. It runs once per start, after the
  `Done` message, and a failure inside the check is logged without affecting the server.

Limitations:

- Paper's configuration files (`config/paper-global.yml`, `config/paper-world-defaults.yml` and the per-world
  `paper-world.yml`), `server.properties` and `commands.yml` are not checked yet.
- Plugins that read these files with their own code can make the check report settings that only those plugins use.
- Settings inside sections that the server reads as a whole, such as Purpur's maps of blocks, are not checked
  individually, because the server reads them through the section. The per-world `generator` and
  `biome-provider` settings below `worlds.<world>` in `bukkit.yml` are an exception and are checked.
- Settings that the server only reads later, for example after a `/reload`, are not known when the check runs.
  CraftBukkit's settings are covered by the defaults of `bukkit.yml`, so this mainly affects plugins.

Disable the check by starting the server with `-Dcubiq.configCheck=false`.
