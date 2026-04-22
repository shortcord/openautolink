package com.openautolink.app.transport.direct

import com.openautolink.app.proto.Control
import com.openautolink.app.proto.Media
import com.openautolink.app.proto.Sensors

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
        val codec: String = "H.264",  // "H.264" or "H.265"
        val marginWidth: Int = 0,
        val marginHeight: Int = 0,
    )

    fun build(
        video: VideoConfig = VideoConfig(),
        hideClock: Boolean = true,
        hideSignal: Boolean = true,
        hideBattery: Boolean = true,
    ): Control.ServiceDiscoveryResponse {
        val services = mutableListOf<Control.Service>()

        // 1. Sensor service — announce all VHAL sensors we can provide
        services.add(buildSensorService())

        // 2. Video service
        services.add(buildVideoService(video))

        // 3. Input service (touchscreen)
        services.add(buildInputService(video.width, video.height))

        // 4. Audio services (system, speech, media)
        services.add(buildAudioService(AaChannel.AUDIO_SYSTEM, Media.AudioStreamType.SYSTEM, 16000, 16, 1))
        services.add(buildAudioService(AaChannel.AUDIO_SPEECH, Media.AudioStreamType.SPEECH, 16000, 16, 1))
        services.add(buildAudioService(AaChannel.AUDIO_MEDIA, Media.AudioStreamType.MEDIA, 48000, 16, 2))

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

        // Build session config bitmask for hiding UI elements
        var sessionConfig = 0
        if (hideClock) sessionConfig = sessionConfig or 1
        if (hideSignal) sessionConfig = sessionConfig or 2
        if (hideBattery) sessionConfig = sessionConfig or 4

        return Control.ServiceDiscoveryResponse.newBuilder()
            .addAllServices(services)
            .setMake("Chevrolet")
            .setModel("Blazer EV")
            .setYear("2024")
            .setVehicleId("OpenAutoLink")
            .setDriverPosition(Control.DriverPosition.DRIVER_POSITION_LEFT)
            .setHeadUnitMake("OpenAutoLink")
            .setHeadUnitModel("Direct")
            .setHeadUnitSoftwareBuild("1.0")
            .setHeadUnitSoftwareVersion("1.0")
            .setCanPlayNativeMediaDuringVr(true)
            .setHideProjectedClock(hideClock)
            .setSessionConfiguration(sessionConfig)
            .build()
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
        val codecType = when (config.codec) {
            "H.265" -> Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265
            else -> Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
        }

        val resolution = mapResolution(config.width, config.height)
        val frameRate = if (config.fps >= 60) {
            Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._60
        } else {
            Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30
        }

        return Control.Service.newBuilder()
            .setId(AaChannel.VIDEO)
            .setMediaSinkService(Control.Service.MediaSinkService.newBuilder()
                .setAvailableType(codecType)
                .setAudioType(Media.AudioStreamType.NONE)
                .setAvailableWhileInCall(true)
                .addVideoConfigs(Control.Service.MediaSinkService.VideoConfiguration.newBuilder()
                    .setCodecResolution(resolution)
                    .setFrameRate(frameRate)
                    .setDensity(config.dpi)
                    .setMarginWidth(config.marginWidth)
                    .setMarginHeight(config.marginHeight)
                    .setVideoCodecType(codecType)
                    .build())
                .build())
            .build()
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
    ): Control.Service {
        return Control.Service.newBuilder()
            .setId(channel)
            .setMediaSinkService(Control.Service.MediaSinkService.newBuilder()
                .setAvailableType(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
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

    private fun mapResolution(width: Int, height: Int): Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType {
        return when {
            width >= 3840 || height >= 3840 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160
            width >= 2560 || height >= 2560 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440
            width >= 1920 || height >= 1920 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080
            width >= 1280 || height >= 1280 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720
            else -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
        }
    }
}
