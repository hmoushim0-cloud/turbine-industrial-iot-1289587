package sa.factory.turbineindustriali.engine

/**
 * Production Modbus RTU RS-485 Acoustic Probe Protocol Engine.
 * Implements CRC-16 Modbus binary frame validation, Function Code 0x03 (Read Holding Registers)
 * telemetry parsing, ultrasonic dry-friction micro-acoustic analysis, and workplace noise safety monitoring.
 */
class ModbusRtuAcousticProbeEngine {

    data class AcousticTelemetry(
        val slaveId: Int,
        val soundPressureLevelDba: Double,
        val ultrasonicFrequencyHz: Int,
        val sensorTemperatureCelsius: Double,
        val acousticPeakToRms: Double,
        val isMhrsdNoiseLimitExceeded: Boolean, // > 85 dBA threshold
        val isUltrasonicFrictionSpike: Boolean  // Early friction indicator (> 30 kHz & > 60 dBA)
    )

    /**
     * Calculates 16-bit CRC for Modbus RTU frames (Polynomial: 0xA001, Initial: 0xFFFF).
     * Returns CRC as 16-bit integer (Low byte in lower 8 bits, High byte in upper 8 bits).
     */
    fun computeModbusCrc(data: ByteArray, length: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                if ((crc and 0x0001) != 0) {
                    crc = (crc ushr 1) xor 0xA001
                } else {
                    crc = crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    /**
     * Validates whether a received Modbus RTU binary frame has valid CRC-16 checksum.
     */
    fun verifyModbusFrame(frame: ByteArray): Boolean {
        if (frame.size < 5) return false
        val payloadLen = frame.size - 2
        val expectedCrc = computeModbusCrc(frame, payloadLen)

        val receivedLow = frame[payloadLen].toInt() and 0xFF
        val receivedHigh = frame[payloadLen + 1].toInt() and 0xFF
        val receivedCrc = receivedLow or (receivedHigh shl 8)

        return expectedCrc == receivedCrc
    }

    /**
     * Parses Function Code 0x03 Response Frame for the Acoustic Probe.
     * Frame format:
     * Byte 0: Slave Address
     * Byte 1: Function Code (0x03)
     * Byte 2: Byte Count (e.g. 0x08 for 4 registers)
     * Bytes 3-4: Reg 0x0001 - SPL in 0.1 dBA
     * Bytes 5-6: Reg 0x0002 - Ultrasonic Frequency in Hz
     * Bytes 7-8: Reg 0x0003 - Temperature in 0.1 C
     * Bytes 9-10: Reg 0x0004 - Peak/RMS in 0.01 factor
     * Bytes 11-12: CRC-16 (Low, High)
     */
    fun parseAcousticProbeFrame(frame: ByteArray): AcousticTelemetry {
        require(verifyModbusFrame(frame)) { "Invalid Modbus RTU CRC-16 Checksum" }
        require(frame[1].toInt() == 0x03) { "Unsupported Function Code: ${frame[1]}" }

        val slaveId = frame[0].toInt() and 0xFF
        val byteCount = frame[2].toInt() and 0xFF
        require(byteCount >= 8) { "Insufficient byte count in response: $byteCount" }

        val rawSpl = ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
        val rawFreq = ((frame[5].toInt() and 0xFF) shl 8) or (frame[6].toInt() and 0xFF)
        val rawTemp = ((frame[7].toInt() and 0xFF) shl 8) or (frame[8].toInt() and 0xFF)
        val rawPeakRms = ((frame[9].toInt() and 0xFF) shl 8) or (frame[10].toInt() and 0xFF)

        val splDba = rawSpl / 10.0
        val freqHz = rawFreq
        val tempC = rawTemp / 10.0
        val peakRms = rawPeakRms / 100.0

        val noiseExceeded = splDba >= 85.0 // Saudi MHRSD workplace noise threshold
        val frictionSpike = (freqHz >= 30000) && (splDba >= 60.0)

        return AcousticTelemetry(
            slaveId = slaveId,
            soundPressureLevelDba = splDba,
            ultrasonicFrequencyHz = freqHz,
            sensorTemperatureCelsius = tempC,
            acousticPeakToRms = peakRms,
            isMhrsdNoiseLimitExceeded = noiseExceeded,
            isUltrasonicFrictionSpike = frictionSpike
        )
    }

    /**
     * Builds a standard query frame to read 4 holding registers starting at address 0x0001.
     */
    fun buildReadHoldingRegistersQuery(slaveId: Int, startRegister: Int = 1, registerCount: Int = 4): ByteArray {
        val query = ByteArray(8)
        query[0] = (slaveId and 0xFF).toByte()
        query[1] = 0x03.toByte() // Read Holding Registers
        query[2] = ((startRegister ushr 8) and 0xFF).toByte()
        query[3] = (startRegister and 0xFF).toByte()
        query[4] = ((registerCount ushr 8) and 0xFF).toByte()
        query[5] = (registerCount and 0xFF).toByte()

        val crc = computeModbusCrc(query, 6)
        query[6] = (crc and 0xFF).toByte()        // CRC Low
        query[7] = ((crc ushr 8) and 0xFF).toByte() // CRC High

        return query
    }
}
