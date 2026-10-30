package br.radarleo.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import br.radarleo.RadarLeoApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

class AtualizarBaseWorker(contexto: Context, parametros: WorkerParameters) :
    CoroutineWorker(contexto, parametros) {

    override suspend fun doWork(): Result {
        val resultado = (applicationContext as RadarLeoApp).sincronizador.sincronizar()
        val texto = SincronizadorBase.mensagem(resultado)
        Log.i(RadarLeoApp.TAG, "Sincronização: $texto")
        val saida = workDataOf(CHAVE_MENSAGEM to texto)
        val manual = inputData.getBoolean(CHAVE_MANUAL, false)
        return when {
            resultado !is ResultadoSync.Falha -> Result.success(saida)
            // no agendamento semanal, erro de rede tenta de novo mais tarde (backoff);
            // no botão, a resposta tem que voltar logo para a tela
            resultado.temporaria && !manual && runAttemptCount < MAX_TENTATIVAS -> Result.retry()
            else -> Result.failure(saida)
        }
    }

    companion object {
        const val CHAVE_MENSAGEM = "mensagem"
        const val CHAVE_MANUAL = "manual"
        private const val MAX_TENTATIVAS = 3
        private const val SEMANAL = "atualizar-base-semanal"
        private const val AGORA = "atualizar-base-agora"

        /** Uma vez por semana, só em rede não tarifada (Wi-Fi). KEEP: não reinicia a contagem a cada abertura. */
        fun agendarSemanal(contexto: Context) {
            val pedido = PeriodicWorkRequestBuilder<AtualizarBaseWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.UNMETERED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(contexto)
                .enqueueUniquePeriodicWork(SEMANAL, ExistingPeriodicWorkPolicy.KEEP, pedido)
        }

        /** Botão "Atualizar agora": qualquer rede, porque foi o usuário que pediu. */
        fun atualizarAgora(contexto: Context) {
            val pedido = OneTimeWorkRequestBuilder<AtualizarBaseWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInputData(workDataOf(CHAVE_MANUAL to true))
                .build()
            WorkManager.getInstance(contexto)
                .enqueueUniqueWork(AGORA, ExistingWorkPolicy.REPLACE, pedido)
        }

        /** Estado do último "Atualizar agora", para a tela. */
        fun estadoAtualizarAgora(contexto: Context): Flow<WorkInfo?> =
            WorkManager.getInstance(contexto).getWorkInfosForUniqueWorkFlow(AGORA)
                .map { it.lastOrNull() }
    }
}
