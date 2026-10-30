package br.radarleo.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Permissões na ordem em que são pedidas. Cada uma é explicada antes do pedido.
 * [sdkMinimo]: abaixo dessa versão do Android a permissão não existe (já vem concedida).
 * [obrigatoria]: sem ela o app não funciona, então não tem "Agora não".
 */
enum class Etapa(val sdkMinimo: Int, val obrigatoria: Boolean) {
    LOCALIZACAO_PRECISA(Build.VERSION_CODES.BASE, true),
    LOCALIZACAO_SEGUNDO_PLANO(Build.VERSION_CODES.Q, false),
    NOTIFICACOES(Build.VERSION_CODES.TIRAMISU, false),
    SOBREPOSICAO(Build.VERSION_CODES.BASE, false),
}

object OrdemPermissoes {
    /** Próxima etapa a mostrar, ou null quando não falta nada (concedida ou pulada). */
    fun proxima(sdk: Int, concedidas: Set<Etapa>, puladas: Set<Etapa>): Etapa? =
        Etapa.entries.firstOrNull { e ->
            sdk >= e.sdkMinimo && e !in concedidas && (e.obrigatoria || e !in puladas)
        }
}

object Permissoes {

    fun concedida(contexto: Context, etapa: Etapa): Boolean {
        fun tem(p: String) = ContextCompat.checkSelfPermission(contexto, p) == PackageManager.PERMISSION_GRANTED
        return when (etapa) {
            // "precisa" = FINE; se o usuário escolher "aproximada" só vem a COARSE
            Etapa.LOCALIZACAO_PRECISA -> tem(Manifest.permission.ACCESS_FINE_LOCATION)
            Etapa.LOCALIZACAO_SEGUNDO_PLANO ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) tem(Manifest.permission.ACCESS_FINE_LOCATION)
                else tem(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            Etapa.NOTIFICACOES ->
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || tem(Manifest.permission.POST_NOTIFICATIONS)
            Etapa.SOBREPOSICAO -> Settings.canDrawOverlays(contexto)
        }
    }

    fun concedidas(contexto: Context): Set<Etapa> = Etapa.entries.filter { concedida(contexto, it) }.toSet()

    /** Permissões de tempo de execução de cada etapa. A sobreposição vai pelas configurações. */
    fun doSistema(etapa: Etapa): Array<String> = when (etapa) {
        Etapa.LOCALIZACAO_PRECISA ->
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        Etapa.LOCALIZACAO_SEGUNDO_PLANO ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else emptyArray()
        Etapa.NOTIFICACOES ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            else emptyArray()
        Etapa.SOBREPOSICAO -> emptyArray()
    }
}

/** Lembra o que o usuário pulou e o que foi negado de vez ("não perguntar de novo"). */
class MemoriaPermissoes(contexto: Context) {
    private val prefs = contexto.getSharedPreferences("permissoes", Context.MODE_PRIVATE)

    val puladas: Set<Etapa> get() = ler("puladas")
    val negadasDeVez: Set<Etapa> get() = ler("negadas")

    fun pular(e: Etapa) = gravar("puladas", puladas + e)
    fun marcarNegadaDeVez(e: Etapa) = gravar("negadas", negadasDeVez + e)
    fun esquecerNegada(e: Etapa) = gravar("negadas", negadasDeVez - e)

    private fun ler(chave: String) = prefs.getStringSet(chave, emptySet())!!
        .mapNotNull { nome -> Etapa.entries.firstOrNull { it.name == nome } }.toSet()

    private fun gravar(chave: String, valor: Set<Etapa>) =
        prefs.edit().putStringSet(chave, valor.map { it.name }.toSet()).apply()
}
