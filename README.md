# SwingCam - Android Golf Swing Recording App

A native Android app for recording golf swings with HTTP API control, designed to run entirely on your Pixel 9 phone.

## Concept

- **Simple Setup**: Mount your phone on a tripod, no external camera required
- **HTTP API Control**: Trigger recordings via external HTTP requests (from simulator, web browser, or custom triggers)
- **High-Quality Recording**: Uses phone's camera with configurable resolution and FPS
- **In-App Playback**: Review your swings immediately
- **Metadata Tracking**: Automatic timestamp and settings tracking for each recording

## Features

- Native Android app (Kotlin)
- Embedded HTTP server (Ktor) for external trigger control
- Camera2 API for high-quality video recording
- Configurable recording settings (resolution, FPS, duration)
- In-app recording management and playback
- Automatic metadata storage (JSON format)
- Foreground service keeps server running

## Tech Stack

- **Language**: Kotlin
- **Camera**: CameraX Video API
- **HTTP Server**: Ktor (embedded Netty server)
- **UI**: Material Design, ViewBinding
- **Video Playback**: ExoPlayer (planned)
- **Storage**: Local file system with JSON metadata

## API Endpoints

The app runs an HTTP server on port 8080 when started:

### GET `/api/status`
Get current camera and recording status.

**Response:**
```json
{
  "recording": false,
  "config": {
    "duration": 5
  }
}
```

### POST `/api/record`
Start a new recording.

**Response:**
```json
{
  "success": true,
  "message": "Recording started",
  "duration": 5
}
```

### GET `/api/recordings`
List all recordings.

**Response:**
```json
{
  "recordings": [
    {
      "filename": "swing_20241022_143022.mp4",
      "timestamp": "2024-10-22 14:30:22",
      "duration": 5,
      "fileSize": 12345678
    }
  ]
}
```

### DELETE `/api/recordings/{filename}`
Delete a specific recording.

### DELETE `/api/recordings`
Delete all recordings.

## Configuration

Settings are stored in `config.json` in the app's private storage:

```json
{
  "duration": 5
}
```

- **duration**: Recording length in seconds

**Note**: Resolution, FPS, and quality are automatically handled by the Pixel 9's slow-motion camera mode. The app uses the highest quality available for the best slow-motion capture.

## Usage

1. **Install the app** on your Android phone
2. **Grant permissions** (Camera, Microphone, Notifications)
3. **Start the HTTP server** from the app
4. **Note the IP address** displayed (e.g., `http://192.168.1.100:8080`)
5. **Trigger recordings** via HTTP:
   ```bash
   curl -X POST http://192.168.1.100:8080/api/record
   ```
6. **Review recordings** in the app or via API

## Building

### Prerequisites

- Android Studio Hedgehog or later
- Android SDK 34
- Gradle 8.2+
- JDK 17

### Build Steps

1. Open Android Studio
2. File → Open → select `swing-cam` directory
3. Wait for Gradle sync to complete
4. Connect Pixel 9 via USB
5. Click Run (▶️) button
6. Select your device and wait for installation

## Permissions

- **CAMERA**: Required for video recording
- **RECORD_AUDIO**: Required for audio in videos
- **INTERNET**: Required for HTTP server
- **POST_NOTIFICATIONS**: Required for foreground service notification (Android 13+)

## Project Structure

```
app/src/main/java/com/example/swingcam/
├── MainActivity.kt                  # Main activity and app orchestration
├── camera/
│   └── CameraManager.kt            # Camera2 API wrapper
├── data/
│   ├── Config.kt                   # Configuration data class
│   ├── RecordingMetadata.kt        # Metadata tracking
│   └── RecordingRepository.kt      # Recording management
├── server/
│   └── HttpServerService.kt        # Ktor HTTP server
└── ui/
    └── RecordingAdapter.kt         # RecyclerView adapter
```

## Design Philosophy

- **Simplicity First**: One device, minimal configuration
- **Phone-Native**: Leverages Pixel 9's excellent slow-motion capabilities
- **API-Driven**: HTTP triggers allow external control (simulator, web UI, scripts)
- **Focused**: Just recording and playback, no unnecessary features

## Roadmap

See [TODO.md](TODO.md) for the complete feature backlog and planned enhancements.

## Documentation

- **[Quick Start Guide](docs/QUICKSTART.md)** - Get up and running in 5 minutes
- **[Usage Guide](docs/USAGE.md)** - Detailed API documentation and examples
- **[Architecture](docs/ARCHITECTURE.md)** - Project structure and design decisions
- **[CLAUDE.md](CLAUDE.md)** - Development guide for AI assistants
- **[TODO.md](TODO.md)** - Feature requests and backlog

## Why SwingCam?

1. **Simple setup**: Just your phone on a tripod
2. **Excellent quality**: Pixel 9's slow-motion mode is optimized automatically
3. **Portable**: Easy to bring to the course, range, or simulator
4. **No complexity**: Just set duration, let the phone handle camera settings
5. **API control**: Trigger recordings from your golf simulator or other apps

## License

MIT License
