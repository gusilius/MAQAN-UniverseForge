# Atlas dimension → WorldForge planet generation

Image input is optional. The dimension profile is the source of truth for seed, scale and settlement density.

## Image convention

Place an image at `config/worldforge/images/<dimension-id>.png`.
Supported extensions: PNG, JPG, JPEG and WEBP. An explicit `--image` path takes priority, then the dimension's configured `image`, then the default image folder. If no file exists, generation automatically switches to procedural mode.

Windows filenames cannot contain `:`. For namespaced dimension IDs, use a sanitized filename such as `example_temperate_world.png` and set its path explicitly in the config's `image` property.

## Run

From the image-worldgen tool directory:

```powershell
python planet_generation.py --dimension-id example:temperate_world --output config/worldforge/maps/temperate.worldmap
```

Override profile values:

```powershell
python planet_generation.py --dimension-id example:temperate_world --seed 8821 --settlement-level 6 --width 1024 --height 512 --output config/worldforge/maps/temperate.worldmap
```

Set an image explicitly:

```powershell
python planet_generation.py --dimension-id example:temperate_world --image C:/maps/temperate.png --output config/worldforge/maps/temperate.worldmap
```

Seven settlement levels control initial political density and territorial partition thresholds. They are not technology or government eras. Government form remains independently seeded.

The generator emits the current `.worldmap` layers and `world-blueprint.json`. Java-side Atlas dimension registration and profile loading remain a separate integration task; generating a package does not itself register a Minecraft dimension.
