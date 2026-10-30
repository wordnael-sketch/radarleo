package br.radarleo.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import br.radarleo.R
import br.radarleo.RadarLeoApp
import br.radarleo.alert.Falador
import br.radarleo.alert.PreferenciasAlertas
import br.radarleo.data.TipoRadar
import br.radarleo.location.Etapa
import br.radarleo.location.LocalizacaoService
import br.radarleo.location.MemoriaPermissoes
import br.radarleo.location.OrdemPermissoes
import br.radarleo.location.Permissoes
import br.radarleo.location.Posicao
import br.radarleo.location.Rastreamento
import br.radarleo.overlay.EstadoBolha
import br.radarleo.sync.AtualizarBaseWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class Aba(val rotulo: Int) { PAINEL(R.string.aba_painel), MAPA(R.string.aba_mapa), AJUSTES(R.string.aba_ajustes) }

/** Primeiro as permissões, uma por vez e explicadas; depois o app com três abas. */
class MainActivity : ComponentActivity() {

    private val memoria by lazy { MemoriaPermissoes(this) }
    private val preferencias by lazy { PreferenciasAlertas(this) }
    private val ajustes by lazy { MutableStateFlow(preferencias.ler()) }
    private val repo by lazy { (application as RadarLeoApp).repositorio }
    private var falador: Falador? = null // só para o "Testar voz"

    // Muda sempre que o estado das permissões pode ter mudado (diálogo, volta das configurações).
    private val revisao = MutableStateFlow(0)
    private var etapaPedida: Etapa? = null

    private val pedirPermissoes =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { aposPedido() }

    private val abrirConfiguracoes =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { revisao.value++ }

