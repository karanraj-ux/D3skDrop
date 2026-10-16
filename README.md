# DeskDrop ⚡
> **Ultra-Fast, Zero-Install Local File Sharing & Media Streaming between Mobile & Desktop (PC/Mac/Linux).**

![Android](https://img.shields.io/badge/Platform-Android_14+-3DDC84?logo=android&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose_M3-4285F4?logo=jetpackcompose&logoColor=white)
![Ktor](https://img.shields.io/badge/Server-Ktor_Netty-087CFA?logo=kotlin&logoColor=white)
![Build](https://img.shields.io/badge/Build-Passing-brightgreen)
![License](https://img.shields.io/badge/License-Apache_2.0-blue)

DeskDrop turns your Android phone into a high-speed local file hub. Connect any **Windows PC, Mac, Linux laptop, iPad, or another phone** in seconds simply by opening a browser link or scanning a QR code. **No software installation on your computer, no cloud accounts, no cables, and no internet required.**

---

## 🌟 Key Features

### 🖥️ Zero-Install Desktop Experience
* **No `.exe` or `.dmg` to install:** Open Chrome, Edge, Safari, or Firefox on your computer and navigate to `http://deskdrop.local:8080` or your phone's local IP.
* **Drag-and-Drop Batch Uploads:** Drop gigabytes of 4K videos, zip files, or documents directly into the desktop dashboard.
* **5MB Chunked Engine:** Resilient multi-chunk transfers with live upload speed (MB/s) and progress indicators.

### ⚡ Blazing Fast & 100% Offline (No Wi-Fi Needed)
* **Hardware LAN Speeds:** Transfers files locally at **50–100 MB/s**, limited only by your Wi-Fi hardware.
* **Hotspot Mode (Airplane / Travel):** Turn on your phone's Personal Hotspot and connect your laptop. DeskDrop works seamlessly with **0 bytes of mobile data consumed**.

### 🎵 In-Browser Media Streaming (Instant Jukebox)
* **Stream Without Downloading:** Watch videos or listen to music stored on your phone directly in browser tabs via HTTP range streaming (`206 Partial Content`).
* **Remote Control ("📱 Play on Phone"):** Trigger native video or audio playback on the mobile phone directly from your PC.

### 📋 Bi-Directional Clipboard Sync
* Push text, URLs, notes, and 2FA tokens from your PC to your phone in 1 click, or fetch text from your phone directly into your computer's clipboard.

### 📱 Modern Android App (Material 3)
* **Two-Way Sharing:** Tap **`+ Send Files to PC`** to pick photos, videos, APKs, or documents from your phone and make them instantly accessible on your desktop.
* **Real Hardware Storage Meter:** Queries low-level Linux `android.os.StatFs` for exact physical flash storage remaining (e.g. `48.2 GB Free of 128.0 GB`).
* **Live File Categorization:** Filter shared files instantly by **All**, **Videos**, **Audio**, **APKs**, and **Docs**.
* **Built-in Media Player:** ExoPlayer-powered continuous audio and video playback with loop support.

---

## 🚀 How to Use

### 1. Same Wi-Fi Network
1. Open **DeskDrop** on your Android phone.
2. Ensure both your phone and computer are on the same Wi-Fi router.
3. Open your browser on PC/Mac and enter:
   ```
   http://deskdrop.local:8080
   ```
   *(or the numeric IP shown on the phone screen, e.g., `http://192.168.1.45:8080`)*.
4. Drag and drop files to send to your phone, or download files from your phone to your PC!

### 2. Offline / Hotspot Mode (No Router / No Internet)
1. On your Android phone, turn on **Personal Hotspot** (mobile data can be turned OFF).
2. Connect your PC/Mac or laptop to your phone's Wi-Fi hotspot.
3. Open `http://192.168.43.1:8080` in your computer's browser.
4. Transfer files at maximum local wireless speed!

---

## 🛠️ Architecture & Tech Stack

| Layer | Technology |
| :--- | :--- |
| **Language** | Kotlin 100% |
| **UI Framework** | Jetpack Compose + Material Design 3 (M3) |
| **Local Web Server** | Ktor Server (Netty Engine) with Partial Content & AutoHead |
| **Media Engine** | AndroidX Media3 / ExoPlayer |
| **Local Discovery** | Network Service Discovery (mDNS / ZeroConf) |
| **Storage Engine** | Persistent Android External App Storage + Linux `StatFs` |
| **QR Code Engine** | ZXing (Zebra Crossing) |
| **Background Resilience**| Android Foreground Service + WakeLock & WifiLock |
| **Optimization** | Android R8 Full Mode shrinking & resource minimization |

---

## 📦 Building & Launch Testing

### Prerequisites
* Android Studio Hedgehog or newer
* JDK 17
* Android SDK 34+

### Clone & Compile
```bash
git clone https://github.com/your-username/deskdrop.git
cd deskdrop

# Run unit tests
./gradlew testDebugUnitTest

# Build Debug APK
./gradlew assembleDebug

# Build Release APK & Bundle with R8 optimization
./gradlew assembleRelease bundleRelease
```

### Generated Artifacts
* **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk`
* **Release APK:** `app/build/outputs/apk/release/app-release.apk`
* **Google Play Bundle:** `app/build/outputs/bundle/release/app-release.aab`

---

## 🛡️ R8 & Proguard Optimization

DeskDrop is pre-configured with complete R8 shrinking and resource minimization rules (`app/proguard-rules.pro`):
* Retains necessary Netty unsafe channels and dynamic reflection classes.
* Preserves Ktor server plugin hooks and routing reflection.
* Retains Media3/ExoPlayer decoders and ZXing matrix generators.
* Strips unused Android classes, keeping the final APK lightweight and launch-ready.

---

## 📄 License
```
Copyright 2026 DeskDrop Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```
