# SeriesGrabber

Android app prototype for TikTok drama link discovery and offline video organization.

## Version 0.2.0

- Added a TikTok public-page scanner that accepts a creator profile, series/collection page, or episode URL.
- The scanner scrolls the page to reveal lazy-loaded cards and collects public episode and series links exposed as page anchors.
- Selecting a detected series link scans that series page for its episode links.
- Retains the local video import, episode/ad/review suggestions, manual ordering, and Media3 MP4 merge/export workflow.

## Current limitations

- TikTok changes its page structure frequently. Discovery depends on the links exposed by the public web page and may return no results when TikTok requires sign-in, consent, or blocks the embedded browser.
- **Automatic episode downloading and one-link-to-finished-MP4 processing are not implemented yet.** The scanner currently discovers links; users still need locally available video files to use the merge workflow.
- The app does not bypass authentication, access controls, DRM, or platform restrictions. Use only videos you are authorized to download and combine.

## Build

Open the repository in Android Studio or run `gradle assembleDebug` with JDK 17 and Gradle 8.9.