    private val salvarCsv =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(::gravarCsv) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val estadoAtualizar = AtualizarBaseWorker.estadoAtualizarAgora(this)
        setContent {
            val rev by revisao.collectAsStateWithLifecycle()
            val concedidas = remember(rev) { Permissoes.concedidas(this) }
            val puladas = remember(rev) { memoria.puladas }
            val negadas = remember(rev) { memoria.negadasDeVez }
            val etapa = OrdemPermissoes.proxima(Build.VERSION.SDK_INT, concedidas, puladas)

            TemaRadarLeo {
                Surface(Modifier.fillMaxSize()) {
                    if (etapa != null) {
                        val aplicaveis = Etapa.entries.filter { Build.VERSION.SDK_INT >= it.sdkMinimo }
                        Box(Modifier.padding(top = 48.dp)) {
                            PermissaoTela(
                                etapa = etapa,
                                passo = aplicaveis.indexOf(etapa) + 1,
                                totalPassos = aplicaveis.size,
                                negadaDeVez = etapa in negadas,
                                aoContinuar = { continuar(etapa) },
                                aoAbrirConfiguracoes = { abrirConfiguracoesDoApp() },
                                aoPular = { memoria.pular(etapa); revisao.value++ },
                            )
                        }
                    } else {
                        AppRadarLeo(
                            temPermissaoBolha = Etapa.SOBREPOSICAO in concedidas,
                            estadoAtualizar = estadoAtualizar,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AppRadarLeo(temPermissaoBolha: Boolean, estadoAtualizar: Flow<WorkInfo?>) {
        var aba by rememberSaveable { mutableStateOf(Aba.PAINEL) }
        var reportando by remember { mutableStateOf<Pair<Posicao, Double?>?>(null) }
        val snack = remember { SnackbarHostState() }
        val escopo = rememberCoroutineScope()

        val ativo by Rastreamento.ativo.collectAsStateWithLifecycle()
        val posicao by Rastreamento.posicao.collectAsStateWithLifecycle()
        val movimento by Rastreamento.movimento.collectAsStateWithLifecycle()
        val aFrente by Rastreamento.aFrente.collectAsStateWithLifecycle()
        val aj by ajustes.collectAsStateWithLifecycle()
        val info by repo.baseInfo.collectAsStateWithLifecycle(initialValue = null)
        val total by repo.total.collectAsStateWithLifecycle(initialValue = 0)
        val reportes by repo.totalDoUsuario.collectAsStateWithLifecycle(initialValue = 0)
        val trabalho by estadoAtualizar.collectAsStateWithLifecycle(initialValue = null)
        val textoSalvo = stringResource(R.string.reportar_salvo)

        Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                NavigationBar {
                    Aba.entries.forEach { a ->
                        NavigationBarItem(
                            selected = aba == a,
                            onClick = { aba = a },
                            icon = {
                                Icon(
                                    when (a) { Aba.PAINEL -> Icons.Filled.Home; Aba.MAPA -> Icons.Filled.Place; Aba.AJUSTES -> Icons.Filled.Settings },
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(a.rotulo)) },
                        )
                    }
                }
            },
        ) { margens ->
            Box(Modifier.padding(margens)) {
                when (aba) {
                    Aba.PAINEL -> PainelTela(
                        ativo = ativo,
                        velocidadeKmh = movimento?.velocidadeKmh,
                        proximo = EstadoBolha.de(aFrente, movimento, aj.alertas),
                        podeReportar = posicao != null,
                        aoIniciar = { LocalizacaoService.iniciar(this@MainActivity) },
                        aoParar = { LocalizacaoService.parar(this@MainActivity) },
                        // posição e rumo congelados no toque, não quando o diálogo fecha
                        aoReportar = { posicao?.let { reportando = it to movimento?.rumoGraus } },
                    )
                    Aba.MAPA -> MapaTela(repo, posicao)
                    Aba.AJUSTES -> ConfiguracoesTela(
                        ajustes = aj,
                        aoMudar = { novo -> preferencias.gravar(novo); ajustes.value = novo },
                        temPermissaoBolha = temPermissaoBolha,
                        aoPermitirBolha = { continuar(Etapa.SOBREPOSICAO) },
                        aoTestarVoz = ::testarVoz,
                        info = info, total = total, trabalho = trabalho,
                        aoAtualizarBase = { AtualizarBaseWorker.atualizarAgora(this@MainActivity) },
                        reportes = reportes,
                        aoExportar = { salvarCsv.launch("radarleo_reportes_${LocalDate.now()}.csv") },
                    )
                }
            }
        }

        reportando?.let { (p, rumo) ->
            ReportarDialogo(
                rumoGraus = rumo,
                aoConfirmar = { tipo: TipoRadar, limite: Int? ->
                    reportando = null
                    escopo.launch {
                        repo.reportar(p.latitude, p.longitude, tipo, rumo, limite)
                        snack.showSnackbar(textoSalvo)
                    }
                },
                aoCancelar = { reportando = null },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        Rastreamento.appVisivel(true)
    }

    override fun onStop() {
        Rastreamento.appVisivel(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        revisao.value++ // o usuário pode ter mudado algo nas configurações do Android
    }

    override fun onDestroy() {
        falador?.encerrar()
        super.onDestroy()
    }

    private fun testarVoz() {
        val f = falador ?: Falador(this).also { falador = it }
        f.volume = ajustes.value.volume
        f.testar(getString(R.string.cfg_frase_teste))
    }

    private fun gravarCsv(uri: Uri) {
        lifecycleScope.launch {
            val ok = runCatching {
                val csv = repo.csvDosReportes()
                contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                    ?: error("sem acesso ao arquivo")
            }.isSuccess
            Toast.makeText(
                this@MainActivity, if (ok) R.string.cfg_exportado else R.string.cfg_exportar_falhou, Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun continuar(etapa: Etapa) {
        if (etapa == Etapa.SOBREPOSICAO) {
            abrirConfiguracoes.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val permissoes = Permissoes.doSistema(etapa)
        if (permissoes.isEmpty()) {
            revisao.value++
            return
        }
        etapaPedida = etapa
        pedirPermissoes.launch(permissoes)
    }

    private fun aposPedido() {
        val etapa = etapaPedida ?: return
        etapaPedida = null
        if (Permissoes.concedida(this, etapa)) {
            memoria.esquecerNegada(etapa)
        } else {
            // Negada e o sistema não vai mais mostrar o diálogo: só pelas configurações
            val principal = Permissoes.doSistema(etapa).first()
            if (!shouldShowRequestPermissionRationale(principal)) memoria.marcarNegadaDeVez(etapa)
        }
        revisao.value++
    }

    private fun abrirConfiguracoesDoApp() {
        abrirConfiguracoes.launch(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }
}

@Composable
fun TemaRadarLeo(conteudo: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = conteudo,
    )
}
