package sa.factory.turbineindustriali.engine

import kotlin.math.sqrt

/**
 * Production ISO 10816-3 Industrial Vibration Severity & Machine Health Evaluation Engine.
 * Implements 3-axis accelerometer vector synthesis, RMS vibration velocity evaluation,
 * ISO 10816-3 Zone A-D classification for industrial manufacturing machinery,
 * and textile sewing line defect yield projection.
 */
class Iso10816VibrationSeverityEngine {

    enum class MachineVibrationZone {
        ZONE_A, // Newly commissioned, excellent condition (<= 1.4 mm/s)
        ZONE_B, // Unrestricted continuous long-term operation (1.4 < v <= 2.8 mm/s)
        ZONE_C, // Unsatisfactory for continuous operation; maintenance required (2.8 < v <= 4.5 mm/s)
        ZONE_D  // Damage imminent; immediate trip/shutdown recommended (> 4.5 mm/s)
    }

    enum class BearingHealthStatus {
        OPTIMAL,
        EARLY_FATIGUE,
        RACEWAY_SPALLING,
        CRITICAL_FAILURE
    }

    data class Vector3D(
        val x: Double,
        val y: Double,
        val z: Double
    ) {
        val magnitude: Double
            get() = sqrt(x * x + y * y + z * z)
    }

    data class VibrationAssessment(
        val rmsVelocityMmS: Double,
        val peakAccelerationG: Double,
        val crestFactor: Double,
        val vibrationZone: MachineVibrationZone,
        val bearingHealth: BearingHealthStatus,
        val projectedDefectRatePct: Double,
        val requiresImmediateTrip: Boolean
    )

    /**
     * Synthesizes 3-axis accelerometer readings into a resultant vector magnitude (g).
     */
    fun computeResultantAccelerationG(accel: Vector3D): Double {
        return Math.round(accel.magnitude * 1000.0) / 1000.0
    }

    /**
     * Classifies RMS vibration velocity (mm/s) according to ISO 10816-3 Group 2 (Rigid mount).
     */
    fun classifyIso10816Zone(rmsVelocityMmS: Double): MachineVibrationZone {
        return when {
            rmsVelocityMmS <= 1.4 -> MachineVibrationZone.ZONE_A
            rmsVelocityMmS <= 2.8 -> MachineVibrationZone.ZONE_B
            rmsVelocityMmS <= 4.5 -> MachineVibrationZone.ZONE_C
            else -> MachineVibrationZone.ZONE_D
        }
    }

    /**
     * Evaluates bearing health based on the Crest Factor (Peak / RMS ratio).
     * Normal roller bearings exhibit a Crest Factor between 2.5 and 3.5.
     * Values exceeding 4.5 indicate localized surface micro-cracking and impact transients.
     */
    fun evaluateBearingHealth(peakAccelG: Double, rmsAccelG: Double): BearingHealthStatus {
        if (rmsAccelG <= 0.0) return BearingHealthStatus.OPTIMAL
        val crestFactor = peakAccelG / rmsAccelG
        return when {
            crestFactor < 3.2 -> BearingHealthStatus.OPTIMAL
            crestFactor < 4.5 -> BearingHealthStatus.EARLY_FATIGUE
            crestFactor < 6.0 -> BearingHealthStatus.RACEWAY_SPALLING
            else -> BearingHealthStatus.CRITICAL_FAILURE
        }
    }

    /**
     * Estimates textile production seam defect rate based on vibration amplitude.
     * High machine head vibrations cause sewing needle deflection, leading to skipped stitches.
     */
    fun estimateTextileDefectRate(rmsVelocityMmS: Double): Double {
        val baseDefectPct = 0.25 // Normal baseline defect rate under optimal conditions
        val defectPct = when {
            rmsVelocityMmS <= 1.4 -> baseDefectPct
            rmsVelocityMmS <= 2.8 -> baseDefectPct + (rmsVelocityMmS - 1.4) * 0.8
            rmsVelocityMmS <= 4.5 -> 1.37 + (rmsVelocityMmS - 2.8) * 3.5
            else -> 7.32 + (rmsVelocityMmS - 4.5) * 6.0
        }
        return Math.round(defectPct * 100.0) / 100.0
    }

    /**
     * Performs end-to-end condition assessment for a production line turbine or motor.
     */
    fun assessMachineCondition(
        rmsVelocityMmS: Double,
        peakAccelerationG: Double,
        rmsAccelerationG: Double
    ): VibrationAssessment {
        val zone = classifyIso10816Zone(rmsVelocityMmS)
        val bearing = evaluateBearingHealth(peakAccelerationG, rmsAccelerationG)
        val crestFactor = if (rmsAccelerationG > 0.0) Math.round((peakAccelerationG / rmsAccelerationG) * 100.0) / 100.0 else 1.0
        val defectRate = estimateTextileDefectRate(rmsVelocityMmS)
        val tripRequired = (zone == MachineVibrationZone.ZONE_D) || (bearing == BearingHealthStatus.CRITICAL_FAILURE)

        return VibrationAssessment(
            rmsVelocityMmS = rmsVelocityMmS,
            peakAccelerationG = peakAccelerationG,
            crestFactor = crestFactor,
            vibrationZone = zone,
            bearingHealth = bearing,
            projectedDefectRatePct = defectRate,
            requiresImmediateTrip = tripRequired
        )
    }
}
