# Hubitat-VeSync

A community fork of [NiklasGustafsson/Hubitat](https://github.com/NiklasGustafsson/Hubitat) focused on the **VeSync** ecosystem (Levoit air purifiers and humidifiers).

This fork preserves Niklas's original Core line drivers and adds:

- **Levoit Vital 200S** air purifier (LAP-V201S, including 200S-P)
- **Levoit Superior 6000S** evaporative humidifier (LEH-S601S)
- A parent-driver fix that was silently breaking humidifier polling for all users
- Connection-pool retry logic for transient HTTP failures

See [`Drivers/Levoit/readme.md`](Drivers/Levoit/readme.md) for full details, supported models, and installation instructions.

## Install via Hubitat Package Manager

Manifest URL:
```
https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/levoitManifest.json
```

Reference for canonical VeSync API payloads: [pyvesync](https://github.com/webdjoe/pyvesync).

## Support / community

Discussion, issue reports, and feature requests:

- **Hubitat community thread:** [Levoit Air Purifiers Drivers](https://community.hubitat.com/t/levoit-air-purifiers-drivers/81816) — the active discussion for both this fork and Niklas's original drivers.
- **GitHub issues:** [level99/Hubitat-VeSync/issues](https://github.com/level99/Hubitat-VeSync/issues) — for bug reports tied to a specific code path or PR.

## Credits

- **Niklas Gustafsson** — original VeSyncIntegration framework, Core 200S/300S/400S/600S drivers
- **Dan Cox** — community fork maintainer, v1.6+ contributions, Vital 200S, Superior 6000S, parent humidifier-method fix
- **elfege** — `setLevel()` support, Core 600S 'max' speed
- **pyvesync** ([webdjoe/pyvesync](https://github.com/webdjoe/pyvesync)) — canonical API payload reference
