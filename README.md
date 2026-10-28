# Ember Client

Minecraft 26.2 in the browser with Fabric mods, built on o_xer's Eaglercraft 26.2 port.

- `site/` – the website (edit `site/site-config.js` to change name, mods, credits)
- `scripts/` – build steps used by GitHub Actions
- `overlay/` – Ember's own files copied onto the generated project (Fabric support goes here)

This repo contains no Minecraft code. The build downloads the official client jar from Mojang
and the 26.2 patcher kit, decompiles and patches it on the build machine, and uploads the
playable HTML as a short-lived artifact.

Run a build: Actions tab → **Build Ember Client** → **Run workflow**.
