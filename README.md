# ComfyChair

A simplified, mobile UI for [ComfyUI](https://github.com/comfyanonymous/ComfyUI) on Android.

**Current version**: v0.3.0

## Overview

ComfyChair provides a streamlined mobile interface for interacting with ComfyUI servers, allowing you to generate and manage AI images and videos directly from your Android device. The app communicates with your ComfyUI server via its API, bringing the power of node-based AI generation to your mobile workflow.

## Screenshots

<img src="screenshots/login.png" width="200"/> <img src="screenshots/texttoimage.png" width="200"/> <img src="screenshots/texttoimage-livepreview.png" width="200"/> <img src="screenshots/texttoimage-settingsunet.png" width="200"/> <img src="screenshots/texttoimage-contextmenu.png" width="200"/> <img src="screenshots/texttovideo.png" width="200"/> <img src="screenshots/inpainting-maskeditor.png" width="200"/> <img src="screenshots/inpainting-sourceimage.png" width="200"/> <img src="screenshots/inpainting-preview.png" width="200"/> <img src="screenshots/gallery.png" width="200"/>
<img src="screenshots/toast.png" width="200"/> <img src="screenshots/configuration.png" width="200"/>

## Features

- **Server connection**: Connect to remote or local ComfyUI servers with automatic HTTP/HTTPS detection and self-signed certificate support
- **Dual workflow support**:
  - **Checkpoint mode**: Traditional CheckpointLoaderSimple workflows
  - **UNET mode**: Modern diffusion workflows (Flux, Z-Image, etc.) with separate UNET, VAE, and CLIP model selection
- **Text-to-image generation**:
  - Mobile-optimized interface
  - Cancel generation at any time with one-tap interrupt
  - WebSocket-based live updates showing step-by-step progress
  - Live preview images during generation (when supported by server)
  - Error notifications via Toast messages
- **Text-to-video generation**:
  - Generate AI videos with customizable parameters
  - High/low noise UNET and LoRA model selection
  - Live preview during generation
  - In-loop video playback with center-crop scaling
  - Save videos to device gallery or share
- **Inpainting**:
  - Upload source images for selective inpainting
  - Intuitive mask painting with adjustable brush size
  - Mask inversion and clearing tools
  - Feathered mask edges for smooth blending
  - Megapixels-based sizing for checkpoint workflows
  - WebSocket-based live updates showing step-by-step progress
  - Live preview images during generation (when supported by server)
  - Error notifications via Toast messages
- **Image/Video preview**:
  - Tap to view fullscreen with pinch-to-zoom (images) or play (videos)
  - Long press for save/share options
- **Gallery**:
  - View all generated images and videos with 2-column grid layout
  - Video indicator on thumbnails
  - Pull-to-refresh to update gallery
  - Delete individual items from server history
- **Media management**: Save to device gallery (Pictures/ComfyChair or Movies/ComfyChair), save as file, or share
- **Server configuration**:
  - View detailed server information (ComfyUI version, OS, Python, PyTorch versions)
  - Monitor hardware resources (RAM and GPU VRAM usage with free/total display)
  - Server management actions (clear queue, clear history)
- **App management**:
  - Clear local cache (generated images, videos, source images, masks)
  - Restore default settings
- **Configuration persistence**: Automatically saves and restores all settings including prompts, models, workflow selections, and generation parameters
- **Persistent navigation**: Bottom navigation bar for seamless switching between screens
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
│   ├── TextToImageFragment.kt       # Text-to-image generation screen
│   ├── TextToVideoFragment.kt       # Text-to-video generation screen
│   ├── InpaintingFragment.kt        # Inpainting screen with mask editor
│   ├── GalleryFragment.kt           # Image/video gallery screen
│   ├── ConfigurationFragment.kt     # Server configuration and management
│   ├── GalleryAdapter.kt            # RecyclerView adapter for gallery grid
│   ├── MaskPaintView.kt             # Custom view for mask painting
│   ├── ComfyUIClient.kt             # API client for ComfyUI server
│   ├── WorkflowManager.kt           # Workflow JSON management
│   └── SelfSignedCertHelper.kt      # SSL certificate handling
├── res/
│   ├── layout/                      # UI layouts
│   │   ├── activity_main.xml        # Login screen layout
│   │   ├── activity_main_container.xml  # Container with bottom navigation
│   │   ├── fragment_text_to_image.xml   # Text-to-image screen layout
│   │   ├── fragment_text_to_video.xml   # Text-to-video screen layout
│   │   ├── fragment_inpainting.xml  # Inpainting screen layout
│   │   ├── fragment_gallery.xml     # Gallery screen layout
│   │   ├── fragment_configuration.xml   # Configuration screen layout
│   │   ├── bottom_sheet_config.xml  # Text-to-image configuration panel
│   │   ├── bottom_sheet_video_config.xml  # Text-to-video configuration panel
│   │   ├── bottom_sheet_inpainting_config.xml  # Inpainting configuration panel
│   │   ├── bottom_sheet_save_options.xml  # Save/share options
│   │   ├── bottom_sheet_source_image.xml  # Source image options
│   │   ├── dialog_mask_editor.xml   # Mask painting dialog
│   │   ├── dialog_fullscreen_image.xml    # Fullscreen image viewer
│   │   ├── dialog_fullscreen_video.xml    # Fullscreen video player
│   │   └── item_gallery_thumbnail.xml     # Gallery thumbnail item
│   ├── raw/                         # Workflow JSON files
│   │   ├── tti_checkpoint_default.json  # Default text-to-image checkpoint workflow
│   │   ├── tti_unet_zimage.json         # Z-Image text-to-image UNET workflow
│   │   ├── iip_checkpoint_default.json  # Default inpainting checkpoint workflow
│   │   ├── iip_unet_zimage.json         # Z-Image inpainting UNET workflow
│   │   └── ttv_unet_wan22_lightx2v.json # WAN 2.2 text-to-video UNET workflow
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
