# Meta Media Saver

Native Android MVP for signing in with a Firebase app account, connecting an official Meta account, browsing authorized own-media, and saving photos/videos to the phone gallery.

## What Is Implemented

- Kotlin + Jetpack Compose Android app.
- Firebase email/password auth with a profile username in Firestore.
- Meta connection flow through Firebase callable functions.
- Media account picker, paginated media grid, refresh/error/empty states, and download controls.
- WorkManager-backed downloads that stream through OkHttp and write to Android MediaStore.
- Firebase Functions TypeScript backend for OAuth start/finish, account listing, media listing, URL refresh, and disconnect.

The app intentionally does not collect Instagram/Facebook passwords and does not scrape arbitrary feed items.

## Setup

1. Open the project in Android Studio.
2. Register an Android Firebase app with package `com.santi.metamediasaver`.
3. Download `google-services.json` from Firebase and place it in `app/google-services.json`.
4. Enable Firebase Authentication email/password and deploy `firestore.rules`.
5. Create a Meta developer app, configure Facebook Login, and add this redirect URI:

   ```text
   metamediasaver://oauth/meta
   ```

6. Configure Functions params/secrets before deploy:

   ```bash
   firebase functions:secrets:set META_APP_SECRET
   ```

   Create `functions/.env`:

   ```text
   META_APP_ID=your-meta-app-id
   META_REDIRECT_URI=metamediasaver://oauth/meta
   META_GRAPH_VERSION=v24.0
   META_LOGIN_SCOPES=email,pages_show_list,pages_read_engagement,instagram_basic,user_photos,user_videos
   ```

7. Install backend dependencies and deploy:

   ```bash
   cd functions
   npm install
   npm run build
   firebase deploy --only functions,firestore:rules
   ```

8. Sync/build the Android app.

## Notes

- `app/google-services.json` is ignored so real Firebase identifiers do not get committed.
- If `google-services.json` is absent, Gradle can still sync the project, but Firebase calls will not work at runtime.
- Instagram API media access depends on Meta permissions, account type, and App Review approval.
