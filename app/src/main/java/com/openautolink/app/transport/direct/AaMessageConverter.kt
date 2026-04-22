package com.openautolink.app.transport.direct

import com.openautolink.app.proto.Input
import com.openautolink.app.proto.Sensors
import com.openautolink.app.transport.ControlMessage

/**
 * Converts OAL ControlMessages to AA wire protocol protobuf messages.
 *
 * In bridge mode, the bridge handles this conversion (OAL JSON â†’ aasdk protobuf).
 * In direct mode, the app must do it directly.
 */
object AaMessageConverter {

    /**
     * Convert a Touch ControlMessage to an AA InputReport protobuf.
     */
    fun touchToProto(touch: ControlMessage.Touch): AaMessage {
        val action = when (touch.action) {
            0 -> Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
            1 -> Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
            2 -> Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE
            3 -> Input.TouchEvent.PointerAction.TOUCH_ACTION_CANCEL
            5 -> Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN
            6 -> Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_UP
            else -> Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
        }

        val touchEvent = Input.TouchEvent.newBuilder()
            .setAction(action)

        if (touch.pointers != null) {
            for (p in touch.pointers) {
                touchEvent.addPointerData(Input.TouchEvent.Pointer.newBuilder()
                    .setX(p.x.toInt())
                    .setY(p.y.toInt())
                    .setPointerId(p.id)
                    .build())
            }
            touch.actionIndex?.let { touchEvent.setActionIndex(it) }
        } else if (touch.x != null && touch.y != null) {
            touchEvent.addPointerData(Input.TouchEvent.Pointer.newBuilder()
                .setX(touch.x.toInt())
                .setY(touch.y.toInt())
                .setPointerId(touch.pointerId ?: 0)
                .build())
        }

        val inputReport = Input.InputReport.newBuilder()
            .setTimestamp(System.nanoTime())
            .setTouchEvent(touchEvent.build())
            .build()

        return AaMessage.fromProto(AaChannel.INPUT, AaMsgType.MEDIA_START, inputReport)
    }

    /**
     * Convert a Button ControlMessage to an AA InputReport with KeyEvent.
     */
    fun buttonToProto(button: ControlMessage.Button): AaMessage {
        val key = Input.Key.newBuilder()
            .setKeycode(button.keycode)
            .setDown(button.down)
            .setMetastate(button.metastate)
            .setLongpress(button.longpress)
            .build()

        val keyEvent = Input.KeyEvent.newBuilder()
            .addKeys(key)
            .build()

        val inputReport = Input.InputReport.newBuilder()
            .setTimestamp(System.nanoTime())
            .setKeyEvent(keyEvent)
            .build()

        return AaMessage.fromProto(AaChannel.INPUT, AaMsgType.MEDIA_START, inputReport)
    }

    /**
     * Convert VehicleData ControlMessage to an AA SensorBatch.
     * Maps VHAL properties to the appropriate SensorBatch fields.
     * IMU data (accel/gyro/compass) is included in VehicleData.
     */
    fun vehicleDataToProto(vd: ControlMessage.VehicleData): AaMessage {
        val batch = Sensors.SensorBatch.newBuilder()

        // Driving status
        batch.addDrivingStatus(Sensors.SensorBatch.DrivingStatusData.newBuilder()
            .setStatus(if (vd.parkingBrake == true) 0 else 1)
            .build())

        // Speed (e6 = speed * 1,000,000)
        vd.speedKmh?.let { speed: Float ->
            batch.addSpeedData(Sensors.SensorBatch.SpeedData.newBuilder()
                .setSpeedE6((speed * 1_000_000 / 3.6).toInt()) // km/h → m/s × 1e6
                .setCruiseEngaged(false)
                .setCruiseSetSpeed(false)
                .build())
        }

        // Gear
        vd.gear?.let { gear ->
            val gearEnum = when (gear) {
                "P" -> Sensors.SensorBatch.GearData.GEAR.PARK
                "R" -> Sensors.SensorBatch.GearData.GEAR.REVERSE
                "N" -> Sensors.SensorBatch.GearData.GEAR.NEUTRAL
                "D" -> Sensors.SensorBatch.GearData.GEAR.DRIVE
                else -> Sensors.SensorBatch.GearData.GEAR.DRIVE
            }
            batch.addGearData(Sensors.SensorBatch.GearData.newBuilder()
                .setGear(gearEnum)
                .build())
        }

        // Fuel/battery level
        vd.fuelLevelPct?.let { fuel ->
            batch.addFuelData(Sensors.SensorBatch.FuelData.newBuilder()
                .setFuellevel(fuel)
                .setLowfuel(vd.lowFuel ?: false)
                .build())
        }

        // Night mode
        vd.nightMode?.let { night ->
            batch.addNightMode(Sensors.SensorBatch.NightData.newBuilder()
                .setIsNightMode(night)
                .build())
        }

        // Parking brake
        vd.parkingBrake?.let { brake: Boolean ->
            batch.addParkingbrakeData(Sensors.SensorBatch.ParkingBrakeData.newBuilder()
                .setIsEngaged(brake)
                .build())
        }

        // Accelerometer (values already in e3 format from VHAL)
        vd.accelXe3?.let { x ->
            batch.addAccelData(Sensors.SensorBatch.AccelerometerData.newBuilder()
                .setAccelerationXE3(x)
                .setAccelerationYE3(vd.accelYe3 ?: 0)
                .setAccelerationZE3(vd.accelZe3 ?: 0)
                .build())
        }

        // Gyroscope (values already in e3 format)
        vd.gyroRxe3?.let { rx ->
            batch.addGyroData(Sensors.SensorBatch.GyroscopeData.newBuilder()
                .setRotationSpeedXE3(rx)
                .setRotationSpeedYE3(vd.gyroRye3 ?: 0)
                .setRotationSpeedZE3(vd.gyroRze3 ?: 0)
                .build())
        }

        // Compass (value already in e6 format)
        vd.compassBearingE6?.let { bearing ->
            batch.addCompassData(Sensors.SensorBatch.CompassData.newBuilder()
                .setBearingE6(bearing)
                .build())
        }

        // RPM
        vd.rpmE3?.let { rpmE3 ->
            batch.addRpm(Sensors.SensorBatch.RpmData.newBuilder()
                .setRpm(rpmE3 / 1000)
                .build())
        }

        return AaMessage.fromProto(AaChannel.SENSOR, AaMsgType.MEDIA_DATA, batch.build())
    }

