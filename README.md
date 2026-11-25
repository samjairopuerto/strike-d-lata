<p align="center"><img src="banner.png" width="100%" alt="Strike D Lata"></p>
<p align="center">
  <a href="https://samjairopuerto.github.io/strike-d-lata/">
    <img src="https://img.shields.io/badge/%E2%96%B6%20PLAY%20NOW-in%20your%20browser-f5c542?style=for-the-badge" alt="Play Strike D Lata">
  </a>
</p>
# Strike D Lata: Kalye ng Sakuna

> **SIGAW NG SAKUNA** · *Tumbahin ang lata, iligtas ang laro!*

A Filipino arcade take on **Tumbang Preso**. Throw your tsinelas at the lata, dodge Taya, and survive a disaster in every street.
The whole game is one Java file that serves an HTML5 canvas game on your own machine. It also runs as a static page in any browser.

Course project for **IT 373A**, supporting **SDG 3** (Good Health and Well-being), **SDG 11** (Sustainable Cities) and **SDG 13** (Climate Action).

---

## Play

**In the browser (no install):** open `index.html` (or the GitHub Pages link for this repo).

**From source (JDK 17 or newer):**

```bash
javac StrikeDLata.java
java StrikeDLata
# then open http://localhost:8080
```

## Controls

| Action | Player 1 | Player 2 |
|---|---|---|
| Move / aim angle | `W` `A` `S` `D` | Arrow keys |
| Sight, then throw | `Space` | `Enter` |
| Hero skill | `E` | Right `Shift` |

| Key | Opens |
|---|---|
| `R` | How to play |
| `G` | Buff pogs |
| `D` | Disasters |
| `C` | Heroes |
| `B` | Scoreboard |
| `Esc` | Pause |

## Game modes

- **Classic story:** 50 levels across 5 biomes. Every 10th level is a boss fight.
- **2-player co-op Endless:** shared lives, waves that get harder, and a SYNC bonus for retrieving the lata together.

## Biomes and bosses

| Biome | Disaster | Boss |
|---|---|---|
| Kalye Uno | Flash flood | Jeepney-Bot |
| Dalampasigan | Amihan gusts and high tide | Mechanical Bakunawa |
| Palayan | Earthquake and landslide | Kalabaw-Bot 3000 |
| Perya sa Gabi | Brownout | Ferris-Bot |
| Bulkang Mayon | Ashfall and lava geysers | Mayon Magma-Bot |

## Heroes

Jun-Jun (Striker) · Nene (Sprinter) · Boyet (Tactician) · Taba (Heavy). Each hero has a unique skill and can be upgraded.

---

## What's new

### Latest update
- **Smooth, full-resolution graphics.** Clean 2D rendering with smooth scaling. No blocky pixel or palette effect.
- **Livelier running.** Heroes kick up dust and speed streaks, and lean and bounce while running. The effect is tuned per hero: Nene is the fastest and Taba kicks up the heaviest dust.
- **Fairer Endless co-op.**
  - The second Taya wears a **different skin** from the first.
  - **Neither Taya can tag a player while the lata is down.** The chase only resumes once the lata is back on its marker.
- **Smaller level-complete box,** so more of the street stays visible.

### Earlier updates
- **Buff-pog shop.** Buy buff pogs with Tansan. Each has a rarity and impact tier, and only owned pogs appear in a run.
- **Audio settings.** Music and sound-effect volume sliders that are saved on the device. Sound effects use small pitch variations so repeated hits sound less repetitive.
- **Endless mode.** Two Tayas and trap-heavy spawns for tense co-op play.
- **Disaster typography.** Clearer on-screen warnings in Level 1.

---

## Project notes

- **Single file.** `StrikeDLata.java` contains the server (built-in `HttpServer`, port 8080) and the full game client, embedded as text blocks.
- **Static build.** `index.html` is the same client with the fonts and scripts inlined, so it works on GitHub Pages.
- **Requirements:** JDK 17+ to run from source; any modern browser to play.

## Credits

Built by the Strike D Lata team for IT 373A.
