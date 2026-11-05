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
The remote for OpenTV: swipe, tap and type from the couch, set live wallpapers and screen savers, and sign the TV in to Xbox Cloud Gaming. OpenTV is free.

**Description**
Your iPhone. Your TV remote.

Otv Remote is the remote control for OpenTV, the free and open-source home screen that turns your TV into a calm, beautiful place for everything you watch and play. Pair once and control it all from the couch.

A REMOTE THAT FEELS RIGHT
• Swipe to move around, tap to select
• Back, Home, play and pause, mute and skip
• Your iPhone's volume buttons change the TV's volume while the app is open
• Power: sleep and wake the TV

CONTROL CENTER, ONE PRESS AWAY
• Power, Wi-Fi, Bluetooth, volume, sleep timer and screen saver
• AirPlay, Apple Home, Game Mode and Close Background Apps
• Remote controls on your Lock Screen and in Control Center, plus Siri and Shortcuts

A TRACKPAD AND A KEYBOARD
• Open a web page on the TV and the remote turns into a trackpad by itself
• Type in search boxes and sign-in fields with the keyboard you already know

LIVE WALLPAPERS AND SCREEN SAVERS
• Aurora and Underwater: scenes drawn live by the TV, never the same twice
• Slow-motion aerials, desktop pictures, Bing's picture of the day or your own photos
• Screen savers that match your wallpaper, or pick their own

SET UP THE TV FROM YOUR PHONE
• Wallpaper and screen saver, applied at once
• Inputs, Bluetooth accessories and software updates

XBOX CLOUD GAMING
• Sign the TV in with your saved passwords on the iPhone; the session reaches the TV encrypted, end to end
• Your gamertag, friends and games on the TV, with the remote as a controller

ALWAYS AT HAND
• Send to TV from the Share sheet: videos, songs and web pages open on the TV
• Pair once with a code; it reconnects by itself, and the whole family can connect

PRIVATE BY DESIGN
• No account, no tracking, nothing collected. The app talks only to your TV, over your own Wi-Fi.

GET OPENTV, FREE
Otv Remote needs a TV running OpenTV. It's free and open source: install it from a Mac with OpenTV Installer, straight from the TV, or with a command line. Everything you need is at harkhowalia-1.github.io/OpenTV.

**Keywords (100)**
tv remote,remote control,trackpad,keyboard,smart tv,live wallpaper,screensaver,opentv,couch,airplay

*(No other companies' brands in keywords, and no other platforms named in the screenshots or name: Apple rejects both.)*

**Support URL** https://github.com/harkhowalia-1/OpenTV/issues
**Marketing URL** https://harkhowalia-1.github.io/OpenTV/

**What's New**
First release on the App Store.

## Screenshots

Eight, in this order, each with OpenTV's name along the bottom:

1. Remote: "Your iPhone. Your TV remote."
2. Control Center: "Everything, one press away."
3. Trackpad: "Point and click on the big screen."
4. Keyboard: "Type with the keyboard you know."
5. Live Wallpapers: "Scenes drawn live. Chosen in a tap."
6. TV Settings: "Set up the TV from your phone."
7. Xbox Cloud Gaming: "Sign in on your phone. Play on your TV."
8. Get OpenTV: "Pair once. You're all set." (with the website)

- iPhone 6.9" (required): [Screenshots-6.9in/](Screenshots-6.9in), 1320 × 2868.
- iPhone 6.3": [Screenshots-6.3in/](Screenshots-6.3in), 1206 × 2622 (optional; App Store Connect scales the 6.9" ones otherwise).

Remake them with `swift make-screenshots.swift appstore Screenshots-6.9in 1320 2868` (and `… Screenshots-6.3in 1206 2622`).
The raw captures in `Captures/` stay on this Mac (git ignores them); personal details in them are replaced with
placeholders by the script (profile "A", "Home Wi-Fi", a sample network address).

## App Review information

**Sign-in required:** No.

**Notes for the reviewer** (attach a screen recording, see the checklist):

Otv Remote is the companion remote for OpenTV, a free, open-source home screen for TVs running Android TV and Google TV (https://github.com/harkhowalia-1/OpenTV). It needs a TV running OpenTV on the same Wi-Fi network, so the attached video shows the app controlling a TV: pairing with the code shown on the TV, the touch remote, the automatic trackpad for web pages, typing, and changing the TV's wallpaper and screen saver.

The optional "Sign In to Xbox Cloud Gaming" opens Microsoft's own sign-in page in a private web view, so the user can use their saved passwords; the resulting session is encrypted on the device (ECDH P-256 + AES-GCM) and sent only to the user's TV on the local network. Nothing is sent to us; the app collects no data.

The app uses only standard encryption provided by iOS (CryptoKit, HTTPS), so ITSAppUsesNonExemptEncryption is set to NO.
