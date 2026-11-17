# ADR 0066: The game's own look as an option

- Status: accepted
- Date: 2026-10-27
- Builds on: ADR 0047 (screen edits and skins), ADR 0048 (the client's look)

## Context

Some players want Minecraft's own menus, font, panorama and loading screen, not Frost. ADR 0047
made the look one skin plus a few screen edits and hooks, each of which falls back to the game's
drawing when it does nothing.

## Decision

A switch for the whole look, `Look.vanilla`, settled once a run, at the first ask (the loading
screen and the fonts ask before the first tick):

1. the page's `?look=vanilla` or `?look=rinth`, for one page load;
2. the player's choice, Rinth Settings' "Minecraft's look", kept in `rinth/look.txt` and used
   from the next start;
3. the build's, `rinth.look` in `rinth.properties` (`-Printh.look` overrides).

In the game's look: no skin and no pause or title edits; the game's title screen, with its Realms
button turned into Rinth Settings (Realms can't be reached); the game's loading screen; the
panorama, fetched from the asset index (the jar has blank stand-ins); the game's blur option; the
game's pixel font, whatever the theme says. Rinth's own screens stay.

## Consequences

- One flag read at each of the look's hooks; nothing changes in Rinth's look.
- The switch isn't live: the skin, edits and fonts are set up once.
- The panorama costs a fetch, in the game's look only.
