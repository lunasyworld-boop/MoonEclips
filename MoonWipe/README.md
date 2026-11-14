# MoonWipe 1.0.0

Paper 1.21.4 / Java 21 plugin for manual and scheduled full wipes.

## Build

From this directory:

```bash
mvn clean package
```

Artifact: `target/MoonWipe-1.0.0.jar`

## Install

1. Install the JAR in `plugins/`.
2. Keep Vault and EssentialsX Economy installed for Money reset.
3. Start once to generate `plugins/MoonWipe/config.yml`.
4. Edit `wipe.world-folders` so it exactly matches the world folder names on the server.
5. Verify `wipe.auction-database` points to the EconomicMoonEclips auction database.
6. Restart the server.

The default schedule is disabled. Configure `schedule.enabled`, `schedule.day`, `schedule.time`, and `timezone` before enabling it.

## Commands

- `/mwipe` — open the administration menu.
- `/mwipe start` — prepare a manual wipe and show a one-time confirmation code.
- `/mwipe confirm <code>` — confirm the prepared wipe.
- `/mwipe cancel` — cancel the pending preparation.
- `/mwipe backup` — create a backup without wiping.
- `/mwipe schedule <DAY> <HH:mm> <ZoneId>` — configure and enable the weekly schedule.
- `/mwipe schedule off` — disable automatic wipes.
- `/mwipe status` — show schedule and last operation.
- `/mwipe reload` — reload configuration.

Permission: `moonwipe.admin` (OP by default).

## What is reset

- Configured world folders. This resets world chunks, player inventories, ender chests, advancements, and vanilla statistics stored in those world folders.
- Vault balances for UUIDs found in EssentialsX userdata files.
- All application tables in the configured SQLite auction database. This is intended for the EconomicMoonEclips auction database, whose documented contents are auction listings and item-return mail.

## What is preserved

- LuckPerms data and player groups/permissions.
- Plugin JAR files and plugin configuration files.
- The MoonWipe configuration.
- Schematic files such as `plugins/WorldEdit/schematics/hub-spawn.schem`.
- Any data paths not explicitly listed in the wipe configuration.

## Safety and execution model

A wipe is not performed while the server is live. MoonWipe saves worlds, warns and disconnects players, creates a ZIP backup, writes a pending-wipe manifest, and then shuts the server down. The hosting panel must be configured to restart the server automatically; if it does not, an administrator must start it manually.

During the next startup, MoonWipe runs before EconomicMoonEclips and deletes the configured world folders and clears the configured SQLite database before normal world/plugin startup. Money is reset through the registered Vault Economy provider during plugin enable. A result report is written to `plugins/MoonWipe/last-wipe.yml`.

If the host does not restart the process, the wipe remains pending and no files are deleted until the next startup.

## Important limitations

- The plugin cannot guarantee a host-panel restart. Enable the Veroid auto-restart setting or start the server manually after shutdown.
- Only the configured world folder names are removed. Check `server.properties` and the actual folder names before the first wipe.
- Money reset requires Vault and a compatible EssentialsX Economy provider. If no provider is available, the report records a failure.
- The auction reset clears all non-system tables in the configured SQLite database. Point it only at the EconomicMoonEclips auction database.
- Custom scoreboard/plugin statistics are not guessed or deleted. Add their confirmed data paths to `wipe.extra-data-paths` only after inspecting those plugins.
- MoonWipe preserves the schematic file; it does not automatically paste the schematic into the new world in this first release.

Always test on a backup/test server before using this on a live production server.