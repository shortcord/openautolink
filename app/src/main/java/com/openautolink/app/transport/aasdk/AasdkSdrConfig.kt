package com.openautolink.app.transport.aasdk

/**
 * Configuration for the AA Service Discovery Response (SDR).
 *
 * Passed to native code which builds the protobuf SDR that tells
 * the phone what this head unit supports.
 *
 * Fields are accessed from C++ via JNI GetIntField/GetObjectField.
 */
class AasdkSdrConfig(
    /** Video width in pixels (e.g. 1920). */
    @JvmField val videoWidth: Int = 1920,

    /** Video height in pixels (e.g. 1080). */
    @JvmField val videoHeight: Int = 1080,

    /** Video frames per second (e.g. 60). */
    @JvmField val videoFps: Int = 60,

    /** Video DPI for UI scaling (e.g. 160). */
    @JvmField val videoDpi: Int = 160,

    /** Stable inset width margin in pixels. */
    @JvmField val marginWidth: Int = 0,

    /** Stable inset height margin in pixels. */
    @JvmField val marginHeight: Int = 0,

    /** Pixel aspect ratio × 10000 (0 = square pixels). */
    @JvmField val pixelAspectE4: Int = 0,

    /** Bluetooth MAC address of the car (for BT pairing service). */
    @JvmField val btMacAddress: String = "",

    /** Vehicle make (e.g. "Chevrolet"). */
    @JvmField val vehicleMake: String = "OpenAutoLink",

    /** Vehicle model (e.g. "Blazer EV"). */
    @JvmField val vehicleModel: String = "Direct",

    /** Vehicle year (e.g. "2024"). */
    @JvmField val vehicleYear: String = "2024",

    /** Driver position: 0=left, 1=right. */
    @JvmField val driverPosition: Int = 0,

    /** Hide clock in AA status bar. */
    @JvmField val hideClock: Boolean = false,

    /** Hide signal strength in AA status bar. */
    @JvmField val hideSignal: Boolean = false,

    /** Hide battery indicator in AA status bar. */
    @JvmField val hideBattery: Boolean = false,

    /** Auto-negotiate video: true = send all resolutions/codecs, false = send only the configured one. */
    @JvmField val autoNegotiate: Boolean = true,

    /** Video codec preference for manual mode: "h264" or "h265". */
    @JvmField val videoCodec: String = "h265",

    /** Physical pixel density of the AAOS display (from DisplayMetrics.densityDpi). */
    @JvmField val realDensity: Int = 0,

    /** AA stable content insets — tells phone where to keep UI inside. */
    @JvmField val safeAreaTop: Int = 0,
    @JvmField val safeAreaBottom: Int = 0,
    @JvmField val safeAreaLeft: Int = 0,
    @JvmField val safeAreaRight: Int = 0,

    /**
     * Target layout width in dp for per-tier DPI scaling (auto-negotiate mode).
     * When > 0, each resolution tier gets a computed DPI so that
     * screenWidthDp = tierPixelWidth * 160 / dpi ≈ this value.
     * 0 = disabled (use videoDpi uniformly for all tiers).
     */
    @JvmField val targetLayoutWidthDp: Int = 0,

    /** Fuel types from VHAL INFO_FUEL_TYPE (e.g. [10] = ELECTRIC). */
    @JvmField val fuelTypes: IntArray = intArrayOf(),

    /** EV connector types from VHAL INFO_EV_CONNECTOR_TYPE (e.g. [1,5] = J1772+CCS1). */
    @JvmField val evConnectorTypes: IntArray = intArrayOf(),
)
