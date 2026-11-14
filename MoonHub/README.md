# MoonHub 1.21.1b

Paper 1.21.4 / Java 21. MoonHub is the MoonEclips hub management plugin.

## Current implementation
- Admin GUI: `/moonhub gui`
- Saved teleport points: `/spawn`, `/top`, `/avto`, `/case`, `/freecase`
- Config-backed point storage (persists across restarts)
- Cuboid regions with `safe`, `mine`, and `pvp` modes
- Auto-mine block restoration (restores the mined block type after the configured delay)
- Region-aware explosion and fire protection
- LuckPerms-compatible permissions (LuckPerms is not a hard dependency)

## Install
1. Build with Java 21 and Maven: `mvn -B clean package`.
2. Put `target/MoonHub-1.21.1b.jar` in the server's `plugins` folder.
3. Start Paper 1.21.4 once to create `plugins/MoonHub/config.yml`.
4. Give administrators `moonhub.admin`; trusted staff may receive `moonhub.bypass`.

## Admin GUI
Run `/moonhub gui`. Click a point icon to save your current location as that destination. The point is written to config.yml immediately.

## Commands
- `/moonhub gui` — open the admin menu.
- `/moonhub set <spawn|top|avto|case|freecase>` — save a teleport point.
- `/moonhub pos1 <region>` — mark first corner at your block position.
- `/moonhub pos2 <region>` — mark second corner.
- `/moonhub region <region> <safe|mine|pvp>` — create/update a cuboid region from the two marked corners.
- `/moonhub reload` — reload config.
- Player navigation: `/spawn`, `/top`, `/avto`, `/case`, `/freecase`.

## Region modes
- `safe`: block breaking/placing is denied; interactive blocks are protected; PvP is disabled.
- `mine`: block breaking is allowed and each mined block is restored after the configured delay; placing blocks is denied.
- `pvp`: block breaking/placing is denied; PvP is allowed only when both players are inside the same pvp region.
- `moonhub.bypass`: bypasses block and PvP region restrictions.

Example:
```
/moonhub pos1 spawn
/moonhub pos2 spawn
/moonhub region spawn safe
```

## Configuration
- `settings.prefix` — chat prefix.
- `settings.teleport-safe` — reserved for future safe-teleport validation.
- `settings.mine-regeneration.enabled` — enable/disable auto-mine restoration.
- `settings.mine-regeneration.delay-seconds` — restoration delay.
- `regions.<id>.*` — world, min/max coordinates and mode.
- `points.<id>` — saved Bukkit Location.

## Compatibility notes
- LuckPerms works through Bukkit permission nodes; no LuckPerms API dependency is required.
- MoonHub does not create or replace the player's sidebar scoreboard, avoiding direct scoreboard conflicts with Moonscoreboards.
- Economy integration is not required for the current hub navigation and protection core.
- Holographic leaderboards, NPC/case provider hooks, and the finished decorative 200x200 spawn world are not included in this first build yet; they require their dedicated implementation/integration and in-game verification.
