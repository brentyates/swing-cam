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

### PATCH `/api/recordings/{filename}/metadata`
Updates shot metadata for a recording (typically club data sent after ball data).

**Request Body (JSON):**
```json
{
  "clubSpeed": 102.5,
  "clubType": "Driver",
  "smashFactor": 1.48,
  "attackAngle": 3.2,
  "clubPath": 1.5,
  "faceAngle": 0.8,
  "dynamicLoft": 12.5,
  "faceToPath": -0.7,
  "lowPoint": -2.1
}
```

**Example:**
```bash
curl -X PATCH http://192.168.1.100:8080/api/recordings/swing_20241022_143530.mp4/metadata \
  -H "Content-Type: application/json" \
  -d '{
    "clubSpeed": 102.5,
    "clubType": "Driver",
    "smashFactor": 1.48
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "Metadata updated"
}
```

## Launch Monitor Integration

SwingCam supports integration with launch monitors to capture shot data alongside video recordings.

### POST `/api/lm/shot-detected`
Signals that a shot was detected and should extract the last N seconds from the continuous recording buffer. Optionally includes ball data from the launch monitor.

**Request Body (JSON) - Optional:**
```json
{
  "ballSpeed": 165.3,
  "launchAngle": 12.5,
  "launchDirection": -2.1,
  "spinRate": 2450,
  "spinAxis": 5.2,
  "backSpin": 2430,
  "sideSpin": 215,
  "carryDistance": 275.5,
  "totalDistance": 285.0,
  "maxHeight": 32.8,
  "landingAngle": 45.2,
  "hangTime": 5.8
}
```

**Example with ball data:**
```bash
curl -X POST http://192.168.1.100:8080/api/lm/shot-detected \
  -H "Content-Type: application/json" \
  -d '{
    "ballSpeed": 165.3,
    "launchAngle": 12.5,
    "carryDistance": 275.5,
    "spinRate": 2450
  }'
```

**Response:**
```json
{
  "status": "success",
  "filename": "swing_20241022_143530.mp4",
  "message": "Shot extracted successfully"
}
```

### Launch Monitor Workflow

1. **Arm the system** before the shot:
   ```bash
   curl -X POST http://192.168.1.100:8080/api/lm/arm
   ```

2. **Hit your shot** - camera continuously records to buffer

3. **Send shot detection** with ball data when launch monitor detects impact:
   ```bash
   curl -X POST http://192.168.1.100:8080/api/lm/shot-detected \
     -H "Content-Type: application/json" \
     -d '{"ballSpeed": 165.3, "carryDistance": 275.5, "launchAngle": 12.5}'
   ```

4. **Optionally update with club data** (sent separately by some launch monitors):
   ```bash
   curl -X PATCH http://192.168.1.100:8080/api/recordings/swing_20241022_143530.mp4/metadata \
     -H "Content-Type: application/json" \
     -d '{"clubSpeed": 112.5, "clubType": "Driver", "smashFactor": 1.47}'
   ```

5. **Repeat** - System automatically re-arms after successful extraction

### Shot Metadata Fields

All metadata fields are optional - different launch monitors provide different data sets.

**Ball Data:**
- `ballSpeed` - Ball speed (mph or km/h)
- `launchAngle` - Vertical launch angle (degrees)
- `launchDirection` - Horizontal direction (degrees, + = right, - = left)
- `spinRate` - Total spin rate (rpm)
- `spinAxis` - Spin axis tilt (degrees)
- `backSpin` - Back spin component (rpm)
- `sideSpin` - Side spin component (rpm, + = right, - = left)
- `carryDistance` - Carry distance (yards or meters)
- `totalDistance` - Total distance with roll (yards or meters)
- `maxHeight` - Apex height (yards or meters)
- `landingAngle` - Descent angle at landing (degrees)
- `hangTime` - Time in air (seconds)

**Club Data:**
- `clubSpeed` - Club head speed (mph or km/h)
- `clubPath` - Club path (degrees, + = in-to-out, - = out-to-in)
- `faceAngle` - Face angle at impact (degrees, + = open, - = closed)
- `faceToPath` - Face to path relationship (degrees)
- `attackAngle` - Attack angle (degrees, + = up, - = down)
- `dynamicLoft` - Dynamic loft at impact (degrees)
- `smashFactor` - Smash factor (ball speed / club speed)
- `lowPoint` - Low point position (inches before/after ball)
- `clubType` - Club name (e.g., "Driver", "7-iron", "Pitching Wedge")

### Viewing Shot Metadata

Shot metadata is automatically displayed in:

1. **Android App** - Recordings list shows key metrics (ball speed, carry distance, club type)
2. **Web Interface** - Video player shows detailed grid with all available metrics
3. **API Response** - GET `/api/recordings` includes full `shotMetadata` object

**Example API Response with Metadata:**
```json
[
  {
    "filename": "swing_20241022_143530.mp4",
    "timestamp": "2024-10-22 14:35:30",
    "duration": 2,
    "fileSize": 12450000,
    "shotMetadata": {
      "ballData": {
        "ballSpeed": 165.3,
        "launchAngle": 12.5,
        "carryDistance": 275.5,
        "spinRate": 2450
      },
      "clubData": {
        "clubSpeed": 112.5,
        "clubType": "Driver",
        "smashFactor": 1.47
      },
      "timestamp": 1729612530000
    }
  }
]
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
