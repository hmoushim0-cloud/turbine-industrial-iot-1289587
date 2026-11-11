package sa.factory.turbineindustriali

import org.junit.Assert.*
import org.junit.Test
import sa.factory.turbineindustriali.engine.Iso10816VibrationSeverityEngine
import sa.factory.turbineindustriali.engine.ModbusRtuAcousticProbeEngine
import java.io.File

class TurbineIndustrialRegressionTest {

    private val vibrationEngine = Iso10816VibrationSeverityEngine()
    private val modbusEngine = ModbusRtuAcousticProbeEngine()

    @Test
    fun testVectorMagnitudeCalculation() {
        val vector = Iso10816VibrationSeverityEngine.Vector3D(0.8, 0.2, 0.5)
        val magnitude = vibrationEngine.computeResultantAccelerationG(vector)
        // sqrt(0.8^2 + 0.2^2 + 0.5^2) = sqrt(0.64 + 0.04 + 0.25) = sqrt(0.93) ≈ 0.964
        assertEquals(0.964, magnitude, 0.005)
    }

    @Test
    fun testIso10816ZoneAClassification() {
        val zone = vibrationEngine.classifyIso10816Zone(1.1)
        assertEquals(Iso10816VibrationSeverityEngine.MachineVibrationZone.ZONE_A, zone)
    }

    @Test
    fun testIso10816ZoneBClassification() {
        val zone = vibrationEngine.classifyIso10816Zone(2.2)
        assertEquals(Iso10816VibrationSeverityEngine.MachineVibrationZone.ZONE_B, zone)
    }

    @Test
    fun testIso10816ZoneCClassification() {
        val zone = vibrationEngine.classifyIso10816Zone(3.8)
        assertEquals(Iso10816VibrationSeverityEngine.MachineVibrationZone.ZONE_C, zone)
    }

    @Test
    fun testIso10816ZoneDClassificationAndImmediateTrip() {
        val assessment = vibrationEngine.assessMachineCondition(
            rmsVelocityMmS = 5.2,
            peakAccelerationG = 3.5,
            rmsAccelerationG = 1.0
        )
        assertEquals(Iso10816VibrationSeverityEngine.MachineVibrationZone.ZONE_D, assessment.vibrationZone)
        assertTrue("Zone D must require immediate machine trip", assessment.requiresImmediateTrip)
    }

    @Test
    fun testBearingCrestFactorAssessment() {
        // Normal bearing: peak 3.0g / rms 1.0g = 3.0 (< 3.2) -> OPTIMAL
        val optimal = vibrationEngine.evaluateBearingHealth(3.0, 1.0)
        assertEquals(Iso10816VibrationSeverityEngine.BearingHealthStatus.OPTIMAL, optimal)

        // Spalling bearing: peak 5.0g / rms 1.0g = 5.0 (4.5 to 6.0) -> RACEWAY_SPALLING
        val spalling = vibrationEngine.evaluateBearingHealth(5.0, 1.0)
        assertEquals(Iso10816VibrationSeverityEngine.BearingHealthStatus.RACEWAY_SPALLING, spalling)

        // Critical failure bearing: peak 8.0g / rms 1.0g = 8.0 (>= 6.0) -> CRITICAL_FAILURE
        val critical = vibrationEngine.evaluateBearingHealth(8.0, 1.0)
        assertEquals(Iso10816VibrationSeverityEngine.BearingHealthStatus.CRITICAL_FAILURE, critical)
    }

    @Test
    fun testTextileNeedleDeflectionDefectProjection() {
        val baseDefect = vibrationEngine.estimateTextileDefectRate(1.0)
        assertEquals(0.25, baseDefect, 0.01)

        val elevatedDefect = vibrationEngine.estimateTextileDefectRate(3.5)
        assertTrue("Elevated vibration must project higher defect rate", elevatedDefect > baseDefect)
        assertTrue(elevatedDefect in 3.0..5.0)
    }

    @Test
    fun testModbusCrcComputationAndValidation() {
        // Standard query: Slave 1, FC 3, Addr 0x0001, Count 0x0004
        val query = modbusEngine.buildReadHoldingRegistersQuery(1, 1, 4)
        assertEquals(8, query.size)
        assertTrue("Query must satisfy Modbus CRC-16 check", modbusEngine.verifyModbusFrame(query))
    }

    @Test
    fun testModbusAcousticProbeFrameParsing() {
        // Construct mock response:
        // Byte 0: 0x01 (Slave 1)
        // Byte 1: 0x03 (FC 03)
        // Byte 2: 0x08 (8 data bytes)
        // Reg 1: 685 (68.5 dBA) -> 0x02, 0xAD
        // Reg 2: 38500 Hz (38.5 kHz ultrasonic) -> 0x96, 0x64
        // Reg 3: 420 (42.0 C) -> 0x01, 0xA4
        // Reg 4: 310 (3.10 peak/rms) -> 0x01, 0x36
        val payload = byteArrayOf(
            0x01.toByte(), 0x03.toByte(), 0x08.toByte(),
            0x02.toByte(), 0xAD.toByte(),
            0x96.toByte(), 0x64.toByte(),
            0x01.toByte(), 0xA4.toByte(),
            0x01.toByte(), 0x36.toByte()
        )
        val crc = modbusEngine.computeModbusCrc(payload, payload.size)
        val fullFrame = payload + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())

        val telemetry = modbusEngine.parseAcousticProbeFrame(fullFrame)
        assertEquals(1, telemetry.slaveId)
        assertEquals(68.5, telemetry.soundPressureLevelDba, 0.05)
        assertEquals(38500, telemetry.ultrasonicFrequencyHz)
        assertEquals(42.0, telemetry.sensorTemperatureCelsius, 0.05)
        assertEquals(3.10, telemetry.acousticPeakToRms, 0.05)
        assertTrue("High ultrasonic frequency with elevated dBA indicates friction spike", telemetry.isUltrasonicFrictionSpike)
        assertFalse("68.5 dBA is within 85 dBA workplace limit", telemetry.isMhrsdNoiseLimitExceeded)
    }

    @Test
    fun testSaudiMhrsdNoiseLimitThresholdAlert() {
        // Construct response with 88.0 dBA (880 -> 0x03, 0x70)
        val payload = byteArrayOf(
            0x01.toByte(), 0x03.toByte(), 0x08.toByte(),
            0x03.toByte(), 0x70.toByte(), // 880 = 88.0 dBA
            0x27.toByte(), 0x10.toByte(), // 10000 Hz
            0x01.toByte(), 0x5E.toByte(), // 35.0 C
            0x01.toByte(), 0x00.toByte()  // 1.00
        )
        val crc = modbusEngine.computeModbusCrc(payload, payload.size)
        val fullFrame = payload + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())

        val telemetry = modbusEngine.parseAcousticProbeFrame(fullFrame)
        assertTrue("88.0 dBA must trigger Saudi MHRSD workplace noise limit alarm", telemetry.isMhrsdNoiseLimitExceeded)
    }
}
