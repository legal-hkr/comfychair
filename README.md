# ComfyChair

A simplified, mobile UI for [ComfyUI](https://github.com/comfyanonymous/ComfyUI) on Android.

## Overview

ComfyChair provides a streamlined mobile interface for interacting with ComfyUI servers, allowing you to generate and manage AI images directly from your Android device. The app communicates with your ComfyUI server via its API, bringing the power of node-based AI image generation to your mobile workflow.

## Screenshots

| Login | Text to image  | Progress bar | Settings |
|-------|----------------|--------------|----------|
| <img src="screenshots/login.png" width="200"/> | <img src="screenshots/texttoimage.png" width="200"/> | <img src="screenshots/progressbar.png" width="200"/> | <img src="screenshots/settings.png" width="200"/> |


| Gallery | Configuration  | | |
|---------|----------------|-|-|
| <img src="screenshots/gallery.png" width="200"/> | <img src="screenshots/configuration.png" width="200"/> | | |

## Features

- **Server connection**: Connect to remote or local ComfyUI servers with automatic HTTP/HTTPS detection and self-signed certificate support
- **Dual workflow support**:
  - Checkpoint-based workflows (traditional ComfyUI models)
  - Diffusers workflows (Flux, Z-Image, and other UNET-based models)
- **Image generation**:
  - Mobile-optimized interface with real-time progress tracking
  - Visual progress bar overlay showing generation progress
  - Cancel generation at any time with one-tap interrupt
  - WebSocket-based live updates (step-by-step progress)
- **Image gallery**: View all generated images with thumbnail grid layout
- **Image management**: Save to device gallery, save as file, or share images
- **Server configuration**:
  - View detailed server information (ComfyUI version, OS, Python, PyTorch versions)
  - Monitor hardware resources (RAM and GPU VRAM usage)
  - Server management actions (clear queue, clear history)
- **Configuration persistence**: Automatically saves and restores all settings including prompts, models, and generation parameters
- **Persistent navigation**: Bottom navigation bar for seamless switching between text-to-image, gallery, and configuration screens
- **Native Android experience**: Built with Kotlin and Material Design 3

## Requirements

- Android 14 (API level 34) or higher
- Access to a running ComfyUI server instance
- Network connectivity to reach your ComfyUI server

## Development setup

### Prerequisites

1. **Android Studio** (latest stable version recommended)
2. **JDK 11** or higher
3. **Android SDK** with API level 36

### Building the project

1. Clone the repository:
   ```bash
   git clone <repository-url>
   cd ComfyChair
   ```

2. Set up your local environment:
   - Ensure `JAVA_HOME` is set to your JDK installation
   - Configure Android SDK path in `local.properties`

3. Build the app:
   ```bash
   ./gradlew assembleDebug
   ```

4. Run on device/emulator:
   ```bash
   ./gradlew installDebug
   ```

### Running tests

```bash
# Unit tests
./gradlew test

# Instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

## Configuration

To connect to your ComfyUI server, you'll need:
- ComfyUI server URL (e.g., `http://192.168.1.100:8188`)
- Network access between your Android device and the ComfyUI server

## Tech stack

- **Language**: Kotlin 2.0.21
- **Min SDK**: Android 14 (API 34)
- **Target SDK**: Android 15 (API 36)
- **Architecture**: Modern Android with AndroidX components
- **UI**: Material Design 3
- **Build system**: Gradle with Kotlin DSL

## Project structure

```
app/src/main/
├── java/sh/hnet/comfychair/
│   ├── MainActivity.kt              # Login/connection screen
│   ├── MainContainerActivity.kt     # Fragment container with persistent navigation
│   ├── TextToImageFragment.kt       # Image generation screen (fragment)
│   ├── GalleryFragment.kt           # Image gallery screen (fragment)
│   ├── ConfigurationFragment.kt     # Server configuration and management (fragment)
│   ├── GalleryAdapter.kt            # RecyclerView adapter for gallery grid
│   ├── ComfyUIClient.kt             # API client for ComfyUI server
│   ├── WorkflowManager.kt           # Workflow JSON management
│   └── SelfSignedCertHelper.kt      # SSL certificate handling
├── res/
│   ├── layout/                      # UI layouts
│   │   ├── activity_main.xml        # Login screen layout
│   │   ├── activity_main_container.xml  # Container with bottom navigation
│   │   ├── fragment_text_to_image.xml   # Generation screen layout
│   │   ├── fragment_gallery.xml     # Gallery screen layout
│   │   ├── fragment_configuration.xml   # Configuration screen layout
│   │   └── bottom_sheet_config.xml  # Generation configuration panel
│   ├── raw/                         # Workflow JSON files
│   │   ├── checkpoint_*.json        # Checkpoint-based workflows
│   │   └── diffusers_*.json         # Diffusers workflows
│   ├── values/                      # Strings, themes, colors
│   ├── drawable/                    # Icons and graphics
│   └── xml/                         # Backup rules, file provider paths
└── AndroidManifest.xml
```

## Contributing

This project follows standard Android development practices:
- Kotlin coding conventions
- Material Design guidelines
- AndroidX compatibility

## License

[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.en.html)

## Acknowledgments

- [ComfyUI](https://github.com/comfyanonymous/ComfyUI) - The powerful node-based UI this app interfaces with
