package com.example.swingcam.data

/**
 * Ball data from launch monitor
 * All fields are optional (nullable) since different launch monitors provide different data
 */
data class BallData(
    val ballSpeed: Double? = null,           // mph or km/h
    val launchAngle: Double? = null,         // degrees
    val launchDirection: Double? = null,     // degrees (positive = right, negative = left)
    val spinRate: Int? = null,               // rpm
    val spinAxis: Double? = null,            // degrees
    val backSpin: Int? = null,               // rpm
    val sideSpin: Int? = null,               // rpm (positive = right, negative = left)
    val carryDistance: Double? = null,       // yards or meters
    val totalDistance: Double? = null,       // yards or meters
    val maxHeight: Double? = null,           // yards or meters
    val landingAngle: Double? = null,        // degrees
    val hangTime: Double? = null             // seconds
)

/**
 * Club data from launch monitor
 * All fields are optional (nullable) since different launch monitors provide different data
 */
data class ClubData(
    val clubSpeed: Double? = null,           // mph or km/h
    val clubPath: Double? = null,            // degrees (positive = in-to-out, negative = out-to-in)
    val faceAngle: Double? = null,           // degrees (positive = open, negative = closed)
    val faceToPath: Double? = null,          // degrees
    val attackAngle: Double? = null,         // degrees (positive = up, negative = down)
    val dynamicLoft: Double? = null,         // degrees
    val smashFactor: Double? = null,         // ratio (ball speed / club speed)
    val lowPoint: Double? = null,            // inches before/after ball
    val clubType: String? = null             // e.g., "Driver", "7-iron", "Wedge"
)

/**
 * Complete shot metadata combining ball and club data
 */
data class ShotMetadata(
    val ballData: BallData? = null,
    val clubData: ClubData? = null,
    val timestamp: Long = System.currentTimeMillis()  // When the metadata was recorded
)
