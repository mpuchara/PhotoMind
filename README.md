# PhotoMind

PhotoMind is a privacy-first Android photo search app. It builds a local AI index of the user's gallery and lets the user search with natural text such as `Maciek nad morzem`, `dzieci w lesie` or `zachód słońca`.

## v0.2 — automatic people + scenery

- Automatically refreshes the local gallery index when the app opens.
- Periodically refreshes the fast index while the phone is charging and the battery is not low.
- Detects faces locally with ML Kit Face Detection.
- Creates local FaceNet embeddings and groups recurring faces into person clusters.
- The user names a person once in the **People / Osoby** screen; that name becomes searchable across photos assigned to the cluster.
- Automatically enriches photos with local Gemini Nano Image Description on supported devices while PhotoMind is in the foreground.
- Keeps the existing fast local layers: ML Kit image labels, OCR, filename/folder metadata and optional custom tags.
- Search ranking prioritizes named people, then rich scene descriptions, then labels/OCR/metadata.
- Photos and face embeddings are not uploaded to a PhotoMind server.

### Why scene enrichment is progressive

ML Kit GenAI / AICore restricts GenAI inference to foreground use and applies device/app resource quotas. PhotoMind therefore keeps the basic index automatic in the background, while richer Gemini Nano scene descriptions are progressively generated whenever the app is open.

### Face model

PhotoMind downloads the FaceNet TFLite model once to app-private storage when face embeddings are first needed. The model source is the Apache-2.0 project `shubham0204/FaceRecognition_With_FaceNet_Android` and the app uses compatible 160×160 input / 128D embedding preprocessing. The downloaded model is code/model data only; user photos are not sent to that source.

## Build

GitHub Actions runs unit tests, Android Lint and a debug APK build.

Local build (Gradle 8.10.2 / Java 17):

```bash
gradle :app:assembleDebug
```

## Next

- Improve face-cluster merge/split correction UI and show cropped face thumbnails instead of the whole representative photo.
- Add semantic image/text embeddings for natural-language retrieval independent of generated captions.
- Add date/location filters and duplicate/screenshot/document filters.
