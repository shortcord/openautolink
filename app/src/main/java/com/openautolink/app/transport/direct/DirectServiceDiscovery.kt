package com.openautolink.app.transport.direct

import com.openautolink.app.proto.Common
import com.openautolink.app.proto.Control
import com.openautolink.app.proto.Media
import com.openautolink.app.proto.Sensors
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.video.AaVideoCodec.normalizedPreference

/**
 * Builds the AA ServiceDiscoveryResponse protobuf for direct mode.
 *
 * This announces our capabilities to the phone: what sensors we provide,
 * video/audio config we accept, input we handle, etc. Must include our
 * custom sensor types (VEM 23-26) for Maps battery-on-arrival.
 */
object DirectServiceDiscovery {

    data class VideoConfig(
        val width: Int = 1920,
        val height: Int = 1080,
        val fps: Int = 60,
        val dpi: Int = 160,
        val codec: String = "auto",  // "auto", "h264", "h265", or "vp9"
        val marginWidth: Int = 0,
        val marginHeight: Int = 0,
        val pixelAspectE4: Int = 0,  // 0 = auto-compute from display/video AR, >0 = manual override (10000 = 1:1)
        val displayWidth: Int = 0,   // For auto pixel_aspect computation
        val displayHeight: Int = 0,
    )

    data class VehicleIdentity(
        val make: String = "OpenAutoLink",
        val model: String = "Direct",
        val year: String = "2024",
        val vehicleId: String = "OpenAutoLink",
        val driverPosition: Control.DriverPosition = Control.DriverPosition.DRIVER_POSITION_LEFT,
    )

    fun build(
        video: VideoConfig = VideoConfig(),
        vehicle: VehicleIdentity = VehicleIdentity(),
        btMacAddress: String = "",
        useAacAudio: Boolean = false,
        hideClock: Boolean = true,
        hideSignal: Boolean = true,
        hideBattery: Boolean = true,
    ): Control.ServiceDiscoveryResponse {
        OalLog.i("DirectSD", "Building SDR: video=${video.width}x${video.height} fps=${video.fps} " +
            "dpi=${video.dpi} codec=${video.codec} pixelAspect=${video.pixelAspectE4} " +
            "display=${video.displayWidth}x${video.displayHeight} " +
            "margins=${video.marginWidth}x${video.marginHeight}")
        val services = mutableListOf<Control.Service>()

        // 1. Sensor service — announce all VHAL sensors we can provide
        services.add(buildSensorService())

        // 2. Video service
        services.add(buildVideoService(video))

        // 3. Input service (touchscreen)
        services.add(buildInputService(video.width, video.height))

        // 4. Audio services (system, speech, media)
        val audioCodec = if (useAacAudio) Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC
            else Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
        services.add(buildAudioService(AaChannel.AUDIO_SYSTEM, Media.AudioStreamType.SYSTEM, 16000, 16, 1, audioCodec))
        services.add(buildAudioService(AaChannel.AUDIO_SPEECH, Media.AudioStreamType.SPEECH, 16000, 16, 1, audioCodec))
        services.add(buildAudioService(AaChannel.AUDIO_MEDIA, Media.AudioStreamType.MEDIA, 48000, 16, 2, audioCodec))

        // 5. Mic service (PCM 16kHz mono)
        services.add(buildMicService())

        // 6. Media playback status
        services.add(Control.Service.newBuilder()
            .setId(AaChannel.MEDIA_PLAYBACK)
            .setMediaPlaybackService(Control.Service.MediaPlaybackStatusService.getDefaultInstance())
            .build())

        // 7. Navigation status
        services.add(Control.Service.newBuilder()
            .setId(AaChannel.NAVIGATION)
            .setNavigationStatusService(Control.Service.NavigationStatusService.newBuilder()
                .setMinimumIntervalMs(500)
                .setType(Control.Service.NavigationStatusService.ClusterType.ImageCodesOnly)
                .build())
            .build())

        // 8. Bluetooth service (for HFP/A2DP pairing)
        if (btMacAddress.isNotEmpty()) {
            services.add(Control.Service.newBuilder()
                .setId(AaChannel.BLUETOOTH)
                .setBluetoothService(Control.Service.BluetoothService.newBuilder()
                    .setCarAddress(btMacAddress)
                    .addAllSupportedPairingMethods(listOf(
                        Control.BluetoothPairingMethod.A2DP,
                        Control.BluetoothPairingMethod.HFP))
                    .build())
                .build())
        }

        // Build session config bitmask for hiding UI elements
        var sessionConfig = 0
        if (hideClock) sessionConfig = sessionConfig or 1
        if (hideSignal) sessionConfig = sessionConfig or 2
        if (hideBattery) sessionConfig = sessionConfig or 4

        // Match bridge live_session.cpp SDR exactly
        val builder = Control.ServiceDiscoveryResponse.newBuilder()
            .addAllServices(services)
            // Bridge only sets model/year/vehicleId, not make (field 2)
            .setMake("")
            .setModel(vehicle.model)
            .setYear(vehicle.year)
            .setVehicleId(vehicle.vehicleId)
            .setDriverPosition(vehicle.driverPosition)
            .setHeadUnitMake("OpenAutoLink")
            .setHeadUnitModel("Direct")
            .setHeadUnitSoftwareBuild("1")
            .setHeadUnitSoftwareVersion("1.0")
            .setCanPlayNativeMediaDuringVr(false)
            .setDisplayName("OpenAutoLink")
            .setProbeForSupport(false)
            .setHeadunitInfo(Common.HeadUnitInfo.newBuilder()
                .setHeadUnitMake("OpenAutoLink")
                .setHeadUnitModel("Direct")
                .setMake(vehicle.make)
                .setModel(vehicle.model)
                .setYear(vehicle.year)
                .setVehicleId("oal-001")
                .setHeadUnitSoftwareBuild("1")
                .setHeadUnitSoftwareVersion("1.0")
                .build())
            .setConnectionConfiguration(Control.ConnectionConfiguration.newBuilder()
                .setPingConfiguration(Control.PingConfiguration.newBuilder()
                    .setTimeoutMs(5000)
                    .setIntervalMs(1500)
                    .setHighLatencyThresholdMs(500)
                    .setTrackedPingCount(5)
                    .build())
                .build())
        if (sessionConfig != 0) {
            builder.setSessionConfiguration(sessionConfig)
        }
        return builder.build()
    }

