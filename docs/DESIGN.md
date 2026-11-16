# Design tokens (from the Figma design, 1280 x 800 canvas)

- Colors: maroon `#800000` (sidebar, cards), cream `#FFFCCF` (content panel, pills), camera preview `#5A3F3F`, text on cards white, secondary text white 80 %.
- Fonts: **Cormorant Garamond** (serif: navigation, card titles bold, labels) and **Lexend** (sans: page titles, filter, status box, "New Event"). Static instances are in `android/app/src/main/assets/fonts` and `admin/fonts`.
- Shape: cards radius 16, pills fully rounded, content panel has rounded left corners (30), sidebar 307 px wide, 3 card columns.
- Android: everything is sized by `u(designPixels)` (screen width / 1280, limited by height / 800) so the layout matches the design on any landscape screen. Icons are Material outline paths (`Ui.kt`, `Icons`).
- Screens: Events (list + filter), Event information (header card + Event Gallery sessions), Event edit (name, start screen, 4 steppers, Add Frame, frames), Gallery (events) > Session list > Session images, Camera Setting (live preview), Connection (mode, find host, unpair).
- Not in the design, styled to match: pairing screen, slot editor, the guest screens (start, frames, photos, QR, print).

## Windows admin (v8)
Rebuilt to match `windows_UI_design.zip` (Desktop 1-9 + Popup). Own title bar (borderless window, drag/resize/min/max/close), header with logo and "New Event", sidebar (Events, Gallery, Print, Devices, Server Config + status box), cream rounded content panel. All drawing is custom GDI+ (`admin/Ui.cs`), sizes are in design units (1200 x 638) scaled by `Theme.Scale`.
Pages: Events, Event information, Event edit (autosaves), Gallery > Sessions > Session photos (+ QR for the download page), Print queue, Devices (+ "Pair new device" QR popup), Server config.
Not in the design, styled to match: slot editor, photo viewer, confirm/info/password dialogs, session QR popup, "Print automatically" switch, tunnel switch and firewall button.
The design's "Start Event Session" button is not implemented (tablets start sessions themselves; there is no server function for it).

## Windows admin, light theme (v8.1)
The first Figma-style drawing (own title bar, Cormorant/Lexend fonts, shadows, heavy rounding) was replaced by a light native-looking UI: standard Windows frame and scrollbars, Segoe UI, white surfaces, maroon accent, 6 px corners and soft maroon-tinted outlines (`Theme.Line` / `Theme.LineStrong`). Pages, data loading and QR pairing are unchanged. Colours are all in `Theme` (admin/Program.cs); controls in admin/Ui.cs.

## Windows admin v9
- **Event editor**: edits a copy; the dedicated **Save** button (top right, with an "Unsaved changes" hint) stores it. Picked start-screen / frame files are only uploaded on Save. Leaving with unsaved changes asks first; a new event is only created on Save. No autosave. The event name is the first field of the settings section.
- **Start screen**: image (PNG/JPG) or MP4 (max 40 MB, plays in a loop on the tablet). The thumbnail of a video start screen shows a play badge (Windows cannot decode MP4 posters).
- **Printer**: Print page > "Printer :" lists every installed printer; the choice is kept in `%AppData%\PhotoboothAdmin\printer.txt` (empty = Windows default) and used for printing and shown in the status box.
- **Devices**: unpairing removes the tablet from the list (server `DELETE /devices/{id}` deletes the record; older revoked records are purged at server start). "New device" uses the same small button as "New event".
- **Loading screens**: a spinner over the content area for anything slower than 0.2 s (page loads, saving, uploads, export, restart, unpair), spinners in thumbnails while pictures load, in the pairing QR while the code is made, and in the photo viewer.
- Consistent top padding (content panel and sidebar).
- Frame cards show "1 slot: slideshow GIF possible" for single-slot frames (the tablet makes the GIF).
