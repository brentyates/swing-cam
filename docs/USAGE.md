# SwingCam Usage Guide

## Quick Start

1. **Install** the app on your Pixel 9
2. **Grant permissions** when prompted (Camera, Microphone, Notifications)
3. **Tap "Start Server"** in the app
4. **Note the IP address** shown (e.g., `http://192.168.1.100:8080`)

## Triggering Recordings

### From Another Device (Recommended)

Trigger recordings from any device on your network:

```bash
# Start a recording
curl -X POST http://192.168.1.100:8080/api/record

# Check status
curl http://192.168.1.100:8080/api/status

# List recordings
curl http://192.168.1.100:8080/api/recordings
```

### From Golf Simulator

If your golf simulator software supports custom webhooks or HTTP calls, configure it to POST to:
```
http://YOUR_PHONE_IP:8080/api/record
```

### Manual Recording

Use the "Manual Record" button in the app for testing or one-off recordings.

## API Endpoints

### POST `/api/record`
Starts a new slow-motion recording using configured duration.

**Response:**
```json
{
  "success": true,
  "message": "Recording started",
  "duration": 5
}
```

**Error Response:**
```json
{
  "success": false,
  "message": "Already recording"
}
```

### GET `/api/status`
Returns current recording status and configuration.

**Response:**
```json
{
  "recording": false,
  "config": {
    "duration": 5
  }
}
```

### GET `/api/recordings`
Returns list of all saved recordings.

**Response:**
```json
{
  "recordings": [
    {
      "filename": "swing_20241022_143530.mp4",
      "timestamp": "2024-10-22 14:35:30",
      "duration": 5,
      "fileSize": 12450000
    }
  ]
}
```

### DELETE `/api/recordings/{filename}`
Deletes a specific recording.

**Example:**
```bash
curl -X DELETE http://192.168.1.100:8080/api/recordings/swing_20241022_143530.mp4
```

### DELETE `/api/recordings`
Deletes all recordings.

**Example:**
```bash
curl -X DELETE http://192.168.1.100:8080/api/recordings
```

## Configuration

Duration can be changed by editing `config.json` in the app's private storage:

```json
{
  "duration": 5
}
```

Default is 5 seconds. Common values:
- 3 seconds: Quick swings
- 5 seconds: Standard (default)
- 7 seconds: Full swing with follow-through
- 10 seconds: Extended capture

## Tips

### Camera Positioning
- **Side view**: Position phone perpendicular to target line, 10-15 feet away
- **Face-on view**: Position in front, far enough to capture full swing width
- **Down-the-line**: Position behind looking toward target

### Setup
1. Use a phone tripod mount for stability
2. Frame your swing area in landscape mode
3. Ensure good lighting (natural light works best)
4. Start the server before your session
5. Keep phone plugged in if recording many swings

### Network
- Phone and trigger device must be on same WiFi network
- Server runs on port 8080
- IP address shown in app when server starts
- Server automatically stops when app is closed

### Recording Files
- Files saved as `swing_YYYYMMDD_HHMMSS.mp4`
- View in app or share to other apps
- Videos use Pixel 9's slow-motion mode automatically
- Metadata stored in companion `.json` files

## Troubleshooting

**Server won't start**
- Check notification permissions
- Ensure port 8080 isn't in use
- Try restarting the app

**Can't trigger from other device**
- Verify both devices on same WiFi
- Check firewall settings
- Test with `curl -v` to see detailed error

**Recording failed**
- Ensure camera permission granted
- Check available storage space
- Verify camera not in use by another app

**Videos not saving**
- Check available storage
- Look for error in app status
- Ensure duration isn't too long for available space
