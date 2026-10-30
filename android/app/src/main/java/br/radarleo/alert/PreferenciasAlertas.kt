package br.radarleo.alert

import android.content.Context
import android.content.SharedPreferences

/** Tudo o que a tela de configurações ajusta. */
data class Ajustes(
    val alertas: ConfigAlertas = ConfigAlertas(),
    val volume: Float = 1f,          // 0..1, multiplica o volume da voz
    val vozLigada: Boolean = true,   // desligada = só bipe no excesso
    val mostrarBolha: Boolean = true,
)

/** Ajustes guardados no aparelho. O serviço escuta as mudanças e aplica na hora. */
class PreferenciasAlertas(contexto: Context) {
    private val prefs = contexto.getSharedPreferences("alertas", Context.MODE_PRIVATE)
    private var ouvinte: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun ler(): Ajustes {
        val padrao = ConfigAlertas()
        val distancias = prefs.getString(DISTANCIAS, null)
            ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: padrao.distanciasM
        val tolerancia = prefs.getInt(TOLERANCIA_PCT, -1).takeIf { it >= 0 } // -1 = regra do Inmetro
        return Ajustes(
            alertas = padrao.copy(distanciasM = distancias, toleranciaPct = tolerancia),
            volume = prefs.getFloat(VOLUME, 1f),
            vozLigada = prefs.getBoolean(VOZ, true),
            mostrarBolha = prefs.getBoolean(BOLHA, true),
        )
    }

    fun gravar(a: Ajustes) {
        prefs.edit()
            .putString(DISTANCIAS, a.alertas.distanciasM.joinToString(","))
            .putInt(TOLERANCIA_PCT, a.alertas.toleranciaPct ?: -1)
            .putFloat(VOLUME, a.volume)
            .putBoolean(VOZ, a.vozLigada)
            .putBoolean(BOLHA, a.mostrarBolha)
            .apply()
    }

    /** Chama [acao] a cada mudança. O SharedPreferences guarda o ouvinte só por referência fraca. */
    fun aoMudar(acao: (Ajustes) -> Unit) {
        parar()
        ouvinte = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> acao(ler()) }
            .also { prefs.registerOnSharedPreferenceChangeListener(it) }
    }

    fun parar() {
        ouvinte?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        ouvinte = null
    }

    private companion object {
        const val BOLHA = "mostrar_bolha"
        const val DISTANCIAS = "distancias_m"
        const val TOLERANCIA_PCT = "tolerancia_pct"
        const val VOLUME = "volume"
        const val VOZ = "voz_ligada"
    }
}