    private fun buildSensorService(): Control.Service {
        val sensors = listOf(
            Sensors.SensorType.DRIVING_STATUS,
            Sensors.SensorType.LOCATION,
            Sensors.SensorType.NIGHT,
            Sensors.SensorType.COMPASS,
            Sensors.SensorType.CAR_SPEED,
            Sensors.SensorType.RPM,
            Sensors.SensorType.FUEL_LEVEL,
            Sensors.SensorType.PARKING_BRAKE,
            Sensors.SensorType.GEAR,
            Sensors.SensorType.ACCEL,
            Sensors.SensorType.GYRO,
            Sensors.SensorType.VEHICLE_ENERGY_MODEL, // Field 23 — EV battery for Maps
        )

        return Control.Service.newBuilder()
            .setId(AaChannel.SENSOR)
            .setSensorSourceService(Control.Service.SensorSourceService.newBuilder()
                .addAllSensors(sensors.map { type ->
                    Control.Service.SensorSourceService.Sensor.newBuilder()
                        .setType(type)
                        .build()
                })
                .build())
            .build()
    }

    private fun buildVideoService(config: VideoConfig): Control.Service {
        val frameRate = if (config.fps >= 60) {
            Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._60
        } else {
            Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30
        }

        val sink = Control.Service.MediaSinkService.newBuilder()
            .setAudioType(Media.AudioStreamType.NONE)
            .setAvailableWhileInCall(true)

        // Auto-compute pixel_aspect_ratio from display AR vs video AR.
        // Compensates for wide displays (non-16:9). When AA renders into a
        // letterboxed area, pixel_aspect pre-distorts the layout so circles
        // stay circular after the phone applies letterbox scaling.
        // Manual override: config.pixelAspectE4 > 0 takes priority.
        fun computePixelAspect(videoW: Int, videoH: Int): Int {
            if (config.pixelAspectE4 > 0) return config.pixelAspectE4
            if (config.displayWidth <= 0 || config.displayHeight <= 0) return 0
            val displayAr = config.displayWidth.toDouble() / config.displayHeight
            val videoAr = videoW.toDouble() / videoH
            if (videoAr <= 0) return 0
            val pa = (displayAr / videoAr * 10000).toInt()
            return if (pa != 10000) pa else 0  // 10000 = 1:1, no compensation needed
        }

        // Map resolution tier to pixel dimensions for pixel_aspect computation
        fun tierDimensions(tier: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType): Pair<Int, Int> {
            return when (tier) {
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480 -> 800 to 480
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720 -> 1280 to 720
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080 -> 1920 to 1080
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440 -> 2560 to 1440
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160 -> 3840 to 2160
                else -> 1920 to 1080
            }
        }

        // Build a VideoConfiguration for a given resolution tier and codec
        fun addVideoConfig(tier: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType,
                           codec: Media.MediaCodecType) {
            val (vw, vh) = tierDimensions(tier)
            val pa = computePixelAspect(vw, vh)
            val builder = Control.Service.MediaSinkService.VideoConfiguration.newBuilder()
                .setCodecResolution(tier)
                .setFrameRate(frameRate)
                .setDensity(config.dpi)
                .setRealDensity(config.dpi)
                .setMarginWidth(config.marginWidth)
                .setMarginHeight(config.marginHeight)
                .setVideoCodecType(codec)
            if (pa > 0) builder.setPixelAspectRatioE4(pa)
            sink.addVideoConfigs(builder.build())
            OalLog.d("DirectSD", "  VideoConfig: tier=$tier codec=$codec dpi=${config.dpi} pa=$pa")
        }

        val tiers = listOf(
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160,
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440,
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080,
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720,
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480,
        )
        val lowTiers = listOf(
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080,
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720,
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480,
        )
        val highTiers = listOf(
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160,
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440,
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080,
        )

        // Map selected resolution to a tier
        val selectedTier = when {
            config.width >= 3840 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160
            config.width >= 2560 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440
            config.width >= 1920 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080
            config.width >= 1280 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720
            else -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
        }

        when (config.codec.normalizedPreference()) {
            "auto" -> {
                // Auto-negotiate: offer H.265 at all tiers, VP9 at high tiers, then H.264 fallback.
                for (tier in tiers) {
                    addVideoConfig(tier, Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265)
                }
                for (tier in highTiers) {
                    addVideoConfig(tier, Media.MediaCodecType.MEDIA_CODEC_VIDEO_VP9)
                }
                for (tier in lowTiers) {
                    addVideoConfig(tier, Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                }
            }
            "h265" -> {
                // Match bridge: offer at configured + alternative tiers, plus H.264 fallback
                addVideoConfig(selectedTier, Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265)
                for (tier in listOf(
                    Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160,
                    Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440,
                    Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080,
                )) {
                    if (tier != selectedTier) addVideoConfig(tier, Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265)
                }
                for (tier in lowTiers) {
                    addVideoConfig(tier, Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                }
            }
            "vp9" -> {
                addVideoConfig(selectedTier, Media.MediaCodecType.MEDIA_CODEC_VIDEO_VP9)
                for (tier in listOf(
                    Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160,
                    Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440,
                    Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080,
                )) {
                    if (tier != selectedTier) addVideoConfig(tier, Media.MediaCodecType.MEDIA_CODEC_VIDEO_VP9)
                }
                for (tier in lowTiers) {
                    addVideoConfig(tier, Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                }
            }
            else -> { // "H.264"
                val h264Tier = if (selectedTier.number > Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080.number) {
                    Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080
                } else {
                    selectedTier
                }
                for (tier in lowTiers) {
                    if (tier.number <= h264Tier.number) {
                        addVideoConfig(tier, Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                    }
                }
            }
        }

        // Set the primary available type to the preferred codec
        val primaryCodec = when (config.codec.normalizedPreference()) {
            "h265" -> Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265
            "vp9" -> Media.MediaCodecType.MEDIA_CODEC_VIDEO_VP9
            "auto" -> Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265
            else -> Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
        }
        sink.setAvailableType(primaryCodec)

        return Control.Service.newBuilder()
            .setId(AaChannel.VIDEO)
            .setMediaSinkService(sink.build())
            .build()
    }

    fun videoConfigCount(config: VideoConfig): Int {
        val selectedTier = when {
            config.width >= 3840 -> 5
            config.width >= 2560 -> 4
            config.width >= 1920 -> 3
            config.width >= 1280 -> 2
            else -> 1
        }
        return when (config.codec.normalizedPreference()) {
            "auto" -> 11
            "h265", "vp9" -> 1 + listOf(5, 4, 3).count { it != selectedTier } + 3
            else -> minOf(selectedTier, 3)
        }
    }

    private fun buildInputService(width: Int, height: Int): Control.Service {
        return Control.Service.newBuilder()
            .setId(AaChannel.INPUT)
            .setInputSourceService(Control.Service.InputSourceService.newBuilder()
                .setTouchscreen(Control.Service.InputSourceService.TouchConfig.newBuilder()
                    .setWidth(width)
                    .setHeight(height)
                    .build())
                .build())
            .build()
    }

    private fun buildAudioService(
        channel: Int,
        streamType: Media.AudioStreamType,
        sampleRate: Int,
        bits: Int,
        channels: Int,
        codec: Media.MediaCodecType = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM,
    ): Control.Service {
        return Control.Service.newBuilder()
            .setId(channel)
            .setMediaSinkService(Control.Service.MediaSinkService.newBuilder()
                .setAvailableType(codec)
                .setAudioType(streamType)
                .addAudioConfigs(Media.AudioConfiguration.newBuilder()
                    .setSampleRate(sampleRate)
                    .setNumberOfBits(bits)
                    .setNumberOfChannels(channels)
                    .build())
                .build())
            .build()
    }

    private fun buildMicService(): Control.Service {
        return Control.Service.newBuilder()
            .setId(AaChannel.MIC)
            .setMediaSourceService(Control.Service.MediaSourceService.newBuilder()
                .setType(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
                .setAudioConfig(Media.AudioConfiguration.newBuilder()
                    .setSampleRate(16000)
                    .setNumberOfBits(16)
                    .setNumberOfChannels(1)
                    .build())
                .build())
            .build()
    }

}
