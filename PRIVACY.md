# Privacy Policy

**Effective Date**: December 28, 2025
**App**: ComfyChair
**Developer**: Legal HKr

## Overview

ComfyChair is an open-source Android application that provides a mobile interface for ComfyUI servers. This privacy policy explains how the app handles your data.

## Data Collection

**ComfyChair does not collect, store, or transmit any personal information to the developer or any third parties.**

### Data Stored Locally on Your Device

The app stores the following data locally on your device:

- **Connection Settings**: Server hostname and port for your ComfyUI server
- **App Preferences**: UI settings such as live preview toggle, cache settings, and debug logging
- **Generation Parameters**: Your workflow selections, prompts, and generation settings (dimensions, steps, CFG, etc.)
- **Generated Media**: Images and videos generated through the app are cached locally
- **Custom Workflows**: Any workflows you import into the app

All of this data remains on your device and is never transmitted to the developer or any third party.

### Data Sent to Your ComfyUI Server

When you use ComfyChair, the app communicates directly with your configured ComfyUI server. The following data is sent to your server:

- Text prompts for image/video generation
- Generation parameters (dimensions, steps, seed, etc.)
- Source images for image-to-image or image-to-video generation
- Workflow configurations

**This data is sent only to the server you configure.** ComfyChair does not route your data through any intermediary servers.

## Third-Party Services

ComfyChair does not integrate any third-party services such as:

- Analytics or tracking services
- Advertising networks
- Crash reporting services
- Social media SDKs

## Data Security

- All local data is stored using Android's private storage mechanisms
- Network communication supports both HTTP and HTTPS protocols
- Self-signed certificates are supported for local server deployments (with user notification)

## Data Backup

If you use Android's backup feature, your app settings may be included in device backups. You can disable this in your device settings if desired.

## Children's Privacy

ComfyChair does not knowingly collect any information from children under 13 years of age.

## Changes to This Policy

We may update this privacy policy from time to time. Any changes will be reflected in the "Effective Date" at the top of this document and in the app's repository.

## Contact

If you have questions about this privacy policy, please open an issue at:
https://github.com/legal-hkr/comfychair/issues

## Open Source

ComfyChair is open-source software licensed under GPL-3.0. You can review the complete source code at:
https://github.com/legal-hkr/comfychair