    /**
     * Build a VehicleEnergyModel SensorBatch (field 23) for EV battery data.
     * This is what makes Maps show battery-on-arrival percentage.
     *
     * Maps reads:
     *   min_usable_capacity.watt_hours → current battery level Wh
     *   max_capacity.watt_hours → total battery capacity Wh
     *   consumption.driving.rate → energy consumption Wh/km
     */
    fun buildVemSensorBatch(vd: ControlMessage.VehicleData): AaMessage? {
        val capacityWh = vd.evBatteryCapacityWh?.toInt() ?: return null
        val currentWh = vd.evBatteryLevelWh?.toInt() ?: return null
        val rangeM = vd.rangeKm?.let { (it * 1000).toInt() } ?: return null
        if (capacityWh <= 0 || currentWh <= 0 || rangeM <= 0) return null

        val vem = com.openautolink.app.proto.VehicleEnergyModelProto.VehicleEnergyModel.newBuilder()

        // Battery config
        val batt = com.openautolink.app.proto.VehicleEnergyModelProto.BatteryConfig.newBuilder()
            .setConfigId(1)
            .setMaxCapacity(com.openautolink.app.proto.VehicleEnergyModelProto.EnergyValue.newBuilder()
                .setWattHours(capacityWh).build())
            .setMinUsableCapacity(com.openautolink.app.proto.VehicleEnergyModelProto.EnergyValue.newBuilder()
                .setWattHours(currentWh).build())
            .setReserveEnergy(com.openautolink.app.proto.VehicleEnergyModelProto.EnergyValue.newBuilder()
                .setWattHours((capacityWh * 0.05).toInt()).build())
            .setRegenBrakingCapable(true)
            .setMaxChargePowerW(vd.evChargeRateW?.toInt()?.takeIf { it > 0 } ?: 150000)
            .setMaxDischargePowerW(150000)
        vem.setBattery(batt.build())

        // Energy consumption from range estimate
        val whPerKm = currentWh.toFloat() / rangeM.toFloat() * 1000f
        val cons = com.openautolink.app.proto.VehicleEnergyModelProto.EnergyConsumption.newBuilder()
            .setDriving(com.openautolink.app.proto.VehicleEnergyModelProto.EnergyRate.newBuilder()
                .setRate(whPerKm).build())
            .setAuxiliary(com.openautolink.app.proto.VehicleEnergyModelProto.EnergyRate.newBuilder()
                .setRate(2.0f).build())
            .setAerodynamic(com.openautolink.app.proto.VehicleEnergyModelProto.EnergyRate.newBuilder()
                .setRate(0.36f).build())
        vem.setConsumption(cons.build())

        // Charging prefs
        vem.setChargingPrefs(com.openautolink.app.proto.VehicleEnergyModelProto.ChargingPrefs.newBuilder()
            .setMode(1).build())

        val batch = Sensors.SensorBatch.newBuilder()
            .addVehicleEnergyModelData(vem.build())
            .build()

        return AaMessage.fromProto(AaChannel.SENSOR, AaMsgType.MEDIA_DATA, batch)
    }
}