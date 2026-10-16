# TrimEffect

A Paper plugin for Minecraft **1.21.11**. Wearing armor with matching trim patterns grants potion effects.

## Rules
- Counts the four equipped armor slots: helmet, chestplate, leggings, boots.
- Matches the **trim pattern**, not the trim material/color.
- Most patterns require 4 matching armor pieces.
- Silence and Host require 2 pieces; Flow requires 3 pieces.
- Effect rules and thresholds are configurable in `plugins/TrimEffect/config.yml`.

## Build
Requires JDK 21 and Gradle.

From this folder, run:

```sh
gradle build
```

The plugin JAR will be at `build/libs/TrimEffect-1.0.0.jar`.

## Install
1. Build the JAR.
2. Put `TrimEffect-1.0.0.jar` in your Paper server's `plugins` folder.
3. Restart the server.
4. Edit `plugins/TrimEffect/config.yml` if desired, then restart the server.

## Notes
- This project uses the Paper API and does not need NMS or paperweight.
- Potion effects are refreshed periodically while the trim condition is met.
