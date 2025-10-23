# Remote Web View Interface - Implementation Plan

## Overview

This document outlines the implementation plan for the Remote Web View Interface feature, which will allow users to view and play recorded golf swing videos through a web browser on any device connected to the same network as the Android phone.

## Requirements (from TODO.md)

- Create web interface accessible via browser (e.g., http://[phone-ip]:8080)
- Show most recent recording automatically (auto-play/loop)
- Real-time updates when new videos are recorded (websocket or polling)
- Video player embedded in web page
- Optional: Show live camera preview before shot is taken
- Optional: Display shot metadata alongside video (if metadata feature implemented)
- Mobile-friendly responsive design
- Simple, minimal UI focused on video playback

## Technical Approach

### Video Delivery Method
**Progressive Download with HTML5 Video**
- Serve MP4 files directly from Ktor server
- Support HTTP range requests for seeking/buffering
- Let browser's native video player handle playback
- Simplest approach, works on all modern browsers

### Real-time Updates
**Polling (every 2-3 seconds)**
- JavaScript polls `/api/recordings` endpoint periodically
- Compares recording list to detect new videos
- Auto-loads new video when detected
- Simple, no WebSocket complexity needed

### UI Framework
**Vanilla HTML/CSS/JavaScript**
- No external frameworks (React, Vue, etc.)
- Minimal dependencies, fast loading
- Full control over mobile responsiveness
- Easy to maintain and modify

### Camera Preview (Optional)
**JPEG Snapshot Streaming**
- CameraX ImageCapture for periodic frame capture
- Serve JPEG images via `/api/camera/preview` endpoint
- Client polls for new frames (1-2 fps)
- Low overhead, acceptable latency for preview use case

## Implementation Phases

### Phase 1: Static Web Server Setup

**Goal:** Serve HTML/CSS/JS files from the Ktor server

**Tasks:**
1. Create `app/src/main/assets/web/` directory structure
   - `index.html` - Main web interface
   - `styles.css` - Golf-themed responsive styles
   - `app.js` - Client-side JavaScript

2. Update `HttpServerService.kt` to serve static files
   - Add Ktor static content plugin configuration
   - Route `/` to serve `index.html`
   - Route `/styles.css` and `/app.js` appropriately
   - Set proper MIME types

**Dependencies:** None

**Estimated Effort:** 30 minutes

### Phase 2: Video Streaming Endpoints

**Goal:** Enable browser to fetch and play recorded videos

**Tasks:**
1. Add video streaming endpoint: `GET /api/recordings/{filename}/stream`
   - Read MP4 file from recordings directory
   - Set `Content-Type: video/mp4`
   - Implement HTTP range request support (for seeking)
   - Handle file not found errors gracefully

2. Enhance existing `/api/recordings` endpoint
   - Sort recordings by timestamp (newest first)
   - Include all metadata fields (filename, timestamp, duration, fileSize)
   - Return as JSON array

**Dependencies:** Phase 1 complete

**Estimated Effort:** 1 hour

**Technical Notes:**
- HTTP range requests use `Range: bytes=start-end` header
- Must respond with `206 Partial Content` status
- Include `Content-Range` and `Accept-Ranges` headers
- Critical for video seeking/scrubbing in browser

### Phase 3: Web Interface Core Features

**Goal:** Build the responsive web UI with video playback

**Tasks:**

#### 3.1 HTML Structure (`index.html`)
- Header with app title and status indicator
- Main video player (HTML5 `<video>` element with controls)
- Recording list/selector below video
- Live preview placeholder (for Phase 4)

#### 3.2 CSS Styling (`styles.css`)
- Mobile-first responsive design
  - Single column on mobile
  - Flexible layout on tablet/desktop
- Golf theme colors (greens, whites from app theme)
- Video player sized to fit viewport
- Touch-friendly controls (min 44px tap targets)
- Loading states and transitions

#### 3.3 JavaScript Functionality (`app.js`)
- On page load:
  - Fetch recordings list from `/api/recordings`
  - Load most recent video automatically
  - Start auto-play with loop enabled
- Polling loop (every 2-3 seconds):
  - Fetch recordings list
  - Detect new recordings (compare timestamps)
  - Auto-switch to newest video when detected
- Recording list:
  - Display all recordings with timestamps
  - Click to play specific recording
  - Highlight currently playing video
- Error handling:
  - Network errors
  - No recordings available
  - Video playback failures

**Dependencies:** Phase 2 complete

**Estimated Effort:** 1.5 hours

**Technical Notes:**
- Use `fetch()` API for all HTTP requests
- Video element `loop` attribute for continuous playback
- Store last known recording count/timestamp to detect changes
- CSS Grid or Flexbox for responsive layout

### Phase 4: Live Camera Preview (Optional)

**Goal:** Show real-time camera preview in web interface

**Tasks:**

#### 4.1 Android Camera Frame Capture
- Extend `CameraManager.kt`:
  - Add ImageCapture use case alongside VideoCapture
  - Implement `capturePreviewFrame()` method
  - Convert captured frame to JPEG bytes
  - Cache latest frame in memory

- Add preview endpoint in `HttpServerService.kt`:
  - `GET /api/camera/preview` returns latest JPEG
  - Set `Content-Type: image/jpeg`
  - Optional: Add timestamp query param to prevent caching

#### 4.2 Web UI Preview Display
- Add preview section to `index.html`:
  - `<img>` element for preview frame
  - Toggle button to enable/disable (save bandwidth)
  - Small overlay (e.g., 320x240 or 25% of screen)

- Update `app.js`:
  - Polling loop for preview frames (1-2 fps)
  - Update `<img src>` with timestamp to force refresh
  - Start/stop polling when toggled

**Dependencies:** Phases 1-3 complete

**Estimated Effort:** 1-2 hours

**Technical Notes:**
- CameraX ImageCapture is separate from VideoCapture
- May need to handle camera binding carefully (both uses)
- Consider frame capture rate vs. battery/performance
- JPEG quality can be tuned (50-70% for preview is fine)

**Challenges:**
- Additional camera use case complexity
- Memory management for cached frames
- Potential lag (1-2 second delay is acceptable)

## File Structure

```
app/src/main/assets/web/
├── index.html          # Main web interface
├── styles.css          # Golf-themed responsive styles
└── app.js              # Client-side JavaScript

app/src/main/java/com/example/swingcam/
├── HttpServerService.kt    # Add static routes + video streaming
└── CameraManager.kt        # Add preview capture (Phase 4)
```

## API Endpoints

### Existing (will enhance)
- `GET /api/recordings` - List all recordings (sort by newest)
- `GET /api/status` - Server status

### New
- `GET /` - Serve web interface (index.html)
- `GET /styles.css` - Serve CSS
- `GET /app.js` - Serve JavaScript
- `GET /api/recordings/{filename}/stream` - Stream video file
- `GET /api/camera/preview` - Live camera preview JPEG (Phase 4)

## Example Web UI Mockup

```
┌─────────────────────────────────────┐
│  SwingCam Remote View               │
│  ● Live (Server: 192.168.1.100)    │
├─────────────────────────────────────┤
│                                     │
│   ┌───────────────────────────┐   │
│   │                           │   │
│   │    VIDEO PLAYER           │   │
│   │    (Most Recent Swing)    │   │
│   │                           │   │
│   │    [Controls: ▶ ⏸ ━━●━━] │   │
│   └───────────────────────────┘   │
│                                     │
│   [Live Preview] (toggle on/off)   │
│   ┌──────────┐                     │
│   │  Camera  │                     │
│   │  Preview │                     │
│   └──────────┘                     │
│                                     │
├─────────────────────────────────────┤
│  Recent Recordings:                 │
│  ► Oct 22, 3:45 PM (2.1s)          │
│    Oct 22, 3:42 PM (2.0s)          │
│    Oct 22, 3:38 PM (2.1s)          │
│    Oct 22, 3:35 PM (2.0s)          │
└─────────────────────────────────────┘
```

## Testing Strategy

### Manual Testing Checklist

**Basic Functionality:**
- [ ] Web interface loads at `http://[phone-ip]:8080`
- [ ] Most recent video loads automatically
- [ ] Video plays and loops continuously
- [ ] Video controls work (play, pause, seek)
- [ ] Recording list displays all videos
- [ ] Clicking recording loads that video

**Real-time Updates:**
- [ ] Trigger new recording via launch monitor mode
- [ ] Web interface detects new recording within 3 seconds
- [ ] New video auto-loads and starts playing
- [ ] Recording list updates with new entry

**Responsive Design:**
- [ ] Test on phone browser (Chrome/Safari)
- [ ] Test on tablet (portrait and landscape)
- [ ] Test on desktop (various window sizes)
- [ ] Video player scales appropriately
- [ ] Touch targets are easy to tap (mobile)

**Live Preview (if implemented):**
- [ ] Preview toggle enables/disables preview
- [ ] Preview shows live camera feed (1-2 fps)
- [ ] Preview has acceptable latency (<2 seconds)
- [ ] No crashes when preview enabled

**Edge Cases:**
- [ ] No recordings available (show message)
- [ ] Network error (retry/show error)
- [ ] Video file deleted while playing (handle gracefully)
- [ ] Multiple clients connected (all work independently)

## Known Limitations

1. **No authentication** - Anyone on the local network can access
2. **HTTP only** - No HTTPS (local network trust assumed)
3. **Polling overhead** - Continuous polling uses some bandwidth
4. **Preview latency** - 1-2 second delay is expected
5. **No download option** - Can add later if needed
6. **No metadata display** - Deferred until metadata feature implemented

## Future Enhancements

- WebSocket support for instant updates (replace polling)
- Thumbnail generation for faster recording list browsing
- Download button for saving videos to computer
- Display shot metadata alongside video (when available)
- Multi-camera view (if multiple phones connected)
- Dark mode toggle
- Fullscreen video mode
- Gesture controls (swipe to next/previous video)

## Dependencies

### Kotlin/Android
- Ktor static content plugin (already using Ktor 2.3.7)
- CameraX ImageCapture (for preview, if implemented)

### Web (None - Vanilla JS)
- Modern browser with HTML5 video support
- JavaScript ES6+ support (2015+, all modern browsers)

## Rollout Plan

### Recommended Order
1. **Implement Phases 1-3 first** (core functionality)
   - Most valuable features: video playback, auto-updates
   - Lower complexity, faster delivery
   - Can be tested and used immediately

2. **Defer Phase 4** (live preview) unless critical
   - Higher complexity, more potential bugs
   - Preview is "nice to have" vs. core video playback
   - Can implement later based on user feedback

### Incremental Testing
- Test each phase independently before moving to next
- Deploy to device and test with real network/browser
- Get user feedback after Phase 3 before starting Phase 4

## Estimated Total Effort

- **Phases 1-3 (Core):** 3 hours
- **Phase 4 (Preview):** 1-2 hours
- **Testing/Polish:** 1 hour
- **Total:** 4-6 hours

## Notes

- This plan assumes basic web development knowledge (HTML/CSS/JS)
- Golf theme colors should match existing app (extract from colors.xml)
- Consider battery usage when preview is enabled (warn user?)
- Polling interval can be made configurable if needed
- Range request implementation is critical for smooth video playback
