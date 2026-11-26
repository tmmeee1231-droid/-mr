# MANW Control API

Railway service for shared app settings and admin authentication. Mount a persistent Railway volume at `/data`. Configure `JWT_SECRET` and `ADMIN_INITIAL_PASSWORD` as Railway service variables before first deploy. The initial password is hashed with bcrypt and must be changed through `/api/auth/change-password` after login. Use HTTPS. Image uploads are limited to PNG/JPEG/WEBP and 5 MB.

Endpoints: `GET /health`, `GET /api/settings`, `POST /api/auth/login`, `POST /api/auth/change-password`, `POST /api/admin/background`, `PUT /api/admin/settings`.
