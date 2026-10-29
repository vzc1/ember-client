/* Edit this file to change the launcher. No build step needed.
   The mod list comes from mods.json (made by each build, with real icons).
   The "mods" list below is only shown until mods.json is next to index.html. */
window.SITE = {
  name: "Ember",
  fullName: "Ember Client",
  version: "26.2",
  gameFile: "play.html",

  credits: [
    "26.2 browser port by o_xer",
    "Eaglercraft runtime by lax1dude and contributors",
    "Mods belong to their authors"
  ],

  mods: [
    { name: "Fabric API", author: "FabricMC", categories: ["library"],
      description: "The library almost every Fabric mod needs." },
    { name: "AppleSkin", author: "squeek502", categories: ["utility"],
      description: "Shows hunger, saturation and food values." },
    { name: "Mod Menu", author: "Prospector", categories: ["utility"],
      description: "Lists your mods and opens their settings." },
    { name: "Shulker Box Tooltip", author: "MisterPeModder", categories: ["utility"],
      description: "Shows what's inside a shulker box when you hover it." }
  ],

  // Servers on the Servers page, e.g.
  // { name: "My Server", address: "wss://play.example.com", description: "Survival and PvP" }
  servers: []
};
