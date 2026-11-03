# Privacy Policy

**Effective Date**: November 3, 2026
**App**: ComfyChair
**Developer**: Legal HKr

## Overview

ComfyChair is an open-source Android application that provides a mobile interface for ComfyUI servers. This privacy policy explains how the app handles your data.

## Data Collection

**ComfyChair does not collect, store, or transmit any personal information to the developer or any third parties.**

### Data Stored Locally on Your Device

The app stores the following data locally on your device:

- **Connection Settings**: Server names, hostnames and ports, and the protocol and certificate fingerprint you accepted for each server
- **Credentials**: Passwords, tokens and sign-in cookies for your servers, encrypted with a key kept in the Android Keystore
- **App Preferences**: UI settings such as live preview, media storage, and debug logging
- **Generation Parameters**: Your workflow selections, prompts, and generation settings (dimensions, steps, CFG, etc.)
- **Generated Media**: Controlled by the "Media storage" setting. By default ("Memory only") generated images and videos are kept only in the phone's RAM and are never written to its storage; switching to this mode deletes any media stored earlier. The other options keep the latest results on the device when the app goes to the background ("Memory, saved on exit") or store them as they arrive ("On the device"). When you share a result, a copy is written to the app's private cache for the receiving app and deleted the next time ComfyChair starts. Media you save to the gallery is managed by you
- **Debug Log**: Kept in memory only while debug logging is enabled. Prompts, file names, job IDs, server addresses and workflow names are recorded as placeholders, so an exported log does not reveal them
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
- Network communication supports both HTTP and HTTPS; HTTPS is tried first, and a server that used HTTPS is never switched to unencrypted HTTP without your consent
- Certificates not trusted by Android (self-signed or issued by a private CA) are accepted only after you confirm their SHA-256 fingerprint, and from then on only that certificate is accepted for the server
- Copied generation metadata is marked as sensitive, so Android does not show it in the clipboard preview
- In "Memory only" mode the app is hidden from the recents screen preview, so the system does not store a screenshot of generated media

## Data Backup

ComfyChair opts out of Android's cloud backup, so none of its data (prompts, server addresses, settings, credentials or media) is uploaded to your Google account. When you move to a new phone with Android's device-to-device transfer, app settings are copied directly between the two devices; saved credentials and media are not, and you will need to sign in to your servers again. To move everything deliberately, use the backup export in the app's settings.

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
