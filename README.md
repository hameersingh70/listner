# Call Monitor — Headless Whitelist Call Audio Recorder

A headless Android background application that automatically records microphone audio during cellular phone calls, but **ONLY for calls involving a pre-configured whitelist of phone numbers**.

Runs completely in the background, requires zero user interaction after first launch, and shows **ZERO popups, ZERO toasts, and ZERO dialogs**. No root required.

---

## 📋 Features

- **Automatic Call Detection:** Handles both incoming and outgoing calls.
- **Whitelist Matching:** Normalizes phone numbers (stripping country codes, spaces, dashes and keeping the last 10 digits) and checks against a local whitelist in O(1) time.
- **Microphone Audio Recording:** Captures the user's microphone during the call using native `MediaRecorder.AudioSource.MIC` in MPEG-4 / AAC format (44.1 kHz, 64 kbps, Mono).
- **Anti-Popup & Silent:** Uses a silent, minimal-importance notification channel (`IMPORTANCE_MIN`). Never launches activities or toasts during calls.
- **Survives Reboots:** Restarts automatically on `BOOT_COMPLETED`.

---

## ⚙️ 1. How to Edit the Whitelist

### Option A: Via the Minimal App Interface
1. Open the **Call Monitor** app on your phone.
2. Under **"Whitelisted Numbers"**, type any phone number (e.g., `9876543210` or `+91 98765-43210`) into the field and tap **Add**.
3. It will be automatically normalized to its 10-digit format and added to the whitelist.
4. To remove a number, **long-press** on any number in the list.

### Option B: Via ADB Command Line
You can also inject whitelisted numbers directly into the app's `SharedPreferences` from your computer:
```bash
adb shell 'am broadcast -a com.recall.app.action.ADD_NUMBER --es number "9876543210"'
```

---

## 📁 2. Where Recordings Are Saved

Recordings are saved to the app's private external storage directory:
```text
/sdcard/Android/data/com.recall.app/files/Recordings/
```

### Filename Format
```text
<normalized_number>_<epoch_timestamp>.m4a
```
*Example:* `9876543210_1729584321000.m4a`

### Pulling Recordings to Computer
```bash
# Pull all call recordings to your current computer directory
adb pull /sdcard/Android/data/com.recall.app/files/Recordings/ .
```

---

## 🔋 3. Battery Optimization Settings (Crucial for Xiaomi / Poco / MIUI / HyperOS)

To ensure Android and MIUI never kill the background recording service:

1. **Battery Optimization:**
   - In the app, tap **"Disable Battery Optimization"** and select **Allow** (sets app to *Unrestricted*).
2. **MIUI / HyperOS Autostart:**
   - Go to phone **Settings → Apps → Permissions → Autostart** (or *Background Autostart*).
   - Turn **ON** Autostart for **Call Monitor**.
3. **MIUI Battery Saver:**
   - Go to phone **Settings → Apps → Manage Apps → Call Monitor → Battery Saver**.
   - Select **No restrictions**.
4. **Lock in Recents:**
   - Open Recent Apps screen on phone, long-press the **Call Monitor** card, and tap the **Lock icon** 🔒.

---

## 🏗️ Architecture

1. **`CallStateReceiver` (`BroadcastReceiver`):** Listens to `android.intent.action.PHONE_STATE`, reads `EXTRA_INCOMING_NUMBER`, normalizes, and triggers recording start/stop.
2. **`CallRecorderService` (`ForegroundService`):** Owns `MediaRecorder` with `AudioSource.MIC`, saves `.m4a` files, runs with `foregroundServiceType="microphone"`, and maintains dynamic telephony callbacks on Android 12+.
3. **`BootReceiver` (`BroadcastReceiver`):** On `BOOT_COMPLETED`, launches `CallRecorderService` to ensure phone monitoring is active after restart.
4. **`PhoneNumberStore`:** SharedPreferences-backed whitelist providing $O(1)$ lookups and phone number normalization.
5. **`CallLogHelper`:** Asynchronously resolves outgoing call destination numbers via `CallLog.Calls`.
