// SwingCam Remote View - Client-side JavaScript

// Configuration
const POLLING_INTERVAL = 2500; // Poll every 2.5 seconds
const PREVIEW_INTERVAL = 1000; // Preview update every 1 second (1 fps)

// State
let recordings = [];
let currentRecording = null;
let pollingTimer = null;
let previewTimer = null;
let previewEnabled = false;

// DOM Elements
const videoPlayer = document.getElementById('videoPlayer');
const videoContainer = document.getElementById('videoContainer');
const noVideoMessage = document.getElementById('noVideoMessage');
const loadingMessage = document.getElementById('loadingMessage');
const videoInfo = document.getElementById('videoInfo');
const videoTitle = document.getElementById('videoTitle');
const videoDuration = document.getElementById('videoDuration');
const videoMetadata = document.getElementById('videoMetadata');
const recordingsList = document.getElementById('recordingsList');
const statusDot = document.getElementById('statusDot');
const statusText = document.getElementById('statusText');
const previewSection = document.getElementById('previewSection');
const previewToggle = document.getElementById('previewToggle');
const previewContainer = document.getElementById('previewContainer');
const previewImage = document.getElementById('previewImage');

// Initialize app
function init() {
    console.log('SwingCam Remote View - Initializing...');

    // Set up event listeners
    setupEventListeners();

    // Load initial recordings
    loadRecordings();

    // Start polling for updates
    startPolling();
}

// Set up event listeners
function setupEventListeners() {
    // Video player events
    videoPlayer.addEventListener('loadedmetadata', onVideoLoaded);
    videoPlayer.addEventListener('error', onVideoError);

    // Preview toggle (Phase 4)
    if (previewToggle) {
        previewToggle.addEventListener('click', togglePreview);
    }
}

// Load recordings from server
async function loadRecordings() {
    try {
        showLoading(true);
        const response = await fetch('/api/recordings');

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        const data = await response.json();
        recordings = data;

        updateStatus('connected', `Live (${recordings.length} recordings)`);
        renderRecordingsList();

        // Auto-load most recent video if available
        if (recordings.length > 0 && !currentRecording) {
            loadVideo(recordings[0]);
        } else if (recordings.length === 0) {
            showNoVideoMessage(true);
        }

        showLoading(false);
    } catch (error) {
        console.error('Error loading recordings:', error);
        updateStatus('error', 'Connection error');
        showError('Failed to load recordings. Retrying...');
        showLoading(false);
    }
}

// Render recordings list
function renderRecordingsList() {
    if (recordings.length === 0) {
        recordingsList.innerHTML = '<p class="loading-text">No recordings available</p>';
        return;
    }

    recordingsList.innerHTML = '';

    recordings.forEach((recording, index) => {
        const item = createRecordingItem(recording, index);
        recordingsList.appendChild(item);
    });
}

// Create recording list item element
function createRecordingItem(recording, index) {
    const item = document.createElement('div');
    item.className = 'recording-item';

    if (currentRecording && currentRecording.filename === recording.filename) {
        item.classList.add('active');
    }

    const timestamp = formatTimestamp(recording.timestamp);
    const duration = formatDuration(recording.duration);
    const fileSize = formatFileSize(recording.fileSize);

    // Build shot metadata display
    let metadataHtml = '';
    if (recording.shotMetadata) {
        const ballData = recording.shotMetadata.ballData;
        const clubData = recording.shotMetadata.clubData;

        if (ballData || clubData) {
            metadataHtml = '<div class="recording-metadata">';

            if (ballData) {
                const ballInfo = [];
                if (ballData.ballSpeed) ballInfo.push(`${ballData.ballSpeed.toFixed(1)} mph`);
                if (ballData.carryDistance) ballInfo.push(`${ballData.carryDistance.toFixed(0)} yds`);
                if (ballInfo.length > 0) {
                    metadataHtml += `<div class="metadata-line">${ballInfo.join(' • ')}</div>`;
                }
            }

            if (clubData && clubData.clubType) {
                metadataHtml += `<div class="metadata-line">${clubData.clubType}</div>`;
            }

            metadataHtml += '</div>';
        }
    }

    item.innerHTML = `
        <div class="recording-name">${timestamp}</div>
        <div class="recording-info">
            <span>${duration}</span>
            <span>${fileSize}</span>
        </div>
        ${metadataHtml}
    `;

    item.addEventListener('click', () => loadVideo(recording));

    return item;
}

