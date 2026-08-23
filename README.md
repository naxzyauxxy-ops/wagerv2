# WagersPlugin

Challenge players to a fight for money. When a wager is accepted, both players are randomly teleported (RTP) to a spot in the wild, frozen for a countdown, and then the fight starts. Winner takes the whole pot.

## Features
- `/wager <player> <amount> [mode]` — challenge someone for money (Vault economy)
- Multiple fight modes: **Classic** (your own gear), **Diamond**, **NoDebuff**, **Sumo**, **Hardcore**
- RTP: both players teleport to a random safe spot, spawned a few blocks apart, facing each other
- Configurable pre-fight freeze countdown (default 5s) with titles + sounds
- Money is taken up front and held; winner receives the full pot
- Kit modes snapshot and restore your real inventory after the fight (no item loss)
- Outsiders can't interfere; quitting or forfeiting counts as a loss
- Requests expire, server-wide fight broadcasts, tab completion

## Commands
| Command | Description |
|---|---|
| `/wager <player> <amount> [mode]` | Send a wager challenge |
| `/wager accept` | Accept the pending challenge |
| `/wager deny` | Deny the pending challenge |
| `/wager modes` | List fight modes |
| `/wager forfeit` | Give up (opponent wins the pot) |
| `/wager messages` | Toggle wager broadcasts per-player (**green ON** / **red OFF**) |
| `/wager stats [player]` | View wins, losses, and money won/lost |
| `/wager join` | Join the current event (also via the clickable chat button) |
| `/wager leave` | Leave the join queue (fee refunded) |
| `/wager event` | Show the next event's name, mode, prize, status, and timer |

Aliases: `/duel`, `/bet`

## Messages & toggle
Every message lives in `messages.yml` and is fully editable (colors with `&`). Each player can turn wager broadcasts on/off for themselves with `/wager messages` — the state shows as green **ON** or red **OFF** and is saved in `data.yml`. Fight participants always receive their own fight messages.

## Rotating Events
Events cycle automatically through the `events.rotation` list in `config.yml` — each has its own name, mode, prize, and optional entry fee. The flow: countdown between events → chat announcement with a clickable **[CLICK TO JOIN]** button (hover shows prize + fee) → join window → everyone teleports (RTP) into a circle, frozen countdown → FFA fight, last one standing wins the prize + all entry fees. When an event ends, the rotation advances to the next one and the placeholders switch to the new name and timer automatically. Not enough players or a timeout = fees refunded.

Event settings: `interval-seconds`, `join-seconds`, `min-players`, `max-fight-seconds`, `animation-frame-seconds`.

## PlaceholderAPI (optional soft-depend)
If PlaceholderAPI is installed, these placeholders register automatically:

| Placeholder | Value |
|---|---|
| `%wagers_wins%` | Total wins |
| `%wagers_losses%` | Total losses |
| `%wagers_money_won%` | Total money won |
| `%wagers_money_lost%` | Total money lost |
| `%wagers_net%` | Net profit |
| `%wagers_messages%` | Toggle state — green ON / red OFF |
| `%wagers_infight%` | true/false currently fighting |
| `%wagers_mode%` | Current fight mode |
| `%wagers_pot%` | Current fight pot |
| `%wagers_event_name%` | Current/next event name |
| `%wagers_event_time%` | Live countdown timer (waiting → join → LIVE) |
| `%wagers_event_status%` | Waiting / JOINABLE / LIVE |
| `%wagers_event_players%` | Players joined / alive |
| `%wagers_event_prize%` | Event pot (prize + fees) |
| `%wagers_event_mode%` | Event fight mode |
| `%wagers_event_animated%` | **Animated**: cycles event name → timer → join hint |

PlaceholderAPI placeholders also work inside `messages.yml`.

## Requirements
- Spigot/Paper 1.20+
- Java 17
- [Vault](https://www.spigotmc.org/resources/vault.34315/) + any economy plugin (e.g. EssentialsX)
- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) (optional, for placeholders)

## Building
### GitHub Actions (easiest)
Push this repo to GitHub. The workflow in `.github/workflows/build.yml` builds automatically — download the jar from the **Actions** tab → latest run → **Artifacts** → `WagersPlugin`.

### Local
```
mvn package
```
Jar output: `target/WagersPlugin-1.0.0.jar`

## Config (`config.yml`)
- `countdown-seconds` — freeze time before the fight starts
- `request-expire-seconds` — how long challenges last
- `min-wager` / `max-wager` — stake limits
- `rtp.world` — world for fights (empty = challenger's world)
- `rtp.radius` — RTP range around spawn
- `rtp.player-gap` — distance between the two fighters
- `broadcast-fights` — announce fights/results server-wide
