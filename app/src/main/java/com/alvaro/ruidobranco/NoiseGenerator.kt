package com.alvaro.ruidobranco

/**
 * Produz amostras PCM continuamente. Não existe arquivo, duração ou ponto de loop.
 * Uma pequena parcela filtrada reduz a aspereza sem remover o caráter de ruído branco.
 */
class NoiseGenerator(seed: Int = System.nanoTime().toInt()) {

    private var state: Int = if (seed == 0) 0x6D2B79F5 else seed
    private var smoothed = 0f

    fun fill(buffer: ShortArray) {
        for (index in buffer.indices) {
            buffer[index] = nextSample()
        }
    }

    private fun nextSample(): Short {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)

        val white = ((state ushr 8) / 8_388_607.5f) - 1f
        smoothed += 0.08f * (white - smoothed)
        val softenedWhite = (white * 0.82f) + (smoothed * 0.18f)
        val sample = (softenedWhite * Short.MAX_VALUE * 0.55f).toInt()

        return sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