// Load and play a video
function loadVideo(recording) {
    console.log('Loading video:', recording.filename);

    currentRecording = recording;

    // Update video source with streaming endpoint
    videoPlayer.src = `/api/recordings/${recording.filename}/stream`;

    // Show video player, hide messages
    showNoVideoMessage(false);
    videoPlayer.style.display = 'block';

    // Update video info
    videoTitle.textContent = formatTimestamp(recording.timestamp);
    videoDuration.textContent = `Duration: ${formatDuration(recording.duration)} • Size: ${formatFileSize(recording.fileSize)}`;
    videoInfo.style.display = 'flex';

    // Update shot metadata if available
    if (recording.shotMetadata && (recording.shotMetadata.ballData || recording.shotMetadata.clubData)) {
        const metadataHtml = formatShotMetadata(recording.shotMetadata);
        videoMetadata.innerHTML = metadataHtml;
        videoMetadata.style.display = 'block';
    } else {
        videoMetadata.style.display = 'none';
    }

    // Highlight active recording in list
    renderRecordingsList();

    // Auto-play when loaded
    videoPlayer.load();
    videoPlayer.play().catch(error => {
        console.warn('Auto-play prevented:', error);
        // Browser may block auto-play, user will need to click play
    });
}

// Video loaded event handler
function onVideoLoaded() {
    console.log('Video loaded successfully');

    // Try to auto-play (in case initial play() was blocked)
    if (videoPlayer.paused) {
        videoPlayer.play().catch(error => {
            console.warn('Auto-play prevented:', error);
        });
    }
}

// Video error event handler
function onVideoError(event) {
    console.error('Video playback error:', event);
    showError('Failed to load video. Please try another recording.');
}

// Start polling for new recordings
function startPolling() {
    if (pollingTimer) {
        clearInterval(pollingTimer);
    }

    pollingTimer = setInterval(() => {
        checkForNewRecordings();
    }, POLLING_INTERVAL);

    console.log(`Polling started (every ${POLLING_INTERVAL}ms)`);
}

// Stop polling
function stopPolling() {
    if (pollingTimer) {
        clearInterval(pollingTimer);
        pollingTimer = null;
    }
}

// Check for new recordings
async function checkForNewRecordings() {
    try {
        const response = await fetch('/api/recordings');

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const newRecordings = await response.json();

        // Check if there are new recordings
        if (newRecordings.length > recordings.length) {
            console.log('New recording(s) detected!');

            const previousCount = recordings.length;
            recordings = newRecordings;

            updateStatus('connected', `Live (${recordings.length} recordings)`);
            renderRecordingsList();

            // Auto-load newest recording only if video file is ready (fileSize > 0)
            if (recordings.length > 0) {
                const newestRecording = recordings[0];
                if (newestRecording.fileSize > 0) {
                    loadVideo(newestRecording);
                } else {
                    console.log('New recording detected but video not ready yet (fileSize=0), waiting...');
                }
            }
        } else if (newRecordings.length !== recordings.length) {
            // Recording count changed (deleted)
            recordings = newRecordings;
            updateStatus('connected', `Live (${recordings.length} recordings)`);
            renderRecordingsList();

            // If current video was deleted, load most recent
            if (currentRecording) {
                const stillExists = recordings.find(r => r.filename === currentRecording.filename);
                if (!stillExists && recordings.length > 0) {
                    loadVideo(recordings[0]);
                } else if (recordings.length === 0) {
                    currentRecording = null;
                    videoPlayer.style.display = 'none';
                    videoInfo.style.display = 'none';
                    showNoVideoMessage(true);
                }
            }
        } else {
            // Same count - check if newest recording just became ready OR current recording's metadata was updated

            // First, check if the newest recording just became ready (fileSize went from 0 to non-zero)
            if (newRecordings.length > 0 && recordings.length > 0) {
                const newestNew = newRecordings[0];
                const newestOld = recordings[0];

                // If newest recording just became ready, auto-load it
                if (newestNew.filename === newestOld.filename &&
                    newestOld.fileSize === 0 &&
                    newestNew.fileSize > 0) {
                    console.log('Newest recording just became ready, auto-loading...');
                    recordings = newRecordings;
                    renderRecordingsList();
                    loadVideo(newestNew);
                    return; // Exit early, we've handled the update
                }
            }

            // Otherwise, check if current recording's metadata was updated
            if (currentRecording) {
                const updatedRecording = newRecordings.find(r => r.filename === currentRecording.filename);
                if (updatedRecording && hasMetadataChanged(currentRecording, updatedRecording)) {
                    console.log('Metadata updated for current recording!');

                    const oldFileSize = currentRecording.fileSize;
                    const newFileSize = updatedRecording.fileSize;

                    // Update current recording reference
                    currentRecording = updatedRecording;

                    // If video just became available (fileSize went from 0 to non-zero), reload video
                    if (oldFileSize === 0 && newFileSize > 0) {
                        console.log('Video file now available, reloading...');
                        loadVideo(updatedRecording);
                    } else {
                        // Just update metadata display without interrupting playback
                        updateMetadataDisplay(updatedRecording);
                    }
                }
            }

            // Always update recordings list in case any metadata changed
            recordings = newRecordings;
            renderRecordingsList();
        }
    } catch (error) {
        console.error('Polling error:', error);
        updateStatus('error', 'Connection lost');
    }
}

