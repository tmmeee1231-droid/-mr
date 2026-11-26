# MANW Control API

Express API for shared MANW app settings and admin authentication. Persistent data is stored in Supabase Postgres and background images in Supabase Storage. Do not deploy the old SQLite version to an ephemeral filesystem.

## Supabase setup

1. Create a Supabase project.
2. Open SQL Editor and run `supabase/schema.sql`.
3. In Storage, create a bucket named `manw-assets` and set it to **Public** (the API server uses the service-role key to upload; public reads are needed by installed apps).
4. In Project Settings -> API, copy the Project URL and service-role key. Keep the service-role key secret and put it only in Render environment variables.

## Render setup

Connect this GitHub repository as a Web Service:
- Root Directory: `backend`
- Runtime: Node
- Build Command: `npm install`
- Start Command: `npm start`
- Health Check Path: `/health`

Set these environment variables in Render:
- `SUPABASE_URL`: Supabase Project URL
- `SUPABASE_SERVICE_ROLE_KEY`: Supabase service-role key (secret)
- `SUPABASE_STORAGE_BUCKET`: `manw-assets`
- `JWT_SECRET`: a unique random secret of at least 32 characters
- `ADMIN_INITIAL_PASSWORD`: a temporary strong password for first startup only

The first startup creates the admin record using a bcrypt hash. Change the temporary password immediately using `POST /api/auth/change-password`. After the admin record exists, remove `ADMIN_INITIAL_PASSWORD` from Render. Never commit secrets or send them in chat.

## Endpoints

- `GET /health`
- `GET /api/settings`
- `POST /api/auth/login`
- `POST /api/auth/change-password`
- `POST /api/admin/background` (multipart field: `image`, max 5 MB)
- `PUT /api/admin/settings`

The Android application still needs to be updated to consume these endpoints and provide the admin UI. Launcher icon/name changes require a new APK; server settings can change the in-app title, background, and text.
