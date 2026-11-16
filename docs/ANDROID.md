# Android app spec and protocols (for the Android AI)
Native Android, landscape tablet, same API as docs/API.md. The tablet is the guest kiosk and an admin device (PIN-protected admin area). **All image processing is done on the tablet.**

## 1. Pairing and connection (internet first, then local)
1. Admin shows a 6-digit code (valid 5 min) on the laptop. The tablet asks for the **public URL** (e.g. `https://photos.example.com`) and the code: `POST {public}/api/v1/pair {code,name}` -> `{token,deviceId,serverId}`. Store all three (token in Keystore). Base URL = public URL (HTTPS). All device API calls work through the tunnel; laptop-only calls (pair/code, devices, tunnel, settings PUT, admin/*) do not.
2. **LAN upgrade** (after pairing, on app start, on every network change): `GET {public}/api/v1/lan` -> `{serverId,port,addresses[],publicUrl}`. For each address in parallel (1.5 s timeout): make a random 16-byte hex `nonce`, call `GET {addr}/api/v1/lan/verify?device={deviceId}&nonce={nonce}` -> `{serverId,proof}`. Accept only if `serverId` equals the stored one **and** `proof` equals HMAC-SHA256(key = UTF-8 bytes of the uppercase hex SHA-256 of your token, message = UTF-8 nonce), uppercase hex, compared in constant time. This proves the laptop on that address is the real server without sending the token. On success `activeBase = addr`; otherwise stay on the public URL.
3. Per request: try `activeBase`; on network failure fall back to the public URL for that request and re-verify the LAN later. Remember the last verified LAN address and try it first at startup (also works with no internet).
4. Public traffic is HTTPS. LAN traffic is plain HTTP with the bearer token (no request signing yet): allow cleartext only for the paired server's private addresses (network security config) and prefer a private/dedicated router.
5. Cloudflare free caps a request body at 100 MB: upload in several POSTs (see section 5). Never block the guest UI on connectivity.

## 2. Event sync (offline-first)
Local DB: events (JSON + `dirty`), frame/theme image cache (by id, immutable), upload queue, kv (publicUrl, token, deviceId, serverId, cursor, clockOffset).
Run: app start, server reachable again, manual Sync button, debounced after local edits. 
1. **Push** dirty events: `POST /sync/push`; replace local with the returned winners; clear `dirty`.
2. **Pull**: `GET /sync/pull?since=<cursor>` -> `{cursor,events,serverTime}` (includes `deleted:true` tombstones). Apply an event if the local copy is not dirty or the server `updated` is newer. Save `cursor` only after everything is applied. First sync: `since=0`. Update `clockOffset = serverTime - deviceNow`.
3. Download missing images: frames `GET /frames/{eventId}/{frameId}`, theme image `GET /themes/{eventId}/{theme.image}` (send the bearer token; cache by id).
4. Edits set `updated = now + clockOffset`; delete = `deleted:true` + `updated`; never drop a row until synced. Conflicts: latest `updated` wins per whole event. Block edits on an event while a guest session runs on it.
5. New frame or theme image made on the tablet: choose a 32-hex id, put it in the event JSON, push the event, then upload the file with that `id`: `POST /events/{id}/frames` (fields `f`,`name`,`id`) or `POST /events/{id}/theme-image` (fields `f`,`id`). Uploads are idempotent.

## 3. Event management on the tablet
Create, edit, delete events; settings (name, shots, countdown, retention days, max print copies); frames with a slot editor (drag rectangles, optional rotation; coordinates are 0..1 fractions of the frame image, stored in `frames[].slots`); theme image. Starting an event opens the guest start screen.

## 4. Guest flow and processing (on the tablet)
- **Start screen:** blank, the event theme image as background, one Start button centered. Nothing else.
- Pick one or more frames, then START -> N shots, each taken when the guest taps the screen (then the countdown runs) (external UVC webcam via Camera2/CameraX external camera if exposed, else a UVC library such as UVCAndroid; camera behind an interface; handle unplug/replug) -> review: tap to choose as many shots as the frame has slots, retake any -> ask whether to add a GIF and/or live photo.
- **Composite:** cover-crop each chosen shot into its slot, rotate about the slot center, draw the frame PNG on top; output JPEG 90 %, 1200 px wide; thumbnail 300 px wide.
- **GIF/slide** (from the shots, about 800 px, <= 3 s) and **live photo** (short MP4 clip, <= 3 s, ideally < 8 MB) are encoded in the background after the result and QR are shown. Keep videos small: they are served through Cloudflare's free tier.
- Show the QR immediately: session id = 32 random hex chars generated on the device; the QR code is `{publicUrl}/d/{id}` (from `GET /settings`) and is **never** a local address, because guests scan it with their own phones. The download page shows "Preparing" until files arrive. Buttons: Print, Finish. Reset to start after 60 s of inactivity.

## 5. Uploads (queue with retry; each POST `/sessions/{id}` is idempotent and takes any subset of files)
Order: (a) `photo`+`thumb`+fields `eventId`,`frameId` first (the page goes live); (b) `raw1`..`rawN`; (c) `gif`; (d) `live`. Exponential backoff 5 s to 2 min; delete local files only after each 200. Print: `POST /sessions/{id}/print {copies}`; status via `GET /prints`.

## 6. Gallery (admin area)
`GET /events/{id}/sessions` -> sessions with `files[]`; thumbnails `/f/{id}/thumb.jpg`; originals `GET /sessions/{id}/files/{name}` (e.g. `raw1.jpg`, `photo.jpg`, `live.mp4`); `DELETE /sessions/{id}`; reprint; export `GET /events/{id}/export` (zip).

## 7. Kiosk
Landscape lock, keep screen on, lock task mode, admin PIN (lock out after 5 tries), persistent offline queue, server and tunnel status indicators, manual Sync button.

## Known server gaps
No request signing on LAN, no mDNS announcement. Printing is done by the Windows admin app from the server queue.

## Update: camera and local connection
- Camera settings (admin menu on the event list): Automatic (external webcam, then **front** camera, then rear), External only, Front only, Rear only, plus a live test preview.
- Local connection availability: the app re-checks on every network change (ConnectivityManager callback), re-verifies the LAN address every 15 s, looks for the laptop every 20 s, and caches the last LAN addresses so it still finds the laptop with no internet. The current link (Local / Internet / Offline) is shown on the event list and the result screen.

## Update (v14): local pairing, discovery, link switching
- **UDP discovery:** the server answers `PHOTOBOOTH_DISCOVER` on UDP 5081 with `{app,serverId,port}`. The tablet broadcasts to 255.255.255.255 and every interface broadcast address, 1.2 s window, and builds `http://<sender ip>:<port>`.
- **Local pairing (no internet):** the pairing screen accepts a local address (found automatically or typed). If the address is private (192.168/10/172.16-31) the tablet uses it as the active local link, then reads `publicUrl` from `GET /settings`.
- **Link rules** (`Net.monitor`, own thread, runs on every screen incl. the start screen): local link alive -> check `/health` every 3 s, two misses -> drop to internet at once; no local link -> look for the laptop every 6 s (saved addresses, addresses from `/lan`, broadcast, typed address), every candidate verified with the HMAC challenge. Requests try the local link first with a 1.5 s connect timeout and retry the same request on the internet immediately. Modes: Automatic / Internet only / Local only (Connection screen).
- The download page needs `rawCount` and `photoCount` form fields on the first `POST /sessions/{id}` so it can refresh until every file has arrived.

## Update (v20): event management on the tablet
- Admin menu > Manage events: create, edit (name, shots, countdown, keep days, max copies), delete (soft delete), start screen image, frames (add PNG, delete, draw photo slots by dragging). Everything is saved locally first and works offline.
- Sync (`Net.sync`, every minute and after each edit): 1) push dirty events (`POST /sync/push`) and apply the winners; 2) upload new frame/theme images with their client-made ids (`POST /events/{id}/frames`, `/theme-image`); 3) pull (`since=cursor`), a pulled event replaces a dirty local one only if its `updated` is newer. `updated` = device time + clock offset learned from `serverTime`.

## Update (v24): admin shell and gallery
- Admin screens sit in a shell: sidebar (Events, Gallery, Camera, Connection, Sync, live connection status) + content. Guest screens and the slot editor are full screen.
- Gallery uses `GET /gallery` (all events -> sessions -> files), `GET /events/{id}/sessions`, `GET /sessions/{id}/files/{name}`, `GET /f/{id}/thumb.jpg`, `DELETE /sessions/{id}`. It needs a connection to the laptop (photos are not stored on the tablet after upload).
- The event page lists its photo sessions; a session page shows framed photos and original photos, a QR code, and Delete.

## v9 changes (Android)
- **No status bar**: immersive full screen (clock, battery, notifications hidden; a swipe from the edge shows them for a moment).
- **Loading animation**: a spinner screen (`showBusy` / `busy`) for pairing (typed code and QR), finding the host, syncing, saving a picked file, starting the camera, preparing photos and sending a print.
- **Animations**: pressed buttons/cards shrink and spring back, new screens fade and slide in, grids fade in one card after another, thumbnails fade in when loaded.
- **Event editor**: works on a draft copy. A dedicated **Save** button stores it (and syncs). Back / sidebar with unsaved changes asks before discarding; a new event is only created when Save is pressed. The event name is now the first field of the settings section (above countdown, shots, ...).
- **Video start screen**: the start-screen file can be an image or an MP4 (max 40 MB). Videos play muted, in a loop, cropped to fill. Stored as `theme.kind = "video"`; the server detects MP4 and serves it with range support.
- **Frame preview while choosing photos**: the photo-choice screen shows the picked frame with the chosen photos already in the slots (empty slots are numbered grey boxes); selected photos get a slot number.
- **Slideshow GIF**: for frames with exactly ONE slot a switch "Slideshow GIF" appears. When on, all shots play in that slot (preview animates) and `gif.gif` (480 px wide, 0.7 s per photo, looping) is uploaded with the session. Only one GIF per session.
