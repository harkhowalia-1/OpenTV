# Publishing Otv Remote 1.3.2

Done and ready:

- [x] Signed App Store build, version 1.3.2 (1): `tv-remote-apps/build/appstore/` (the archive and the exported IPA; not in git)
- [x] Privacy manifests for the app and both extensions, export compliance (`ITSAppUsesNonExemptEncryption = NO`)
- [x] Listing text, keywords, review notes and privacy answers: [Listing.md](Listing.md)
- [x] Eight screenshots at 6.9" and 6.3": [Screenshots-6.9in/](Screenshots-6.9in), [Screenshots-6.3in/](Screenshots-6.3in)
- [x] Privacy policy (https://harkhowalia-1.github.io/OpenTV/privacy.html), support and marketing pages
- [x] Builds contain no folder names, device details or other personal information

Before you submit, decide on these (each can get the app rejected):

1. **The icon.** A silver remote on black looks a lot like Apple's own Apple TV Remote app icon. Review can reject apps
   that look like Apple's (guideline 5.2.5). A distinct icon, for example the OpenTV gradient screen with a small
   remote, is the safe choice.
2. **A demo video.** Reviewers don't have a TV with OpenTV, so attach a 1–2 minute screen recording (iPhone screen
   recording plus the TV in view, or side by side) to the review notes (guideline 2.1).
3. **Other companies' logos.** Screenshots 1, 2, 5 and 7 show the TV's home row with Netflix, YouTube, Prime Video,
   Xbox and Spotify tiles. Usually fine for a remote app, but if Review objects, re-capture with those tiles moved off
   the first row.

Your steps (about 20 minutes):

1. **Create the app.** [App Store Connect](https://appstoreconnect.apple.com) › Apps › **+** › New App, with the values in
   [Listing.md](Listing.md).
2. **Upload the build.** Drag `tv-remote-apps/build/appstore/TVRemote.ipa` into Apple's free **Transporter** app and
   press Deliver. (This IPA has no symbol files, so your Mac's folder names stay off Apple's servers too. If you use
   Xcode › Window › Organizer › *Otv Remote 1.3.2 (1)* › Distribute App instead, untick "Upload your app's symbols".)
   It shows up under TestFlight after about 15 minutes.
3. **Fill in the listing** from Listing.md, drag in the eight 6.9" screenshots in order, and pick the build.
4. **App Privacy:** "No, we do not collect data."
5. **Test once on TestFlight** on your iPhone: pair, swipe, type, change the wallpaper.
6. **Add for Review › Submit**, with the demo video attached. Review usually takes 1–2 days.

For the next update, raise `CURRENT_PROJECT_VERSION` (every upload needs a new build number).
