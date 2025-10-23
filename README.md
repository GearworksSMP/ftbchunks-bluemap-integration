# FTB Chunks BlueMap Integration

This mod will show land claims from the [FTB Chunks](https://www.curseforge.com/minecraft/mc-mods/ftb-chunks-fabric) mod in the maps from the [BlueMap](https://modrinth.com/mod/bluemap) mod.

## Will you support \<Version\>?

Probably, please open an issue.

## Usage

Simply install FTB Chunks (along with FTB Teams and FTB Library), BlueMap, and this mod, and you should be all set!

## Commands

- `/ftbchunks-bluemap refresh-now` - Manually refresh the claims markers
- `/ftbchunks-bluemap refresh-in [time]` - Check or set when the next refresh will occur
- `/ftbchunks-bluemap refresh-every [interval]` - Check or set the auto-refresh interval
- `/ftbchunks-bluemap reload` - Reload the configuration

## Configuration

The configuration file is located at `config/ftbchunks-bluemap.json5` and includes:
- `updateInterval` - How often (in ticks) markers refresh (default: 12000 = 10 minutes)
- `markerMinY` and `markerMaxY` - Y-coordinates for marker display
- `depthTest` - Whether markers are occluded by terrain
