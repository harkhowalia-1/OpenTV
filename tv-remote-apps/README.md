# Otv Remote (iPhone) and OpenTV Companion (Mac)

Companion apps for OpenTV (Otv), the TV running the OtvOS shell. Both find the TV over Bonjour (`_tvremote._tcp`), pair once
with the 4-digit code the TV shows, and keep a live connection (port 8801) that reconnects by itself when the
TV sleeps, restarts or changes address. Any number of devices can be connected at once.

- **Otv Remote** on iPhone (`TVRemote` target, iOS 18+): touch surface, buttons, typing; the iPhone's volume buttons change the TV's
  volume while the app is open. `TVRemoteControls` adds Control Center / Lock Screen controls (TV Remote,
  Play/Pause, Power, Volume Up/Down, Mute, Home) and Siri / Shortcuts actions. `TVRemoteShare` adds
  "Send to TV" to the Share sheet (YouTube, Netflix, Spotify, video links, web pages).
- **OpenTV Companion** on the Mac (`TVRemoteMac` target, macOS 14+): lives in the menu bar (the TV icon with "Otv").
  - Remote, with arrow keys, Return, Esc, Space, H, C, M and +/- while it's open.
  - **TV as Display**: Extend (a virtual display at the TV's resolution, placed above the Mac's screen),
    Mirror, or iPhone (streams the iPhone Mirroring window; the TV remote drives it). H.264 from the media
    engine in low-latency mode; the TV decodes in hardware. Needs Screen Recording permission.
  - **Pointer onto the TV**: push the cursor past the top of the screen to use the TV with the Mac's mouse and
    keyboard; hovering selects, clicking opens, two-finger scrolling (or click-dragging) moves through rows
    and shelves; a two-finger click or tap is Home. ⌘Esc or moving back down returns. Needs Accessibility.
  - **Send to TV** (⌘T in the menu): the open tab of Safari, Chrome, Edge, Brave, Arc, Vivaldi, Opera
    (Apple Events) or Firefox and other browsers (Accessibility), or the file playing in QuickTime Player or
    VLC, which this Mac serves to the TV. YouTube and local files carry on at the same moment.
  - **USB**: when the Chromecast is plugged into the Mac and adb is installed, everything goes over the cable.

Build: `xcodegen generate`, then open `TVRemote.xcodeproj` (team 67439D6VU8, automatic signing).
