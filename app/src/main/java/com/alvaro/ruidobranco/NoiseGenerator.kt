package com.alvaro.ruidobranco

/**
 * Produz amostras PCM continuamente. Não existe arquivo, duração ou ponto de loop.
 * O controle de tom altera a distribuição de energia entre graves e agudos,
 * sem mudar o volume configurado pelo usuário.
 */
class NoiseGenerator(seed: Int = System.nanoTime().toInt()) {

    private var state: Int = if (seed == 0) 0x6D2B79F5 else seed
    private var low = 0f
    private var veryLow = 0f
    private var smoothedTone = DEFAULT_TONE

    fun fill(buffer: ShortArray, targetTone: Float) {
        val safeTarget = targetTone.coerceIn(MIN_TONE, MAX_TONE)
        for (index in buffer.indices) {
            buffer[index] = nextSample(safeTarget)
        }
    }

    private fun nextSample(targetTone: Float): Short {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)

        val white = ((state ushr 8) / 8_388_607.5f) - 1f

        // Dois filtros passa-baixas em cascata formam a versão mais profunda.
        low += 0.10f * (white - low)
        veryLow += 0.025f * (low - veryLow)

        // A transição lenta evita estalos ao mover o slider durante a reprodução.
        smoothedTone += TONE_SMOOTHING * (targetTone - smoothedTone)

        val neutral = (white * 0.82f) + (low * 0.18f)
        val deep = ((low * 0.78f) + (veryLow * 0.22f)) * 2.55f
        val bright = ((white - low) * 0.90f) + (white * 0.15f)

        val shaped = if (smoothedTone < 0f) {
            mix(neutral, deep, -smoothedTone)
        } else {
            mix(neutral, bright, smoothedTone)
        }

        val sample = (shaped * Short.MAX_VALUE * OUTPUT_GAIN).toInt()
        return sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun mix(from: Float, to: Float, amount: Float): Float =
        from + ((to - from) * amount.coerceIn(0f, 1f))

    companion object {
        private const val DEFAULT_TONE = -0.35f
        private const val MIN_TONE = -1.00f
        private const val MAX_TONE = 1.00f
        private const val TONE_SMOOTHING = 0.0015f
        private const val OUTPUT_GAIN = 0.50f
    }
}
