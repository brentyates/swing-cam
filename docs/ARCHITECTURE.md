# SwingCam - Project Summary

## What Was Built

A complete native Android app for recording golf swings using your Pixel 9's slow-motion camera, controllable via HTTP API.

## Project Structure

```
swing-cam/
├── app/
│   ├── build.gradle.kts                           # App dependencies & config
│   ├── proguard-rules.pro                         # ProGuard rules
│   └── src/main/
│       ├── AndroidManifest.xml                    # App manifest & permissions
│       ├── java/com/example/swingcam/
│       │   ├── MainActivity.kt                    # Main app activity
│       │   ├── camera/
│       │   │   └── CameraManager.kt              # Camera & recording logic
│       │   ├── data/
│       │   │   ├── Config.kt                     # Simple config (duration only)
│       │   │   ├── RecordingMetadata.kt          # Metadata model
│       │   │   └── RecordingRepository.kt        # File management
│       │   ├── server/
│       │   │   └── HttpServerService.kt          # Ktor HTTP server
│       │   └── ui/
│       │       └── RecordingAdapter.kt           # RecyclerView adapter
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml             # Main UI layout
│           │   └── item_recording.xml            # Recording list item
│           ├── values/
│           │   ├── strings.xml                   # String resources
│           │   ├── colors.xml                    # Color definitions
│           │   └── themes.xml                    # Material theme
│           ├── xml/
│           │   ├── file_paths.xml                # FileProvider paths
│           │   ├── backup_rules.xml              # Backup configuration
│           │   └── data_extraction_rules.xml     # Data extraction rules
│           ├── drawable/
│           │   └── ic_launcher_foreground.xml    # App icon
│           └── mipmap-anydpi-v26/
│               ├── ic_launcher.xml               # Adaptive icon
│               └── ic_launcher_round.xml         # Round icon
│
├── build.gradle.kts                               # Root build config
├── settings.gradle.kts                            # Gradle settings
├── gradle.properties                              # Gradle properties
├── gradlew                                        # Gradle wrapper script
├── gradle/wrapper/
│   └── gradle-wrapper.properties                 # Gradle wrapper config
│
├── .gitignore                                     # Git ignore rules
├── .gitattributes                                 # Git attributes
├── local.properties                               # Android SDK path
│
├── README.md                                      # Project documentation
├── USAGE.md                                       # Usage guide
├── CLAUDE.md                                      # AI assistant guide
└── PROJECT_SUMMARY.md                             # This file
```

## Key Features Implemented

### 1. Camera Recording
- **CameraX integration** with highest quality mode
- **Automatic slow-motion** using Pixel 9's camera capabilities
- **Configurable duration** (default 5 seconds)
- **Auto-stop** after configured duration
- **Metadata tracking** for each recording

### 2. HTTP API Server
- **Ktor embedded server** running on port 8080
- **Foreground service** keeps server running
- **REST API endpoints:**
  - `POST /api/record` - Start recording
  - `GET /api/status` - Get camera status
  - `GET /api/recordings` - List recordings
  - `DELETE /api/recordings/{filename}` - Delete recording
  - `DELETE /api/recordings` - Delete all

### 3. User Interface
- **Material Design** with landscape orientation
- **Server control** (start/stop)
- **IP address display** for API access
- **Recording list** with timestamps
- **Manual record button** for testing
- **Delete functionality** per recording

### 4. Data Management
- **JSON configuration** (`config.json`)
- **JSON metadata** for each recording
- **Internal storage** (app-private)
- **Repository pattern** for data access

## Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | Kotlin | 1.9.20 |
| Build | Gradle | 8.2 |
| Min SDK | Android 8.0 | 26 |
| Target SDK | Android 14 | 34 |
| Camera | CameraX | 1.3.1 |
| HTTP Server | Ktor | 2.3.7 |
| JSON | Gson | 2.10.1 |
| Video Player | ExoPlayer (media3) | 1.2.1 |
| UI | Material Design | 1.11.0 |

