package com.openautolink.app.cluster

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import com.openautolink.app.navigation.LaneDirectionInfo
import com.openautolink.app.navigation.LaneInfo
import com.openautolink.app.navigation.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterHelpersTest {

    @Test
    fun toDistance_prefersPreformattedValueAndUnit() {
        val distance = toDistance(
            meters = 1200,
            distanceUnits = "imperial",
            displayValue = "1.2",
            displayUnit = "kilometers_p1"
        )

        assertEquals(1.2, distance.displayDistance, 0.0)
        assertEquals(Distance.UNIT_KILOMETERS_P1, distance.displayUnit)
    }

    @Test
    fun toDistance_fallsBackToConfiguredImperialUnits() {
        val distance = toDistance(
            meters = 1609,
            distanceUnits = "imperial"
        )

        assertEquals(1.0, distance.displayDistance, 0.01)
        assertEquals(Distance.UNIT_MILES, distance.displayUnit)
    }

    @Test
    fun maneuverMappingProducesCarAppValues() {
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, mapManeuverTypeToCarApp(ManeuverType.TURN_LEFT))
        assertEquals(Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT, mapManeuverTypeToCarApp(ManeuverType.ON_RAMP_SLIGHT_RIGHT))
        assertEquals(Maneuver.TYPE_DESTINATION_RIGHT, mapManeuverTypeToCarApp(ManeuverType.DESTINATION_RIGHT))
    }

    @Test
    fun laneMappingProducesCarAppLaneDirections() {
        val lanes = buildLanes(
            listOf(
                LaneInfo(
                    directions = listOf(
                        LaneDirectionInfo(shape = "straight", highlighted = false),
                        LaneDirectionInfo(shape = "normal_right", highlighted = true)
                    )
                )
            )
        )

        assertEquals(1, lanes.size)
        val directions = lanes.single().directions
        assertEquals(2, directions.size)
        assertEquals(LaneDirection.SHAPE_STRAIGHT, directions[0].shape)
        assertEquals(LaneDirection.SHAPE_NORMAL_RIGHT, directions[1].shape)
        assertTrue(directions[1].isRecommended)
    }
}
