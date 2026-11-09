# <img src="metadata/en-US/icon.png" width="42"/> ComfyChair

An unofficial, native Android UI for [ComfyUI](https://github.com/comfyanonymous/ComfyUI).

**Current version**: v0.9.5

[<img src="https://raw.githubusercontent.com/rubenpgrady/get-it-on-github/refs/heads/main/get-it-on-github.png"
    alt="Get it on GitHub"
    height="80">](https://github.com/legal-hkr/comfychair/releases/latest)
[<img src="https://f-droid.org/badge/get-it-on.png"
    alt="Get it on F-Droid"
    height="80">](https://f-droid.org/packages/sh.hnet.comfychair)

## Overview

ComfyChair provides a streamlined mobile interface for interacting with ComfyUI servers, allowing you to generate and manage AI images and videos directly from your Android device. The app communicates with your ComfyUI server via its API, bringing the power of node-based AI generation to your mobile workflow.

**Note**: This is an independent, community-developed project and is not officially affiliated with or endorsed by the ComfyUI team.

## Screenshots

<img src="metadata/en-US/images/phoneScreenshots/1.png" width="200"/> <img src="metadata/en-US/images/phoneScreenshots/2.png" width="200"/> <img src="metadata/en-US/images/phoneScreenshots/3.png" width="200"/> <img src="metadata/en-US/images/phoneScreenshots/4.png" width="200"/> <img src="metadata/en-US/images/phoneScreenshots/5.png" width="200"/> <img src="metadata/en-US/images/phoneScreenshots/6.png" width="200"/> <img src="metadata/en-US/images/phoneScreenshots/7.png" width="200"/> <img src="metadata/en-US/images/phoneScreenshots/8.png" width="200"/>

## Features

### General

- **Material 3 Expressive design**: Image, Video, Gallery and Settings tabs, each generation screen keeping its own state; Back retraces every step with the predictive back animation; tapping the selected tab again shows it as when first opened (Gallery and Settings back at their top, Image and Video on their Text source)
- **First start**: A short welcome offers to add a server or restore a backup; restored servers that need a password or token say so, and Connect opens their form to save and connect
- **Multi-server support**: Save and manage multiple ComfyUI server connections with per-server settings and optional auto-login
- **Server connection**: Connect to remote or local ComfyUI servers over HTTPS or HTTP, with HTTPS tried first
- **Certificate trust**: Certificates trusted by Android are accepted as usual; self-signed or private-CA certificates are accepted after you confirm their SHA-256 fingerprint, and from then on only that certificate is accepted for the server. A server that used HTTPS is never switched to unencrypted HTTP without asking
- **Authentication**: HTTP Basic Auth, API key/Bearer token, and browser-based SSO sign-in for servers behind reverse proxies
- **Offline mode**: Browse what is stored on the device without a network connection, offered automatically when the server can't be reached
- **Queue**: Submit several jobs without waiting. A chip in the top bar counts your jobs and other clients' jobs apart; the queue sheet lists your running and waiting jobs with cancel, move to front and remove, shows other clients' jobs with their prompt and position (without changing them), and can clear the queue
- **Dual workflow support**:
  - **Checkpoint mode**: Traditional CheckpointLoaderSimple workflows
  - **UNET mode**: Modern diffusion workflows (Flux, Z-Image, etc.) with separate UNET, VAE, and CLIP model selection
- **LoRA chain support**:
  - Add up to 5 LoRAs per chain with individual strength from -100 to 100 (coarse stepper plus fine slider)
  - Text to Image and Image to Image: Separate LoRA chains for Checkpoint and UNET modes
  - Video workflows: Separate High Noise and Low Noise LoRA chains
  - LoRAs are dynamically injected into workflows at generation time, including workflows with non-core model loaders such as GGUF
- **Prompt Library**:
  - Save prompts as reusable presets with custom names and tags
  - Search by name, prompt text or tags, with Favorites and the most used tags as filters under the search field and the match shown in bold
- **What's new**: This version's release notes under Settings -> About, and a one-time notice after an update
- **License**: The full GPL-3.0 text under Settings -> About, readable offline
- **Errors**: Every error appears as a snackbar; Show opens the whole error (the node that failed with the server's message and traceback, the inputs a server refused, the reason a connection failed) to read or copy
- **Localization**: Available in English (default), German, French, Polish, Spanish, and Chinese with per-app language selection
- **Native Android experience**: Built with Kotlin and Jetpack Compose with Material Design 3

### Generation

- **One layout for every mode**: the preview on top, a dock with the prompt, the workflow chip and parameter chips below it, and the Generate button
- **Prompt editor**: Tapping the prompt opens a full-screen editor with Positive and Negative tabs, the prompt library and its own Generate button
- **Parameter chips**: Seed, steps, size or megapixels, length and scale one tap away, with the same controls as the options sheet
- **Number fields**: − and + step along the values the workflow's node allows; from a mistyped value they go to the nearest allowed one
- **Options sheet**: Only the fields the workflow uses, in sections (workflow, quick, sampling, LoRA, models); large workflows get collapsible sections
- **Generate button**: Generate, or add to the front of the queue and cancel your running job from its menu
- **Progress**: A status card on the preview shows the step and elapsed time, with live preview images when the server sends them
- **Results**: Jobs with several results get a pager with a thumbnail strip; videos play inline, muted, with a play/pause and time control and a sound toggle; one menu on the result saves, shares, shows details and sends it to another screen as its source: an image to edit or animate, a video to edit in Video to Video, or its last frame to animate on from where it ends
- **Text to Image** and **Text to Video**: From a prompt, with High Noise / Low Noise UNET and LoRA selection for video
- **Sources**: The source button opens a sheet: from the device, or an image or video already in the server's input folder (newest first, with thumbnails and video lengths), which is used without uploading it again; a source the server already has is never uploaded twice
- **Image to Image**:
  - Two modes: **Editing** (transformation, with its own prompt and up to 2 reference images) and **Inpainting** (mask-based, with a mask editor with adjustable brush size and feathered edges)
  - The source and the result swap through a picture-in-picture thumbnail; the source has its own toolbar (replace, remove, mask tools)
- **Image to Video**: Animate a still image with adjustable length and frame rate
- **Video to Video**: Restyle, restore or upscale a video, from your files or from a result (up to 100 MB, kept like the results: in memory only by default); the source video plays in the preview and swaps with the result
- **Video length**: Checked against the frame grid of the workflow's own nodes (e.g. 4n+1 for Wan, 17n+5 for MiniMax H3), with no upper limit of the app's own
- **Validation feedback**: The inputs ComfyUI refused, and a warning when it skips outputs instead of silently delivering an incomplete result

### Media

- **Gallery**:
  - All generated images and videos in an adaptive grid with a collapsing top bar and pull to refresh; videos show their length
  - Select items with a long press for saving, sharing or deleting them together; the other results of the selected jobs are marked, as the server deletes whole jobs
- **Media viewer**:
  - Opens out of the tile or preview and returns into it; swipe down or use predictive back to close
  - Swipe between items, pinch to zoom, double tap to switch between fit and fill, single tap to hide the controls
  - Save, share, use as source (images, videos, and a video's frame shown or its last frame), details (generation parameters such as prompt, model, seed and steps) and delete
  - Videos play with sound, on a playback card with a seek slider the picture follows as you drag it, frame-by-frame stepping, play/pause, sound and a remembered loop toggle; it hides while a video plays and comes back when it ends
- **Undo**: Every deletion of media can be undone for a few seconds before it reaches the server
- **Media management**: Save to device gallery (Pictures/ComfyChair or Movies/ComfyChair) or share
- **Media storage**: Generated media is kept in memory only by default and never written to the device; optionally it can be saved on exit or stored on the device. In memory-only mode the app is also hidden from the recents screen preview

### Workflows

- **Workflow editor** in four modes: Graph (read-only, from a workflow's page), Node values (from the options sheet), Edit structure (your own workflows and new ones) and Map fields (import)
- **Node graph**:
  - Nodes and connections in ComfyUI's colors, adapted to the app's light and dark themes, with Bezier curves or Hermite splines
  - Zoomed out below about 40 %, nodes become blocks with their titles; tap one to zoom in to it
  - The selected node's connections are emphasized while the others recede
  - Notes and group labels lie above the wires; group areas are frosted glass, with other wires passing under them
  - Bypassed nodes look switched off, with a Bypassed badge on their title bar
  - Opens fitted into the free area of the screen, laid out for its proportions
- **Node values**: A bottom sheet with every editable input of a node (numeric steppers with range hints, switches, image previews, values linked from other nodes leading to their source, the inputs the generation screen fills in), its changed values marked and resettable one by one or all at once
- **Node management**:
  - Add nodes via a node browser: ComfyUI's own categories and extension packs as a tree (open folders stay pinned at the top) or every node by name; the search ranks matches by name, and deprecated and experimental nodes are marked
  - Delete, duplicate, bypass and rename nodes; clean up disconnected nodes
  - Tap any socket for its menu: Connect (compatible sockets are ringed while a connection waits), Add a node (the node browser filtered by compatible types) or Disconnect
  - Values (numbers, text, true/false) take links of their type, as in ComfyUI, and become values again when disconnected
  - Lists (sampler, scheduler, model names and the like) take links too, as ComfyUI's server accepts them: another node's list with the same options, any list for lists declared as COMBO, a switch or a wildcard output; text and numbers can't feed a list
  - A switch (If/Else Switch) takes any type and passes on the type that reaches it; nodes from API-format files show the sockets the file leaves out
  - With TalkBack, a node's sockets and a pending connection's targets are available as actions
  - Organize nodes into groups and add notes with Markdown support; groups are laid out apart like nodes
- **Subgraphs**: Kept as in ComfyUI, one definition shared by all its copies
  - A copy is a frame with an Open button; inside, the subgraph's Inputs and Outputs strips, and Back goes up a level
  - Make a subgraph of a selection, unpack one, rename it or a single copy, and add, rename or remove its inputs and outputs; a value or list linked inside to the Inputs strip becomes an input whose value the copy shows
  - Show, hide and order the values a copy shows, as ComfyUI's promoted values
- **Field mapping**: When importing, the app's fields are assigned one at a time on a card, with a list of all fields; several fields can come from one node; no field is required except the source image for image workflows, and workflows without a prompt (e.g. upscalers) are supported
- **Custom fields**: Any node value, inside subgraphs too, can become a named field of its own: shown in a Custom section of the options sheet with the control of its type and the node's range, checked before Generate and remembered per workflow; one field can fill several values. Built-in fields can be renamed for a workflow
- **Workflow management**:
  - Create workflows from scratch, import a file, or import a workflow saved on the ComfyUI server (Workflow › Save) from a searchable list
  - Supported formats:
    - ComfyUI (import/export) - the format of ComfyUI's Workflow › Save, with layout, groups, notes and subgraphs; widget values are read as ComfyUI lays them out, dynamic inputs and extensions' named values included; an export carries the values last used with the workflow, without the positive prompt
    - ComfyUI API (import/export) - workflow format used by the ComfyUI API
    - ComfyChair JSON (import/export) - preserves field mappings and app settings
  - A list of workflows with filters and a switch for each, so the generation screens offer only the ones you use; each workflow's page shows its graph and description
  - Prompts can be mapped to primitive String nodes, so one prompt can feed several text encoders
  - Video to Video workflows read the source with ComfyUI's LoadVideo or VideoHelperSuite's Load Video. VideoHelperSuite's node fails on a video without sound when its audio output is connected; LoadVideo with Get Video Components handles such videos
  - Built-in templates include SD, SDXL, Flux, HiDream, Qwen Image (generation and editing), Z-Image, Krea-2 (generation, editing, identity editing and LanPaint inpainting), SeedVR2 image and video upscaling, Wan 2.2, LTX-2 and MiniMax H3 (text, image and video to video with sound, full and Turbo LoRA)
  - Dynamic parameter constraints based on workflow node definitions; each workflow remembers its own generation settings

### Settings

- **Server**: ComfyUI, OS, Python and PyTorch versions, RAM and VRAM meters, freeing memory and unloading models, clearing the history
- **App**: Connection, generation, media and privacy, language and workflow editor pages; changes apply at once, without restarting the app
- **Data**: Backup and restore of the configuration (connections, workflow values, custom workflows, saved prompts; passwords and tokens are not included), also offered on the first start, clearing the cache, the debug log, and restoring the app to its installed state while keeping the servers

## Requirements

- Android 13 (API level 33) or higher
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
   git clone https://github.com/legal-hkr/comfychair.git
   cd comfychair
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
- ComfyUI server URL (e.g., `http(s)://192.168.1.100:8188`)
- Network access between your Android device and the ComfyUI server

## Tech stack

- **Language**: Kotlin 2.2.10
- **Min SDK**: Android 13 (API 33)
- **Target SDK**: Android 16 (API 36)
- **UI Framework**: Jetpack Compose with Material Design 3 (including Expressive components)
- **Video Playback**: Media3 ExoPlayer
- **Architecture**: MVVM with ViewModels and StateFlow
- **Navigation**: Jetpack Compose Navigation
- **Build system**: Gradle with Kotlin DSL

## Contributing

This project follows standard Android development practices:
- Kotlin coding conventions
- Jetpack Compose best practices
- Material Design 3 guidelines
- MVVM architecture pattern

## License

[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.en.html)

## Acknowledgments

- [ComfyUI](https://github.com/comfyanonymous/ComfyUI) - The powerful node-based UI this app interfaces with
- [Claude](https://claude.ai) by Anthropic - AI assistant that provided valuable support during the development process, helping with code implementation, architecture decisions, and documentation
