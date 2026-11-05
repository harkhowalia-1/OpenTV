# Otv Remote: App Store listing

Everything to paste into App Store Connect. iPhone only, bundle ID `com.harkhowalia.tvremote` (with the
extensions `.controls` and `.share`), free.

## New app record

| Field | Value |
|---|---|
| Platform | iOS |
| Name (30) | Otv Remote *(if taken: "Otv Remote for OpenTV")* |
| Primary language | English (U.S.) |
| Bundle ID | com.harkhowalia.tvremote |
| SKU | otv-remote-ios |
| User access | Full access |

## App information

| Field | Value |
|---|---|
| Subtitle (30) | Remote control for OpenTV |
| Category | Utilities *(secondary: Entertainment)* |
| Content rights | Doesn't contain, show or access third-party content *(the TV shows it, not the app)* |
| Age rating | Answer **None / No** to every question, including Unrestricted Web Access (the only web page is Microsoft's sign-in) → **4+** |
| Privacy policy URL | https://harkhowalia-1.github.io/OpenTV/privacy.html |
| Copyright | © 2026 Harkhowalia |

## App Privacy

Data collection: **"No, we do not collect data from this app."** → **Data Not Collected**.
(Everything goes from the iPhone to the user's own TV on their network. The Xbox sign-in goes to Microsoft
and, encrypted, to the user's TV; never to us.)

## Pricing and availability

Free, all countries and regions.

## Version 1.3.2

**Promotional text (170)**
Swipe, tap and type to control OpenTV from the couch. Your iPhone becomes a trackpad for web pages, and sets up the TV's wallpaper, screen saver and more.

**Description**
Your iPhone. Your TV remote.

Otv Remote is the remote control for OpenTV, the free and open-source home screen for TVs running Android TV and Google TV. Pair once and control everything from the couch.

A REMOTE THAT FEELS RIGHT
• Swipe to move around, tap to select
• Back, Home, Control Center, play and pause, mute and skip
• Your iPhone's volume buttons change the TV's volume while the app is open
• Power: sleep and wake the TV

A TRACKPAD AND A KEYBOARD
• Open a web page on the TV and the remote turns into a trackpad by itself
• Type in search boxes and sign-in fields with the keyboard you already know

SET UP THE TV FROM YOUR PHONE
• Wallpaper: aerials, desktop pictures, Bing's picture of the day or your own photos, applied at once
• Screen saver, inputs, Bluetooth accessories and software updates
• Sign the TV in to Xbox Cloud Gaming with your saved passwords; the session reaches the TV encrypted, end to end

ALWAYS AT HAND
• Controls for Control Center and the Lock Screen
• Siri and Shortcuts actions
• Send to TV from the Share sheet: videos, songs and web pages open on the TV

PRIVATE BY DESIGN
• No account, no tracking, nothing collected. The app talks only to your TV, over your own Wi-Fi.

Requires a TV with OpenTV installed. OpenTV is free at harkhowalia-1.github.io/OpenTV and installs from a Mac, from the TV itself or with adb.

**Keywords (100)**
tv remote,remote control,trackpad,keyboard,smart tv,television,couch,wallpaper,screensaver,opentv

*(No brand names in keywords: Apple rejects other companies' trademarks there.)*

**Support URL** https://github.com/harkhowalia-1/OpenTV/issues
**Marketing URL** https://harkhowalia-1.github.io/OpenTV/

**What's New**
First release on the App Store.

## Screenshots

- iPhone 6.9" (required): [Screenshots-6.9in/](Screenshots-6.9in), 1320 × 2868, in order 1 to 5.
- iPhone 6.3": [Screenshots-6.3in/](Screenshots-6.3in), 1206 × 2622 (optional; App Store Connect scales the 6.9" ones otherwise).

Remake them with `swift make-screenshots.swift appstore Screenshots-6.9in 1320 2868` (and `… Screenshots-6.3in 1206 2622`).
The raw captures in `Captures/` stay on this Mac (git ignores them); personal details in them are blurred by the script.

## App Review information

**Sign-in required:** No.

**Notes for the reviewer** (attach a screen recording, see the checklist):

Otv Remote is the companion remote for OpenTV, a free, open-source home screen for TVs running Android TV and Google TV (https://github.com/harkhowalia-1/OpenTV). It needs a TV running OpenTV on the same Wi-Fi network, so the attached video shows the app controlling a TV: pairing with the code shown on the TV, the touch remote, the automatic trackpad for web pages, typing, and changing the TV's wallpaper and screen saver.

The optional "Sign In to Xbox Cloud Gaming" opens Microsoft's own sign-in page in a private web view, so the user can use their saved passwords; the resulting session is encrypted on the device (ECDH P-256 + AES-GCM) and sent only to the user's TV on the local network. Nothing is sent to us; the app collects no data.

The app uses only standard encryption provided by iOS (CryptoKit, HTTPS), so ITSAppUsesNonExemptEncryption is set to NO.