// Check if metadata has changed between two recordings
function hasMetadataChanged(oldRecording, newRecording) {
    // Check if shotMetadata changed
    const oldMeta = JSON.stringify(oldRecording.shotMetadata || null);
    const newMeta = JSON.stringify(newRecording.shotMetadata || null);

    // Check if fileSize changed from 0 to non-zero (video extraction completed)
    const fileSizeChanged = oldRecording.fileSize === 0 && newRecording.fileSize > 0;

    return oldMeta !== newMeta || fileSizeChanged;
}

// Update metadata display without interrupting playback
function updateMetadataDisplay(recording) {
    if (recording.shotMetadata && (recording.shotMetadata.ballData || recording.shotMetadata.clubData)) {
        const metadataHtml = formatShotMetadata(recording.shotMetadata);
        videoMetadata.innerHTML = metadataHtml;
        videoMetadata.style.display = 'block';
    } else {
        videoMetadata.style.display = 'none';
    }
}

// Toggle live preview (Phase 4)
function togglePreview() {
    previewEnabled = !previewEnabled;

    if (previewEnabled) {
        previewToggle.textContent = 'Disable Preview';
        previewToggle.classList.add('active');
        previewContainer.classList.add('active');
        startPreviewPolling();
    } else {
        previewToggle.textContent = 'Enable Preview';
        previewToggle.classList.remove('active');
        previewContainer.classList.remove('active');
        stopPreviewPolling();
    }
}

// Start preview polling (Phase 4)
function startPreviewPolling() {
    if (previewTimer) {
        clearInterval(previewTimer);
    }

    // Initial load
    updatePreview();

    // Start polling
    previewTimer = setInterval(() => {
        updatePreview();
    }, PREVIEW_INTERVAL);

    console.log('Preview polling started');
}

// Stop preview polling (Phase 4)
function stopPreviewPolling() {
    if (previewTimer) {
        clearInterval(previewTimer);
        previewTimer = null;
    }
}

// Update preview image (Phase 4)
function updatePreview() {
    // Add timestamp to prevent caching
    const timestamp = new Date().getTime();
    previewImage.src = `/api/camera/preview?t=${timestamp}`;
}

// Update connection status
function updateStatus(status, text) {
    statusDot.className = 'status-dot ' + status;
    statusText.textContent = text;
}

// Show/hide loading message
function showLoading(show) {
    if (show) {
        loadingMessage.classList.add('active');
    } else {
        loadingMessage.classList.remove('active');
    }
}

// Show/hide no video message
function showNoVideoMessage(show) {
    if (show) {
        noVideoMessage.style.display = 'block';
        videoPlayer.style.display = 'none';
    } else {
        noVideoMessage.style.display = 'none';
    }
}

// Show error message
function showError(message) {
    console.error(message);
    // Could add a toast notification here in the future
}

// Format timestamp for display
function formatTimestamp(timestamp) {
    try {
        // Expected format: "2024-10-22 14:35:30"
        const parts = timestamp.split(' ');
        const dateParts = parts[0].split('-');
        const timeParts = parts[1].split(':');

        const date = new Date(
            parseInt(dateParts[0]),
            parseInt(dateParts[1]) - 1,
            parseInt(dateParts[2]),
            parseInt(timeParts[0]),
            parseInt(timeParts[1]),
            parseInt(timeParts[2])
        );

        // Format as "Oct 22, 3:35 PM"
        const options = {
            month: 'short',
            day: 'numeric',
            hour: 'numeric',
            minute: '2-digit',
            hour12: true
        };

        return date.toLocaleString('en-US', options);
    } catch (error) {
        console.error('Error formatting timestamp:', error);
        return timestamp;
    }
}

