# Changelog

All notable changes to ComfyChair will be documented in this file.

## [v0.3.0] - 2025-12-09

### Added
- **Text-to-Video Generation**: New screen for generating AI videos with ComfyUI
  - High/low noise UNET and LoRA model selection for multi-stage video workflows
  - Configurable video dimensions, frame length (1-129), and FPS (1-120)
  - Live preview during generation
  - Center-crop video playback using TextureView with MediaPlayer
- **Gallery Video Support**: View, save, and share generated videos
  - Video detection across multiple ComfyUI output formats (videos, gifs, images arrays)
  - Video thumbnail extraction with placeholder fallback
  - Play button indicator on video thumbnails
  - Fullscreen video player dialog
  - Save videos to Movies/ComfyChair folder

### Changed
- Gallery now displays both images and videos from generation history
- Configuration screen clears video cache and TextToVideoFragment preferences
- Bottom navigation includes new "Text to video" tab

### Fixed
- Progress bar no longer fills to 100% immediately on single-step progress reports
- Video preview background properly hidden before playback starts

### Improved
- All hardcoded toast messages moved to strings.xml for localization support
- Consolidated duplicate string resources ("saved to gallery" variants)
- Cleaned up unused string resources

## [v0.2.1] - 2025-11-XX

### Added
- Live preview images during generation (requires ComfyUI preview support)
- Delete individual items from gallery/history
- Clear cache button in Configuration
- Restore defaults button in Configuration

### Changed
- Preview canvases show only placeholder icon when empty (removed background)

## [v0.2.0] - 2025-11-XX

### Added
- **Inpainting**: New screen for selective image regeneration
  - Source image upload with mask painting
  - Fullscreen mask editor with adjustable brush
  - Mask inversion and clearing tools
  - Feathered mask edges for smooth blending

### Changed
- Bottom navigation always shows text labels
- Workflow file naming convention updated (tti_, iip_ prefixes)

## [v0.1.0] - Initial Release

### Added
- Text-to-image generation with checkpoint and UNET workflows
- Server connection with HTTP/HTTPS auto-detection
- Self-signed certificate support
- Image gallery with save and share options
- Server configuration and management
