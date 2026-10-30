package br.radarleo

import android.app.Application
import android.util.Log
import br.radarleo.data.RadarDb
import br.radarleo.data.RadarRepositorio
import br.radarleo.sync.AtualizarBaseWorker
import br.radarleo.sync.SincronizadorBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RadarLeoApp : Application() {

    val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: RadarDb by lazy { RadarDb.criar(this) }

    val repositorio: RadarRepositorio by lazy {
        RadarRepositorio(db.radarDao()) { assets.open("radares.json") }
    }

    val sincronizador: SincronizadorBase by lazy {
        SincronizadorBase(
            url = BuildConfig.URL_BASE,
            baixar = { url -> SincronizadorBase.baixarHttp(url, "RadarLeo-Android/${BuildConfig.VERSION_NAME}") },
            versaoInstalada = repositorio::versaoInstalada,
            instalarSeMaisNova = repositorio::instalarSeMaisNova,
        )
    }

    override fun onCreate() {
        super.onCreate()
        escopo.launch {
            runCatching { repositorio.garantirBaseEmbutida() }
                .onSuccess { instalou -> if (instalou) Log.i(TAG, "Base embutida instalada") }
                .onFailure { Log.e(TAG, "Falha ao instalar a base embutida", it) }
        }
        AtualizarBaseWorker.agendarSemanal(this)
    }

    companion object {
        const val TAG = "RadarLeo"
    }
}
