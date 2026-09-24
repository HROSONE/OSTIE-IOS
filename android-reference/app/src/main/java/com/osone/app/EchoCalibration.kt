package com.osone.app

import org.json.JSONObject

/**
 * Sensibilidade da proteção de eco aprendida neste aparelho (só com o alto-falante; fones não precisam).
 * Uma "interrupção" que não vira fala transcrita era eco: a barreira sobe. Uma interrupção confirmada
 * pela transcrição mostra que a barreira permite falar: ela desce devagar. O eco medido vira o ponto de partida.
 */
class EchoCalibration(ratio: Float = DEFAULT_RATIO, echoFloor: Float = MIN_ECHO,
    falseBarges: Int = 0, realBarges: Int = 0) {
    var ratio = ratio.coerceIn(MIN_RATIO, MAX_RATIO); private set
    var echoFloor = echoFloor.coerceIn(MIN_ECHO, MAX_ECHO); private set
    var falseBarges = falseBarges; private set
    var realBarges = realBarges; private set

    /** Nível do microfone acima do qual a fala do usuário vence o eco medido. */
    fun threshold(echo: Float) = maxOf(MIN_LEVEL, echo * ratio).coerceAtMost(MAX_THRESHOLD)

    fun falseBarge() { falseBarges++; ratio = (ratio + FALSE_STEP).coerceAtMost(MAX_RATIO) }

    fun realBarge() { realBarges++; ratio = (ratio - REAL_STEP).coerceAtLeast(MIN_RATIO) }

    /** Média móvel do eco observado enquanto o OSTIE falava. */
    fun learnEcho(level: Float) { echoFloor = (echoFloor * 0.7f + level * 0.3f).coerceIn(MIN_ECHO, MAX_ECHO) }

    fun reset() { ratio = DEFAULT_RATIO; echoFloor = MIN_ECHO; falseBarges = 0; realBarges = 0 }

    /** A cada sessão, volta um pouco ao padrão para um ajuste antigo não ficar preso. */
    fun relax() { ratio += (DEFAULT_RATIO - ratio) * 0.1f }

    val adjusted get() = falseBarges + realBarges > 0

    val label: String get() = when {
        !adjusted -> "Padrão; ajusta sozinha conforme você conversa pelo alto-falante."
        ratio > DEFAULT_RATIO + 0.2f -> "Mais firme contra eco neste aparelho (ajustada após $falseBarges interrupções falsas)."
        ratio < DEFAULT_RATIO - 0.2f -> "Mais fácil de interromper neste aparelho."
        else -> "Ajustada a este aparelho."
    }

    fun toJson(): String = JSONObject().put("ratio", ratio.toDouble()).put("echo", echoFloor.toDouble())
        .put("false", falseBarges).put("real", realBarges).toString()

    companion object {
        const val DEFAULT_RATIO = 2.4f
        const val MIN_RATIO = 1.6f
        const val MAX_RATIO = 4.5f
        const val MIN_LEVEL = 0.08f
        const val MAX_THRESHOLD = 0.7f
        const val MIN_ECHO = 0.02f
        const val MAX_ECHO = 0.4f
        const val FALSE_STEP = 0.3f
        const val REAL_STEP = 0.05f

        fun fromJson(value: String?): EchoCalibration = try {
            val json = JSONObject(value ?: "{}")
            EchoCalibration(json.optDouble("ratio", DEFAULT_RATIO.toDouble()).toFloat(),
                json.optDouble("echo", MIN_ECHO.toDouble()).toFloat(), json.optInt("false"), json.optInt("real"))
        } catch (_: Exception) { EchoCalibration() }
    }
}