## Configuration

The app uses minimal configuration - only duration is configurable:

**config.json:**
```json
{
  "duration": 5
}
```

Everything else (resolution, FPS, quality) is automatically handled by the Pixel 9's slow-motion camera mode.

## API Examples

```bash
# Check if phone is on network and server is running
curl http://192.168.1.100:8080/

# Start a recording
curl -X POST http://192.168.1.100:8080/api/record

# Check status
curl http://192.168.1.100:8080/api/status

# List all recordings
curl http://192.168.1.100:8080/api/recordings

# Delete a recording
curl -X DELETE http://192.168.1.100:8080/api/recordings/swing_20241022_143530.mp4
```

## Permissions Required

- ✅ `CAMERA` - Video recording
- ✅ `RECORD_AUDIO` - Audio in videos
- ✅ `INTERNET` - HTTP server
- ✅ `FOREGROUND_SERVICE` - Keep server running
- ✅ `POST_NOTIFICATIONS` - Server notification (Android 13+)

## Next Steps to Use

1. **Open in Android Studio**
   - File → Open → Navigate to the `swing-cam` project directory
   - Wait for Gradle sync

2. **Connect Pixel 9** via USB (enable USB debugging)

3. **Build & Run**
   - Click Run button (▶️)
   - Select your Pixel 9 device

4. **Grant permissions** when prompted

5. **Start server** in the app

6. **Trigger recording** from your simulator or another device:
   ```bash
   curl -X POST http://PHONE_IP:8080/api/record
   ```

## File Storage

Recordings are stored in the app's private internal storage:

```
/data/data/com.example.swingcam/files/recordings/
├── swing_20241022_143530.mp4
├── swing_20241022_143530.json
├── swing_20241022_144120.mp4
└── swing_20241022_144120.json
```

**Metadata example:**
```json
{
  "filename": "swing_20241022_143530.mp4",
  "timestamp": "2024-10-22 14:35:30",
  "duration": 5,
  "fileSize": 12450000,
  "filePath": "/data/data/com.example.swingcam/files/recordings/swing_20241022_143530.mp4"
}
```

## Design Decisions

### Why Kotlin?
Native Android development for best performance and camera API access.

### Why Ktor?
Lightweight, Kotlin-native HTTP server that's easy to embed in Android apps.

### Why CameraX?
Modern camera API with lifecycle awareness and consistent behavior across devices.

### Why Minimal Config?
Pixel 9's camera is sophisticated enough to handle all optimization automatically. Only duration needs to be user-configurable.

### Why Internal Storage?
Simpler permissions, automatic cleanup on uninstall, no need for scoped storage complexity.

### Why No Authentication?
Local network only, assumes trusted environment. Can add auth later if needed.

## Future Enhancements

Planned features:
- [ ] In-app video playback with ExoPlayer
- [ ] Duration configuration UI
- [ ] Cloud upload (Google Drive)
- [ ] Bluetooth remote trigger
- [ ] Share to other apps
- [ ] Slow-motion playback controls

## Testing Checklist

- [ ] App installs successfully
- [ ] Permissions granted
- [ ] Camera initializes
- [ ] Server starts
- [ ] IP address displays
- [ ] Manual recording works
- [ ] HTTP API recording works
- [ ] Recordings appear in list
- [ ] Delete works
- [ ] Metadata accurate
- [ ] App survives background/foreground
- [ ] Service persists

## Known Limitations

1. **No playback UI yet** - ExoPlayer included but not implemented
2. **No cloud upload** - Local storage only
3. **No authentication** - Trust local network
4. **Single recording** - Can't queue multiple recordings
5. **No configuration UI** - Must edit config.json manually
6. **Pixel-specific** - Optimized for Pixel 9, may work on other devices

## Build Requirements

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34
- Gradle 8.2
- Android device with API 26+ (preferably Pixel 9)

## License

MIT License

---

**Ready to build!** 🎥⛳

Open the project in Android Studio and run it on your Pixel 9.