// Format duration in seconds
function formatDuration(seconds) {
    if (seconds < 60) {
        return `${seconds.toFixed(1)}s`;
    }
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${minutes}m ${remainingSeconds.toFixed(0)}s`;
}

// Format file size
function formatFileSize(bytes) {
    if (bytes < 1024) {
        return `${bytes} B`;
    } else if (bytes < 1024 * 1024) {
        return `${(bytes / 1024).toFixed(1)} KB`;
    } else if (bytes < 1024 * 1024 * 1024) {
        return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    } else {
        return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
    }
}

// Format shot metadata for display
function formatShotMetadata(shotMetadata) {
    let html = '<div class="shot-metadata-grid">';

    // Ball data section
    if (shotMetadata.ballData) {
        const ball = shotMetadata.ballData;
        html += '<div class="metadata-section"><h4>Ball Data</h4><div class="metadata-items">';

        if (ball.ballSpeed !== undefined) {
            html += `<div class="metadata-item"><span class="label">Ball Speed:</span><span class="value">${ball.ballSpeed.toFixed(1)} mph</span></div>`;
        }
        if (ball.launchAngle !== undefined) {
            html += `<div class="metadata-item"><span class="label">Launch Angle:</span><span class="value">${ball.launchAngle.toFixed(1)}°</span></div>`;
        }
        if (ball.launchDirection !== undefined) {
            html += `<div class="metadata-item"><span class="label">Launch Direction:</span><span class="value">${ball.launchDirection.toFixed(1)}°</span></div>`;
        }
        if (ball.spinRate !== undefined) {
            html += `<div class="metadata-item"><span class="label">Spin Rate:</span><span class="value">${ball.spinRate} rpm</span></div>`;
        }
        if (ball.carryDistance !== undefined) {
            html += `<div class="metadata-item"><span class="label">Carry:</span><span class="value">${ball.carryDistance.toFixed(0)} yds</span></div>`;
        }
        if (ball.totalDistance !== undefined) {
            html += `<div class="metadata-item"><span class="label">Total:</span><span class="value">${ball.totalDistance.toFixed(0)} yds</span></div>`;
        }
        if (ball.maxHeight !== undefined) {
            html += `<div class="metadata-item"><span class="label">Max Height:</span><span class="value">${ball.maxHeight.toFixed(0)} yds</span></div>`;
        }

        html += '</div></div>';
    }

    // Club data section
    if (shotMetadata.clubData) {
        const club = shotMetadata.clubData;
        html += '<div class="metadata-section"><h4>Club Data</h4><div class="metadata-items">';

        if (club.clubType) {
            html += `<div class="metadata-item"><span class="label">Club:</span><span class="value">${club.clubType}</span></div>`;
        }
        if (club.clubSpeed !== undefined) {
            html += `<div class="metadata-item"><span class="label">Club Speed:</span><span class="value">${club.clubSpeed.toFixed(1)} mph</span></div>`;
        }
        if (club.smashFactor !== undefined) {
            html += `<div class="metadata-item"><span class="label">Smash Factor:</span><span class="value">${club.smashFactor.toFixed(2)}</span></div>`;
        }
        if (club.attackAngle !== undefined) {
            html += `<div class="metadata-item"><span class="label">Attack Angle:</span><span class="value">${club.attackAngle.toFixed(1)}°</span></div>`;
        }
        if (club.clubPath !== undefined) {
            html += `<div class="metadata-item"><span class="label">Club Path:</span><span class="value">${club.clubPath.toFixed(1)}°</span></div>`;
        }
        if (club.faceAngle !== undefined) {
            html += `<div class="metadata-item"><span class="label">Face Angle:</span><span class="value">${club.faceAngle.toFixed(1)}°</span></div>`;
        }
        if (club.dynamicLoft !== undefined) {
            html += `<div class="metadata-item"><span class="label">Dynamic Loft:</span><span class="value">${club.dynamicLoft.toFixed(1)}°</span></div>`;
        }

        html += '</div></div>';
    }

    html += '</div>';
    return html;
}

// Clean up on page unload
window.addEventListener('beforeunload', () => {
    stopPolling();
    stopPreviewPolling();
});

// Start the app when DOM is ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
