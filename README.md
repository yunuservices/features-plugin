# Features

Features is a multi-platform Minecraft plugin for MOTD and in-game customization.

> [!WARNING]
> Mojang removed player-head MOTD rendering for 26.1-era clients.
> MOTD image rendering in this plugin is only intended for clients in the `1.21.9` to `1.21.11` protocol range.
> The Paper and Folia tag system is still supported and requires `1.21.9+`.

## What It Includes?

- Paper and Folia image tag generation backed by MineSkin
- Paper PlaceholderAPI and MiniPlaceholders integration
- Paper standalone MOTD rendering
- Velocity MOTD rendering with persistent image and sprite caches
- Shared rendering, config, image, and MineSkin logic in a common core module

## Modules

- `core` shared runtime, rendering, config, image and MineSkin code
- `paper` Paper and Folia plugin module
- `velocity` Velocity plugin module

## Requirements

- JDK 21
- Internet access to the configured Maven repositories on the first build

## Build

Unix:

```bash
./gradlew test build
```

Windows:

```powershell
.\gradlew.bat test build
```

## Artifacts

- `build/libs/features-1.0.0.jar`
- `paper/build/libs/features-paper-1.0.0.jar`
- `velocity/build/libs/features-velocity-1.0.0.jar`

`features-1.0.0.jar` is the combined distribution. The platform-specific jars are also produced for targeted deployments.

## Overview

Paper and Folia:

- Main commands: `/features reload`, `/tags list`, `/tags view <name>`, `/tags generate <name> <sourceType> <source>`, `/tags rename <old> <new>`, `/tags delete <name>`
- Primary config files: `plugins/features/settings.yml`, `plugins/features/tags.yml`
- Import directory: `plugins/features/import/`
- Optional MOTD files: `plugins/features/config.yml`, `plugins/features/image-cache.yml`, `plugins/features/sprite-cache.yml`
- **MOTD image rendering is intended for protocol range `1.21.9` through `1.21.11`; other clients fall back to text**

Velocity:

- Main command: `/features reload`
- Primary config files: `plugins/features/settings.yml`, `plugins/features/config.yml`
- Cache files: `plugins/features/image-cache.yml`, `plugins/features/sprite-cache.yml`
- **MOTD image rendering is intended for protocol range `1.21.9` through `1.21.11`; other clients fall back to text**

## Placeholder Support

Paper and Folia can expose generated tags through optional integrations.

- PlaceholderAPI: `%features_tag_<name>%`
- PlaceholderAPI image token: `%features_image_<name>%`
- MiniPlaceholders and MiniMessage: `<features_tag:name>`

For MiniPlaceholders-based rendering, install MiniPlaceholders on the server that loads the Paper module.
