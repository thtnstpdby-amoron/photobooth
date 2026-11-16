# Photobooth: product overview and decisions

**Goal:** replace a 150k/month photobooth subscription with a self-hosted system: a Windows laptop server and an Android tablet kiosk (UVC webcam). Windows and Android only.

**Roles.** Laptop = a native Windows admin app (events, frames, gallery, print, tablets, Cloudflare, status) plus a background server (Photobooth.exe, started and watched by the admin app) holding the main database, storage, public download pages, Cloudflare tunnel and print queue. Tablet = guest kiosk **and** admin device; it captures, composites the frame, builds the GIF/live photo, and uploads. All image processing happens on the tablet; the server never composes images.

**Guest flow.** Blank start screen (event theme image as background, one centered Start button) -> pick a frame/twibbon -> 4 shots with countdown, retake any -> choose photos for the frame slots -> optionally add GIF/live photo -> result shown, QR codes -> download on phone or request print.

**Frames.** Transparent PNG with photo placeholders (slots: position, size, rotation as fractions of the image) drawn by the admin. Stacked or scattered layouts; a 3-slot frame lets the guest pick 3 of the 4 shots.

**Uploads per session.** All raw shots, the framed photo, a thumbnail, and (if the guest wants) a GIF and/or live photo (short MP4).

**Delivery.** QR code to a download page on the laptop, served through Cloudflare Tunnel on the owner's domain (no per-GB cost; keep videos short). Downloads work only while the laptop is online (accepted by the client). Photos auto-delete after N days (default 10).

**Connectivity.** The tablet pairs over the internet (public URL + 6-digit code), then upgrades to a verified local connection when on the same network, falling back to the public URL. Offline work is queued and synced.

**Event management** works on both devices with offline-first sync (latest edit wins per event, soft deletes, server revision cursor).

**Gallery** per event for admins (thumbnails, raw files, delete, reprint, export zip). **Printing:** tablet requests, laptop queue; the Windows admin app prints to the default printer (automatically if enabled) and marks the job done.

**Security.** Per-device revocable tokens (hashed); admin password hashed; rate-limited login/pairing; admin and laptop-only functions are blocked through the tunnel; the tunnel token is encrypted at rest.

**Not built yet:** request signing for local HTTP, mDNS discovery, server-side image processing (by design).
