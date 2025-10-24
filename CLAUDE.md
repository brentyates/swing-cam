# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SwingCam - A native Android app for recording golf swings with HTTP API control. Designed specifically for Pixel 9, leveraging its slow-motion camera capabilities. The app runs an embedded HTTP server (Ktor) allowing external triggers from golf simulators or other devices. Simple by design: just duration configuration, everything else handled by the phone's camera.

## Documentation Structure

Project documentation is organized as follows:

- **[README.md](../README.md)** - Main project overview, features, and getting started
- **[CLAUDE.md](../CLAUDE.md)** - This file - development guide for AI assistants
- **[TODO.md](../TODO.md)** - Feature backlog with priorities
- **docs/**
  - **[QUICKSTART.md](docs/QUICKSTART.md)** - 5-minute setup guide
  - **[USAGE.md](docs/USAGE.md)** - Detailed API documentation and examples
  - **[ARCHITECTURE.md](docs/ARCHITECTURE.md)** - Project structure and design decisions
  - **[WEB_INTERFACE_PLAN.md](docs/WEB_INTERFACE_PLAN.md)** - Implementation plan for web interface feature

## Tech Stack

- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Build System**: Gradle with Kotlin DSL
- **Camera**: CameraX Video API (androidx.camera)
- **HTTP Server**: Ktor (embedded Netty server on port 8080)
- **Video Playback**: ExoPlayer (androidx.media3) - planned
- **JSON**: Gson
- **Architecture**: Single Activity with ViewBinding

## Core Commands

### Building

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug

# Clean build
./gradlew clean
```

### Running

```bash
# Run on connected device/emulator
./gradlew installDebug
adb shell am start -n com.example.swingcam/.MainActivity

# View logs
adb logcat | grep "SwingCam\|CameraManager\|HttpServerService"
```

### Testing

No formal test suite yet. Manual testing:
1. Build and install on Pixel 9
2. Grant permissions (Camera, Microphone, Notifications)
3. Start HTTP server in app
4. Trigger recording via HTTP API
5. Verify recording saves and appears in list

### Development Tips

**Quick reinstall during development:**
```bash
./gradlew installDebug && adb shell am start -n com.example.swingcam/.MainActivity
```

**Clear app data:**
```bash
adb shell pm clear com.example.swingcam
```

**View app files:**
```bash
adb shell run-as com.example.swingcam ls files/recordings
```

## Architecture

### Application Components

1. **MainActivity.kt** - Main entry point
   - Manages camera initialization
   - Binds to HttpServerService
   - Handles UI updates and recording list
   - Orchestrates all components

2. **HttpServerService.kt** - Foreground service
   - Runs Ktor embedded server on port 8080
   - Implements REST API endpoints
   - Communicates with MainActivity via callback interface
   - Shows persistent notification with server status

3. **CameraManager.kt** - Camera abstraction
   - Uses CameraX Video API (VideoCapture for recording)
   - Uses CameraX ImageCapture for live preview snapshots
   - Configures for highest quality (slow-motion)
   - Auto-stops recording after configured duration
   - Captures JPEG snapshots for web interface preview (~1fps)
   - Callbacks for recording lifecycle events

4. **RecordingRepository.kt** - File management
   - Manages recordings directory
   - CRUD operations for recordings and metadata
   - Loads/saves JSON metadata files

5. **RecordingAdapter.kt** - RecyclerView adapter
   - Displays list of recordings
   - Play/delete buttons per recording

6. **Web Interface** (assets/web/)
   - `index.html` - Remote web interface UI
   - `styles.css` - Golf-themed responsive styling
   - `app.js` - Client-side JavaScript for video playback and polling
   - Accessible at http://[phone-ip]:8080
   - Auto-plays most recent recording
   - Real-time polling for new recordings (2.5s interval)
   - Live camera preview (toggle on/off)

### Data Models

**Config.kt**
```kotlin
data class Config(
    val duration: Int = 5,  // Recording duration in seconds
    val postShotDelay: Int = 500  // Delay after shot detection before stopping
)
```

**RecordingMetadata.kt**
```kotlin
data class RecordingMetadata(
    val filename: String,
    val timestamp: String,
    val duration: Int,
    val fileSize: Long,
    val filePath: String,
    val shotMetadata: ShotMetadata? = null  // Optional shot data from launch monitor
)
```

**ShotMetadata.kt**
```kotlin
data class ShotMetadata(
    val ballData: BallData? = null,     // Ball flight metrics (12 fields)
    val clubData: ClubData? = null,     // Club performance metrics (9 fields)
    val timestamp: Long = System.currentTimeMillis()
)

data class BallData(
    val ballSpeed: Double? = null,           // mph or km/h
    val launchAngle: Double? = null,         // degrees
    val launchDirection: Double? = null,     // degrees
    val spinRate: Int? = null,               // rpm
    val carryDistance: Double? = null,       // yards or meters
    // ... 7 more optional fields (see ShotMetadata.kt)
)

data class ClubData(
    val clubSpeed: Double? = null,           // mph or km/h
    val clubType: String? = null,            // e.g., "Driver", "7-iron"
    val smashFactor: Double? = null,         // ratio
    val attackAngle: Double? = null,         // degrees
    // ... 5 more optional fields (see ShotMetadata.kt)
)
```

### HTTP API Endpoints

**Recording Management:**
- `POST /api/record` - Start a recording
- `GET /api/status` - Get camera status
- `GET /api/recordings` - List all recordings (sorted newest first)
- `DELETE /api/recordings/{filename}` - Delete specific recording
- `DELETE /api/recordings` - Delete all recordings

**Launch Monitor Mode:**
- `POST /api/lm/arm` - Arm launch monitor (start continuous recording)
- `POST /api/lm/shot-detected` - Extract last N seconds from buffer (accepts optional ball data)
- `POST /api/lm/cancel` - Cancel launch monitor mode
- `GET /api/lm/status` - Get launch monitor status

**Shot Metadata:**
- `PATCH /api/recordings/{filename}/metadata` - Update shot metadata (typically club data sent after ball data)

**Web Interface:**
- `GET /` - Serve web interface (index.html)
- `GET /styles.css` - Serve CSS stylesheet
- `GET /app.js` - Serve JavaScript application
- `GET /api/recordings/{filename}/stream` - Stream video with HTTP range support
- `GET /api/camera/preview` - Get live camera preview snapshot (JPEG)

### HTTP API Flow

1. External client sends `POST /api/record`
2. Ktor server receives request in HttpServerService
3. Service calls callback to MainActivity
4. MainActivity triggers CameraManager.startRecording()
5. CameraManager records for configured duration
6. On completion, metadata saved via RecordingRepository
7. UI refreshes to show new recording
8. Web interface polls and auto-updates with new recording

### File Organization

```
app/files/
├── config.json                    # App configuration
└── recordings/
    ├── swing_20241022_143530.mp4  # Video file
    ├── swing_20241022_143530.json # Metadata (includes optional shot data)
    ├── swing_20241022_144120.mp4
    └── swing_20241022_144120.json
```

**Metadata JSON Structure:**
```json
{
  "filename": "swing_20241022_143530.mp4",
  "timestamp": "2024-10-22 14:35:30",
  "duration": 2,
  "fileSize": 12450000,
  "filePath": "/data/.../recordings/swing_20241022_143530.mp4",
  "shotMetadata": {
    "ballData": {
      "ballSpeed": 165.3,
      "launchAngle": 12.5,
      "carryDistance": 275.5
    },
    "clubData": {
      "clubSpeed": 112.5,
      "clubType": "Driver",
      "smashFactor": 1.47
    },
    "timestamp": 1729612530000
  }
}
```

## Critical Implementation Details

### Camera Configuration

- Uses `Quality.HIGHEST` to leverage Pixel's best slow-motion mode
- Back camera (rear-facing) for recording swings
- CameraX handles all encoding and frame rate optimization
- No manual FPS/resolution config - phone decides best settings

### Recording Lifecycle

1. `CameraManager.setupCamera()` - Initialize camera (once)
2. `CameraManager.startRecording()` - Begin capture
3. Auto-stop after `config.duration` seconds via `delay()`
4. `onRecordingFinished` callback with output file
5. MainActivity saves metadata and refreshes UI

### HTTP Server

- Runs in foreground service to prevent Android from killing it
- Notification required for foreground service (Android 8+)
- Binds to all network interfaces (0.0.0.0)
- Port 8080 (not configurable currently)
- GSON content negotiation for JSON responses

### Permissions

Required permissions (AndroidManifest.xml):
- `CAMERA` - Video recording
- `RECORD_AUDIO` - Audio in videos
- `INTERNET` - HTTP server
- `FOREGROUND_SERVICE` - Keep server running
- `POST_NOTIFICATIONS` - Show server notification (Android 13+)

Runtime permissions requested in MainActivity.checkPermissions()

### Storage

- Uses app-private internal storage (`context.filesDir`)
- Recordings stored in `files/recordings/` subdirectory
- FileProvider configured for potential sharing (file_paths.xml)
- No external storage access needed

## Common Tasks

### Adding a New API Endpoint

1. Add route in `HttpServerService.routing { }` block
2. Add method to `ServerCallback` interface
3. Implement method in `MainActivity.createServerCallback()`
4. Update API documentation in README.md

Example:
```kotlin
// In HttpServerService
get("/api/config") {
    val callback = serverCallback
    if (callback != null) {
        call.respond(callback.getConfig())
    }
}

// In ServerCallback interface
fun getConfig(): Map<String, Any>

// In MainActivity
override fun getConfig(): Map<String, Any> {
    return mapOf("duration" to config.duration)
}
```

### Modifying Recording Duration

Duration is loaded from `config.json`:
```kotlin
val config = Config.load(filesDir)
```

To change default, edit `Config.kt`:
```kotlin
data class Config(
    val duration: Int = 10  // Change default here
)
```

Or save new config:
```kotlin
Config.save(filesDir, Config(duration = 10))
```

### Adding Video Playback

ExoPlayer dependency already included. To implement:

1. Create `VideoPlayerActivity.kt`
2. Add layout with `PlayerView`
3. Initialize ExoPlayer with recorded video URI
4. Update `MainActivity.playRecording()` to launch activity
5. Handle player lifecycle (pause/resume/release)

Example:
```kotlin
private fun playRecording(metadata: RecordingMetadata) {
    val intent = Intent(this, VideoPlayerActivity::class.java).apply {
        putExtra("VIDEO_PATH", metadata.filePath)
    }
    startActivity(intent)
}
```

### Debugging Recording Issues

Check logs for these key messages:
- "Camera setup complete with slow-motion mode" - Camera initialized
- "Recording started: swing_*.mp4" - Recording began
- "Recording saved: /path/to/file" - Recording completed
- "Recording error: X" - Something failed

Common issues:
- Camera in use by another app
- Insufficient storage space
- Permissions not granted
- CameraX not compatible with device

### Changing Server Port

Currently hardcoded to 8080. To change:

1. Edit `HttpServerService.PORT` constant
2. Update documentation in README.md and USAGE.md
3. Consider making it configurable in Config.kt

### Accessing the Web Interface

The web interface is automatically served when the HTTP server is running:

1. Start the HTTP server in the app (auto-starts on launch)
2. Note the IP address displayed in the app (e.g., 10.0.0.147)
3. Open browser on same WiFi network: `http://[phone-ip]:8080`

**Features:**
- Auto-plays most recent swing recording with loop
- Polls for new recordings every 2.5 seconds
- Click any recording in list to play it
- Toggle live camera preview on/off
- Responsive design (mobile, tablet, desktop)
- HTTP range request support for smooth video seeking

**Debugging Web Interface:**
- Check browser console for JavaScript errors
- Verify `/api/recordings` returns data
- Test video streaming: `curl -I http://[ip]:8080/api/recordings/[filename]/stream`
- Test preview: `curl http://[ip]:8080/api/camera/preview -o preview.jpg`

## Code Style & Conventions

- **Kotlin style**: Official Kotlin coding conventions
- **Null safety**: Use nullable types (`?`) and safe calls (`?.`)
- **Coroutines**: Use `lifecycleScope.launch` for async operations in MainActivity
- **ViewBinding**: Enabled, use instead of findViewById
- **Logging**: Use Android Log with appropriate tags
  - CameraManager: "CameraManager"
  - HttpServerService: "HttpServerService"
  - MainActivity: "MainActivity"
- **Error handling**: Try-catch with user-friendly Toast messages
- **File paths**: Use `File` objects, convert to String only when necessary

## Important Constraints

### Android Lifecycle

- MainActivity may be destroyed/recreated by system
- HttpServerService runs independently in foreground
- Service communicates via callback interface
- Bind/unbind service in MainActivity lifecycle

### Camera Limitations

- Only one recording at a time (enforced by `isRecording` flag)
- Camera must be released when activity destroyed
- CameraX requires ViewLifecycleOwner (MainActivity implements)
- Some devices may not support highest quality

### Network

- Server only accessible on local network
- No authentication/security (trust network)
- IP address changes if WiFi changes
- Port 8080 must be available

### Storage

- Internal storage only (private to app)
- Videos can be large (varies by duration)
- No automatic cleanup of old recordings
- User must manually delete via app or API

## Platform-Specific Notes

### Pixel 9

- Native slow-motion support via camera hardware
- CameraX automatically uses best available quality
- Excellent low-light performance
- High frame rate recording optimized

### Android 13+

- Runtime notification permission required
- Foreground service restrictions (camera type)
- Enhanced privacy controls

### Android 8+

- Foreground service requires notification
- Notification channels required

## Dependency Notes

### Ktor

- Version: 2.3.7
- Lightweight embedded server
- No external web server required
- Netty engine for performance

### CameraX

- Version: 1.3.1
- Modern camera API (replaces Camera2)
- Lifecycle-aware
- Consistent API across devices
- Hardware encoding support

### ExoPlayer (media3)

- Version: 1.2.1
- Modern replacement for MediaPlayer
- Better format support
- Adaptive streaming capabilities
- Currently included but not used (future playback feature)

### Gson

- Version: 2.10.1
- JSON serialization/deserialization
- Used for config.json and metadata files
- Simple, reliable

## Testing Strategy

Manual testing checklist:

**Initial Setup**
- [ ] Fresh install grants all permissions
- [ ] Camera initializes successfully
- [ ] Server starts and shows IP address

**Recording**
- [ ] Manual button triggers recording
- [ ] HTTP API triggers recording
- [ ] Recording auto-stops after duration
- [ ] Video file saved to recordings/
- [ ] Metadata JSON created
- [ ] Recording appears in list

**API**
- [ ] GET /api/status returns correct data
- [ ] POST /api/record starts recording
- [ ] GET /api/recordings lists all recordings
- [ ] DELETE /api/recordings/{filename} removes recording
- [ ] DELETE /api/recordings clears all
- [ ] GET /api/recordings/{filename}/stream returns video with range support
- [ ] GET /api/camera/preview returns JPEG snapshot
- [ ] POST /api/lm/shot-detected with ball data saves metadata
- [ ] PATCH /api/recordings/{filename}/metadata updates club data
- [ ] GET /api/recordings includes shotMetadata in response

**Web Interface**
- [ ] Open http://[phone-ip]:8080 in browser
- [ ] Most recent recording auto-plays and loops
- [ ] Click recording in list to play it
- [ ] New recording appears within 3 seconds of creation
- [ ] Video seeking/scrubbing works smoothly
- [ ] Enable live preview shows camera feed (~1fps)
- [ ] Responsive design works on mobile and desktop
- [ ] Shot metadata displays in video player when available
- [ ] Club data auto-updates within 2.5s when added via PATCH
- [ ] Recordings list shows ball/club data summary

**Edge Cases**
- [ ] Recording while already recording (rejected)
- [ ] Low storage space (graceful error)
- [ ] Network change (IP address updates)
- [ ] App backgrounded during recording (continues)
- [ ] Service killed by system (restarts)

## Recently Completed Features

- **Remote Web View Interface** (October 2025) - Fully implemented web interface for viewing/playing recordings remotely
  - Access at http://[phone-ip]:8080
  - Auto-play most recent recording with loop
  - Real-time polling for new recordings
  - HTTP range request support for video seeking
  - Live camera preview (toggle on/off)
  - Implementation details: [WEB_INTERFACE_PLAN.md](docs/WEB_INTERFACE_PLAN.md)

- **Shot Metadata from Launch Monitor** (October 2025) - Complete launch monitor integration with shot data capture
  - Ball data fields: ball speed, launch angle, spin rate, carry distance, etc. (12 total)
  - Club data fields: club speed, club path, face angle, smash factor, etc. (9 total)
  - All fields optional to support different launch monitor capabilities
  - Two-step workflow: ball data sent with shot-detected, club data sent separately via PATCH
  - Metadata stored in recording JSON files alongside videos
  - Displayed in Android app recordings list with key metrics
  - Displayed in web interface with detailed grid layout
  - Web interface auto-updates when club data arrives (no refresh needed)
  - Complete API documentation in [USAGE.md](docs/USAGE.md)

## Future Enhancements

See [TODO.md](../TODO.md) for the complete feature backlog with priorities.

### Quick Reference

Some potential features to add:

1. **Video Playback**
   - In-app ExoPlayer integration
   - Slow-motion playback controls
   - Frame-by-frame scrubbing

2. **Configuration UI**
   - Settings screen for duration
   - Preset durations (3s, 5s, 7s, 10s)
   - Test recording feature

3. **Cloud Upload**
   - Google Drive integration
   - Automatic upload after recording
   - Background upload service

4. **Sharing**
   - Share to other apps
   - Export to gallery
   - Generate shareable links

5. **Advanced Features**
   - Bluetooth remote trigger
   - Voice command trigger
   - Multiple camera angles (if device supports)
   - Drawing/annotation on videos

## Troubleshooting Guide

**Build errors:**
- Sync Gradle files
- Invalidate caches and restart Android Studio
- Check JDK version (requires JDK 17)

**Runtime crashes:**
- Check logcat for stack traces
- Verify permissions granted
- Ensure device meets minimum SDK (26)

**Camera not working:**
- Check other apps aren't using camera
- Verify camera permission granted
- Try restarting device

**Server not accessible:**
- Verify devices on same WiFi network
- Check firewall settings
- Test with localhost (adb forward)

**Recording not saving:**
- Check available storage
- Verify write permissions
- Check logs for specific error
