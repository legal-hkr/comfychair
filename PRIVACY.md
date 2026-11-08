# Privacy Policy

**Effective Date**: November 8, 2026
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
- **Generated Media and Sources**: Controlled by the "Media storage" setting, which applies equally to the source images and videos you pick. By default ("Memory only") generated images and videos are kept only in the phone's RAM and are never written to its storage; switching to this mode deletes any media stored earlier. The other options keep the latest results on the device when the app goes to the background ("Memory, saved on exit") or store them as they arrive ("On the device"). When you share a result, a copy is written to the app's private cache for the receiving app and deleted the next time ComfyChair starts. Media you save to the gallery is managed by you
- **Debug Log**: Kept in memory only while debug logging is enabled. Prompts, file names, job IDs, server addresses, workflow names and the titles you give to groups, notes and nodes are recorded as placeholders; node values, server responses and error messages (which can quote addresses, paths or prompts) are not recorded at all, so an exported log does not reveal them
- **Custom Workflows**: Any workflows you import into the app, from a file or from the workflows saved on your ComfyUI server. The server's list of saved workflows is only shown, not stored; the workflow you pick is kept in memory until you finish importing it. A workflow you export in one of ComfyUI's formats carries the values last used with it on the current server (the negative prompt and other settings, not the positive prompt)
- **Queue**: The IDs of your queued jobs and a random client ID for each server, so jobs you submitted are still recognized as yours after the app restarts. Descriptions of your queued jobs (prompt and workflow) shown in the queue are kept in memory only

All of this data remains on your device and is never transmitted to the developer or any third party.

### Data Sent to Your ComfyUI Server

When you use ComfyChair, the app communicates directly with your configured ComfyUI server. The following data is sent to your server:

- Text prompts for image/video generation
- Generation parameters (dimensions, steps, seed, etc.)
- Source images for image-to-image or image-to-video generation, and source videos for video-to-video generation
- Workflow configurations

**This data is sent only to the server you configure.** ComfyChair does not route your data through any intermediary servers.

### What Your Server Shares

ComfyUI keeps one history, one input folder and one output folder for everyone who uses the server: they hold the work of every client, the web interface included, and ComfyUI shows them to any client that connects. ComfyChair shows what the server shares in the same way: the gallery shows the server's history, and the source picker of the generation screens lists the images and videos in the server's input folder, so a file already there can be used without uploading it again. ComfyUI also reports every client's queued jobs with their workflows; ComfyChair's queue shows other clients' jobs with their prompt and position, without letting you change them. None of this is sent anywhere else, and none of it is logged; it's kept on the device only as the "Media storage" setting allows.

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
- Copied generation metadata and error details are marked as sensitive, so Android does not show them in the clipboard preview
- In "Memory only" mode the app is hidden from the recents screen preview, so the system does not store a screenshot of generated media

## Data Backup

ComfyChair opts out of Android's cloud backup, so none of its data (prompts, server addresses, settings, credentials or media) is uploaded to your Google account. When you move to a new phone with Android's device-to-device transfer, app settings are copied directly between the two devices; saved credentials and media are not, and you will need to sign in to your servers again. To move everything deliberately, use the backup export in the app's settings. Backup files contain your servers, settings, workflows and saved prompts, but no passwords, tokens or sign-in cookies: you enter those again after restoring.

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
