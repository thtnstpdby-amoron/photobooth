# Setup (for the person running the booth)

1. **Before installing**, in Cloudflare (free plan): add your domain, then Zero Trust > Networks > Tunnels > Create tunnel. Add a public hostname such as `photos.yourdomain.com` -> HTTP -> `localhost:5080`. Copy the **token** (only the long string).
2. Run `PhotoboothSetup.exe`. The Cloudflare page asks for the token, the hostname and an admin password. It copies the program files (server, Cloudflare tunnel client, admin app) and makes **Photobooth Admin** start (minimized in the tray) every time someone logs in to Windows.
3. Photobooth Admin starts the server (`Photobooth.exe`) in the background by itself, and the server starts the Cloudflare tunnel. If the server stops, the admin app starts it again. There is no Windows service.
4. The first time the server listens, Windows may ask whether to allow it through the firewall: choose **Private networks** and Allow. (Or use Cloudflare tab > "Allow through firewall...".)
5. Set the laptop's network profile to **Private** and Power > Sleep to **Never** while plugged in.
6. In Photobooth Admin: Events, Gallery, Print, Devices (New Device shows a pairing QR code), Cloudflare (token, status, restart server). Closing the window keeps it in the tray; the tray menu has "Stop server and exit".

Data lives in `C:\ProgramData\Photobooth` (back it up). Photos are deleted after each event's "keep days". Guests can download only while the laptop is on, logged in, and online. Update by running the newer setup (leave the wizard fields empty to keep settings).
