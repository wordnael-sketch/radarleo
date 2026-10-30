package br.radarleo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import br.radarleo.R
import br.radarleo.alert.Ajustes
import br.radarleo.data.BaseInfo
import br.radarleo.sync.AtualizarBaseWorker
import kotlin.math.roundToInt

/** Configurações: alertas, som, bolha, base de radares e meus reportes. */
@Composable
fun ConfiguracoesTela(
    ajustes: Ajustes,
    aoMudar: (Ajustes) -> Unit,
    temPermissaoBolha: Boolean,
    aoPermitirBolha: () -> Unit,
    aoTestarVoz: () -> Unit,
    info: BaseInfo?,
    total: Int,
    trabalho: WorkInfo?,
    aoAtualizarBase: () -> Unit,
    reportes: Int,
    aoExportar: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SecaoAlertas(ajustes, aoMudar)
        HorizontalDivider()
        SecaoSom(ajustes, aoMudar, aoTestarVoz)
        HorizontalDivider()
        Secao(R.string.cfg_bolha) {
            LinhaChave(R.string.cfg_bolha_mostrar, ajustes.mostrarBolha) { aoMudar(ajustes.copy(mostrarBolha = it)) }
            if (ajustes.mostrarBolha && !temPermissaoBolha) {
                Text(stringResource(R.string.cfg_bolha_sem_permissao), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = aoPermitirBolha) { Text(stringResource(R.string.perm_abrir_config)) }
            }
        }
        HorizontalDivider()
        SecaoBase(info, total, trabalho, aoAtualizarBase)
        HorizontalDivider()
        Secao(R.string.cfg_reportes) {
            Text(stringResource(R.string.cfg_reportes_total, reportes))
            Text(stringResource(R.string.cfg_reportes_explica), style = MaterialTheme.typography.bodySmall)
            Button(onClick = aoExportar, enabled = reportes > 0) { Text(stringResource(R.string.cfg_exportar)) }
        }
    }
}

@Composable
private fun SecaoAlertas(ajustes: Ajustes, aoMudar: (Ajustes) -> Unit) {
    val c = ajustes.alertas
    val faixas = c.faixas
    // valores locais durante o arraste; grava só ao soltar
    var d1 by remember(c) { mutableFloatStateOf(faixas.getOrElse(0) { 500 }.toFloat()) }
    var d2 by remember(c) { mutableFloatStateOf(faixas.getOrElse(1) { 300 }.toFloat()) }
    var d3 by remember(c) { mutableFloatStateOf(faixas.getOrElse(2) { 100 }.toFloat()) }
    var tol by remember(c) { mutableFloatStateOf((c.toleranciaPct ?: 10).toFloat()) }
    fun gravarDistancias() = aoMudar(
        ajustes.copy(alertas = c.copy(distanciasM = listOf(d1, d2, d3).map { it.roundToInt() }))
    )

    Secao(R.string.cfg_alertas) {
        Text(stringResource(R.string.cfg_distancias_explica), style = MaterialTheme.typography.bodySmall)
        Deslizante(stringResource(R.string.cfg_aviso_n, 1, d1.roundToInt()), d1, 200f..1500f, 25, { d1 = it }, ::gravarDistancias)
        Deslizante(stringResource(R.string.cfg_aviso_n, 2, d2.roundToInt()), d2, 100f..800f, 13, { d2 = it }, ::gravarDistancias)
        Deslizante(stringResource(R.string.cfg_aviso_n, 3, d3.roundToInt()), d3, 50f..300f, 9, { d3 = it }, ::gravarDistancias)

        LinhaChave(R.string.cfg_inmetro, c.toleranciaPct == null) { usar ->
            aoMudar(ajustes.copy(alertas = c.copy(toleranciaPct = if (usar) null else tol.roundToInt())))
        }
        Text(stringResource(R.string.cfg_inmetro_explica), style = MaterialTheme.typography.bodySmall)
        if (c.toleranciaPct != null) {
            Deslizante(
                stringResource(R.string.cfg_tolerancia_pct, tol.roundToInt()), tol, 0f..20f, 19, { tol = it },
                { aoMudar(ajustes.copy(alertas = c.copy(toleranciaPct = tol.roundToInt()))) },
            )
        }
    }
}

@Composable
private fun SecaoSom(ajustes: Ajustes, aoMudar: (Ajustes) -> Unit, aoTestarVoz: () -> Unit) {
    var volume by remember(ajustes.volume) { mutableFloatStateOf(ajustes.volume) }
    Secao(R.string.cfg_som) {
        LinhaChave(R.string.cfg_voz, ajustes.vozLigada) { aoMudar(ajustes.copy(vozLigada = it)) }
        Text(stringResource(R.string.cfg_voz_explica), style = MaterialTheme.typography.bodySmall)
        Deslizante(
            stringResource(R.string.cfg_volume, (volume * 100).roundToInt()), volume, 0.1f..1f, 8,
            { volume = it }, { aoMudar(ajustes.copy(volume = volume)) },
        )
        OutlinedButton(onClick = aoTestarVoz, enabled = ajustes.vozLigada) { Text(stringResource(R.string.cfg_testar_voz)) }
    }
}

/** Seção "Base de radares": data, total e "Atualizar agora" (Passo 10). */
@Composable
fun SecaoBase(info: BaseInfo?, total: Int, trabalho: WorkInfo?, aoAtualizar: () -> Unit) {
    val emAndamento = trabalho?.state == WorkInfo.State.RUNNING || trabalho?.state == WorkInfo.State.ENQUEUED
    Secao(R.string.base_de_radares) {
        Text(rotuloBase(info, total) ?: stringResource(R.string.carregando_base))
        Button(onClick = aoAtualizar, enabled = !emAndamento) { Text(stringResource(R.string.atualizar_agora)) }
        statusDoTrabalho(trabalho)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun statusDoTrabalho(trabalho: WorkInfo?): String? = when (trabalho?.state) {
    null, WorkInfo.State.CANCELLED -> null
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> stringResource(R.string.aguardando_conexao)
    WorkInfo.State.RUNNING -> stringResource(R.string.atualizando)
    WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED ->
        trabalho.outputData.getString(AtualizarBaseWorker.CHAVE_MENSAGEM)
}

@Composable
private fun Secao(titulo: Int, conteudo: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(titulo), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        conteudo()
    }
}

@Composable
private fun LinhaChave(rotulo: Int, ligado: Boolean, aoMudar: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(rotulo), Modifier.weight(1f))
        Switch(checked = ligado, onCheckedChange = aoMudar)
    }
}

@Composable
private fun Deslizante(
    rotulo: String, valor: Float, faixa: ClosedFloatingPointRange<Float>, passos: Int,
    aoMudar: (Float) -> Unit, aoSoltar: () -> Unit,
) {
    Column {
        Text(rotulo)
        Slider(value = valor, onValueChange = aoMudar, valueRange = faixa, steps = passos, onValueChangeFinished = aoSoltar)
    }
}
