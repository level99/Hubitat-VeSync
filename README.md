# Hubitat-VeSync

A community fork of [NiklasGustafsson/Hubitat](https://github.com/NiklasGustafsson/Hubitat) focused on the **VeSync** ecosystem (Levoit air purifiers and humidifiers).

This fork preserves Niklas's original Core line drivers and adds:

- **Levoit Vital 200S** air purifier (LAP-V201S, including 200S-P)
- **Levoit Superior 6000S** evaporative humidifier (LEH-S601S)
- A parent-driver fix that was silently breaking humidifier polling for all users
- Connection-pool retry logic for transient HTTP failures

See [`Drivers/Levoit/readme.md`](Drivers/Levoit/readme.md) for full details, supported models, and installation instructions.

## Install via Hubitat Package Manager

### First-time install (no Levoit drivers yet)

The fork is HPM-installable. Two install paths depending on whether the fork is in HPM's master index yet:

1. **HPM keyword search** *(preferred — once the master-index PR merges)*: open HPM → Install → Search by Keywords → search `Levoit` or `VeSync` → install.
2. **HPM manifest URL** *(works today)*: open HPM → Install → From a URL → paste:
   ```
   https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/levoitManifest.json
   ```
   HPM downloads and installs the parent app + per-model drivers.

After install:

1. Apps → Add User App → select **VeSync Integration**
2. Enter your VeSync mobile-app credentials (same email + password)
3. Click Done. The parent logs in, discovers your Levoit devices, and creates one Hubitat child per device. Default refresh interval: 30s.

### Already running NiklasGustafsson/Hubitat upstream?

You can install the HPM package the same way as a first-time install. HPM matches our drivers to your existing Niklas-installed drivers by `(namespace, name)` and updates the source in place — existing devices keep working with no re-pairing.

If you had Vital 200S or Superior 6000S devices that previously showed "discovered but no data" on Niklas, you'll need to re-pick the device Type once after install (those drivers didn't exist upstream). See [`docs/migration-from-niklas-upstream.md`](docs/migration-from-niklas-upstream.md) for that step plus optional verification.

### Manual install (no HPM)

Paste each driver from `Drivers/Levoit/*.groovy` into Hubitat's **Drivers Code** page, then add the VeSync Integration app per the steps above. HPM is strongly recommended over manual paste — it handles updates automatically.

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
