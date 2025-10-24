# SwingCam TODO & Feature Requests

## Completed ✅
- [x] Basic camera recording with CameraX
- [x] HTTP API server for remote control
- [x] Launch monitor mode (continuous recording + post-trigger extraction)
- [x] Configurable recording duration
- [x] Configurable post-shot delay (to capture full swing follow-through)
- [x] Auto-cleanup of temp files on startup
- [x] Keep screen awake while app is running
- [x] Asynchronous video extraction (to avoid blocking re-arm)
- [x] Handle re-arming while already armed (discard and restart)
- [x] Golf-themed color scheme (greens and whites)
- [x] Remote web view interface (October 2025)
  - Web interface accessible via browser at http://[phone-ip]:8080
  - Auto-plays most recent recording with loop
  - Real-time polling for new videos (2.5s interval)
  - HTTP range request support for smooth video seeking
  - Live camera preview (toggle on/off, ~1fps)
  - Mobile-friendly responsive design
  - Golf-themed UI matching Android app
- [x] Shot Metadata from Launch Monitor (October 2025)
  - Ball data fields (ball speed, launch angle, spin rate, carry distance, etc.)
  - Club data fields (club speed, club path, face angle, attack angle, etc.)
  - `/api/lm/shot-detected` accepts ball data in request body
  - `PATCH /api/recordings/{filename}/metadata` endpoint to update club data separately
  - Club data updates matched to correct video by filename
  - Metadata stored in recording .json files
  - Metadata displayed in Android recordings list with formatted values
  - Metadata displayed in web interface with detailed grid layout
  - Web interface auto-updates metadata when club data arrives (no manual refresh)
  - Full support for optional fields (different launch monitors provide different data)

## Backlog 📋

### High Priority

- [ ] **Convert Recording Duration from Seconds to Milliseconds**
  - Change `Config.duration` from seconds (Int) to milliseconds (Int) for consistency with `postShotDelay`
  - Update default from 5 seconds (5000ms) to 2500ms (2.5 seconds)
  - Update `CameraManager.kt` to remove `* 1000` conversion
  - Provides finer control and captures full swing start (current 2s sometimes misses beginning)

### Medium Priority

- [ ] **Optional Putt Filtering**
  - Add config option `savePutts: Boolean = true` to automatically discard putter recordings
  - When club data arrives with `clubType` indicating putter, auto-delete the recording and metadata
  - Keep recordings list clean during full swing practice on simulator
  - Check for putter in both `/api/lm/shot-detected` and `PATCH /metadata` endpoints

- [ ] **Shot History Filtering and Grouping**
  - Advanced filtering UI to find shots by criteria:
    - Filter by club type (all drivers, all 7-irons, etc.)
    - Filter by shot quality metrics (ball speed range, carry distance, spin rate, etc.)
    - Filter by swing characteristics (club path, face angle, attack angle ranges)
    - Find "bad shots" - outliers based on configurable thresholds (e.g., club path > +5° or < -5°)
    - Date range filtering
    - Combine multiple criteria (e.g., "Driver shots with slice path")
  - Group/organize recordings by:
    - Club type
    - Date/session
    - Shot quality buckets (good/average/poor based on metrics)
  - Save custom filter presets for quick access
  - Show statistics for filtered groups (avg carry, avg ball speed, etc.)
  - Both Android app and web interface support

- [ ] **Screen Rotation/Orientation Support**
  - Ensure all screens work in both portrait and landscape
  - Handle orientation changes gracefully (maintain playback state)
  - Optimize layouts for landscape viewing (especially video playback)
  - Test with auto-rotate enabled

- [ ] **Auto Storage Management**
  - Show current storage usage (total recordings size)
  - Auto-delete old recordings after configurable days (e.g., 7, 14, 30 days)
  - Warning when storage is getting low
  - Option to keep favorites/starred recordings from auto-deletion
  - Manual "free up space" option to bulk delete old recordings
  - Settings page to configure retention policy

- [ ] **Advanced Playback Controls**
  - Variable playback speed control (0.1x, 0.25x, 0.5x, 1x, 2x)
  - Very slow playback for detailed swing analysis
  - Pause/resume video playback
  - Frame-by-frame stepping (forward/backward)
  - Scrubbing timeline to specific points in the video
  - Show playback speed indicator on screen
  - Tap or button controls for speed adjustment
  - Remember last used playback speed

- [ ] **Swipe Navigation in Recordings Playback**
  - When playing a recording from history, enable swipe gestures
  - Swipe left/right to navigate to previous/next recording
  - Show current position indicator (e.g., "3 / 15")
  - Smooth transitions between videos
  - Auto-play next video when swiping
  - Loop video playback while viewing
  - Optional: Add arrow buttons as alternative to swiping

### Low Priority

- [ ] **Video Annotation/Drawing Tools** (like V1 Golf)
  - Draw directly on paused or playing video
  - Drawing tools:
    - Straight lines (for swing plane analysis)
    - Lines with angle measurements
    - Circles/ellipses
    - Freehand drawing
    - Arrows
  - Color picker for drawings
  - Line thickness adjustment
  - Undo/redo functionality
  - Clear all drawings
  - Save annotated frames as images
  - Optional: Save drawings with video metadata to persist across views
  - Works with slow-motion and frame-by-frame playback
  - Touch/finger drawing on screen

### Ideas / For Consideration

- **Side-by-Side Comparison** - Compare two swings playing simultaneously, synced playback, useful for before/after analysis or comparing different clubs
- **Export/Share** - Export videos to camera roll, share to social media, send to coach, option to include annotations
- **Session/Round Organization** - Group recordings by practice session or round ("Driver practice 10/22", "7-iron session 10/23")
- **Video Trimming** - Fine-tune start/end points of saved clips if 2-second window needs adjustment
- **Grid Overlay** - Toggle alignment grid on playback (horizontal/vertical lines) for checking posture and alignment
- **Club/Shot Tagging** - Tag recordings with club type (Driver, 7-iron, wedge) and shot type (full swing, chip, putt), filter by tags
- **Voice Notes** - Attach quick audio notes to recordings ("Was aiming left", "Weight on toes")
- **Favorite/Star System** - Mark best swings to find them easily, protect from auto-deletion
- **Progress Timeline** - Visual timeline showing recordings over days/weeks, track practice frequency

---

## Notes
- Feature requests should be added to the appropriate priority section
- Move items to "Completed" when done
- Add date completed for tracking
