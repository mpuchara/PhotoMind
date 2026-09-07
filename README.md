# PhotoMind

PhotoMind is a privacy-first Android photo search app. It indexes the user's local gallery on-device and lets the user search with normal text.

## v0.1

- Reads the Android gallery through `MediaStore` (no photo copying).
- On-device image labels with ML Kit.
- On-device OCR with ML Kit Text Recognition.
- Polish → English query translation with ML Kit Translation so Polish queries can match English image labels.
- Search across AI labels, OCR, filenames, folders and user tags.
- Long-press a photo to add a custom tag or person's name.
- Local SQLite index.
- Tap a result to open the original photo.
- No server and no photo upload. Internet permission is only used to download the optional ML Kit translation model.

## Build

Push to `main`. GitHub Actions builds `PhotoMind-debug` as an APK artifact.

Local build (Gradle 8.10.2 / Java 17):

```bash
gradle :app:assembleDebug
```

## Planned

- Face detection + local face embeddings and clustering, then name a person once per cluster.
- Semantic image/text embeddings for natural-language retrieval beyond fixed labels.
- Background incremental indexing with battery/thermal safeguards.
- Date/location filters and duplicate/screenshot/document filters.
