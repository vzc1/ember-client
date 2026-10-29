/* Edit this file to change the launcher. No build step needed. */
window.SITE = {
  name: "Ember",
  fullName: "Ember Client",
  tagline: "Minecraft 26.2 with Fabric mods, in your browser.",
  version: "26.2",
  gameFile: "play.html",         // the built game, placed next to index.html

  credits: [
    "26.2 browser port by o_xer",
    "Eaglercraft runtime by lax1dude and contributors",
    "Mods belong to their respective authors"
  ],

  // status: "included" = in the current build, "next" = being ported, "planned" = on the list
  mods: [
    { name: "Ember Core", author: "vzc1", category: "Utility", status: "included",
      description: "Ember's built-in mod. Shows the Ember brand on the title screen." },
    { name: "Fabric API", author: "FabricMC", category: "Library", status: "included",
      description: "The base library almost every Fabric mod needs." },
    { name: "Mod Menu", author: "TerraformersMC", category: "Utility", status: "next",
      description: "Lists installed mods and opens their settings." },
    { name: "AppleSkin", author: "squeek502", category: "HUD", status: "next",
      description: "Shows hunger, saturation and food values." },
    { name: "Zoomify", author: "isXander", category: "Utility", status: "planned",
      description: "Smooth, configurable zoom." },
    { name: "BetterF3", author: "cominixo", category: "HUD", status: "planned",
      description: "A cleaner, customizable F3 debug screen." },
    { name: "Shulker Box Tooltip", author: "MisterPeModder", category: "HUD", status: "planned",
      description: "Preview what's inside shulker boxes." },
    { name: "Sodium", author: "CaffeineMC", category: "Performance", status: "planned",
      description: "Faster rendering. Big port, comes later." },
    { name: "Iris Shaders", author: "IrisShaders", category: "Visuals", status: "planned",
      description: "Shader pack support. Biggest port, comes last." }
  ],

  // Servers shown on the Servers page. Example:
  // { name: "My Server", address: "wss://play.example.com", description: "Survival, PvP" }
  servers: []
};
