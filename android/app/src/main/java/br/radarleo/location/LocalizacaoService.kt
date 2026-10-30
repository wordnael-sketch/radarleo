package br.radarleo.location

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import br.radarleo.R
import br.radarleo.RadarLeoApp
import br.radarleo.alert.EstimadorMovimento
import br.radarleo.alert.Falador
import br.radarleo.alert.MotorAlertas
import br.radarleo.alert.Ajustes
import br.radarleo.alert.PreferenciasAlertas
import br.radarleo.overlay.BolhaOverlay
import br.radarleo.overlay.EstadoBolha
import br.radarleo.ui.MainActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano com a notificação fixa "RadarLeo ativo".
 * Lê o GPS a cada 1 s em alta precisão, publica em [Rastreamento] e passa cada leitura
 * pelo motor de alertas, que fala pelo [Falador].
 */
class LocalizacaoService : Service() {

    private lateinit var cliente: FusedLocationProviderClient
    private var lendoGps = false

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var falador: Falador
    private val estimador = EstimadorMovimento()
    private val motor = MotorAlertas()
    private lateinit var bolha: BolhaOverlay
    private lateinit var preferencias: PreferenciasAlertas
    @Volatile private var mostrarBolha = true

    private val retorno = object : LocationCallback() {
        override fun onLocationResult(resultado: LocationResult) {
            resultado.lastLocation?.let { Rastreamento.publicar(Posicao.de(it)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cliente = LocationServices.getFusedLocationProviderClient(this)
        falador = Falador(this)
        bolha = BolhaOverlay(this)
        preferencias = PreferenciasAlertas(this)
        aplicar(preferencias.ler())
        // mudou algo nas configurações com o RadarLeo ligado: vale na hora
        preferencias.aoMudar(::aplicar)
    }

    private fun aplicar(a: Ajustes) {
        motor.config = a.alertas
        falador.volume = a.volume
        falador.vozLigada = a.vozLigada
        mostrarBolha = a.mostrarBolha
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACAO_PARAR) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Permissoes.concedida(this, Etapa.LOCALIZACAO_PRECISA)) {
            Log.w(RadarLeoApp.TAG, "Serviço não iniciado: falta a permissão de localização precisa")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            criarCanal()
            val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else 0
            ServiceCompat.startForeground(this, ID_NOTIFICACAO, notificacao(), tipo)
        } catch (e: Exception) {
            // Android 12+ bloqueia iniciar em segundo plano sem "Permitir o tempo todo"
            Log.e(RadarLeoApp.TAG, "Não foi possível entrar em primeiro plano", e)
            stopSelf()
            return START_NOT_STICKY
        }
        iniciarGps()
        // STICKY: se o sistema matar o serviço por falta de memória, ele volta sozinho
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // conferida em onStartCommand
    private fun iniciarGps() {
        if (lendoGps) return
        val pedido = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVALO_MS)
            .setMinUpdateIntervalMillis(INTERVALO_MS)
            .build()
        cliente.requestLocationUpdates(pedido, retorno, Looper.getMainLooper())
        lendoGps = true
        Rastreamento.iniciado()
        // collect processa uma leitura por vez; se uma demorar, as intermediárias são puladas
        escopo.launch { Rastreamento.posicao.filterNotNull().collect { analisar(it) } }
        // WindowManager só aceita mexer na janela pela thread principal
        escopo.launch(Dispatchers.Main) {
            combine(Rastreamento.aFrente, Rastreamento.movimento, Rastreamento.appVisivel) { a, m, appNaTela ->
                if (mostrarBolha && !appNaTela) EstadoBolha.de(a, m, motor.config) else null
            }.collect { bolha.mostrar(it) }
        }
        Log.i(RadarLeoApp.TAG, "GPS ligado (a cada $INTERVALO_MS ms)")
    }

    private suspend fun analisar(p: Posicao) {
        try {
            val movimento = estimador.estimar(p)
            val repo = (application as RadarLeoApp).repositorio
            val perto = repo.radaresPerto(p.latitude, p.longitude, motor.raioBuscaM)
            val resultado = motor.processar(p.latitude, p.longitude, movimento, p.instante, perto)
            resultado.eventos.forEach(falador::tocar)
            Rastreamento.publicarAnalise(movimento, resultado.aFrente)
        } catch (e: Exception) {
            Log.e(RadarLeoApp.TAG, "Falha ao analisar a posição", e) // uma leitura ruim não derruba o serviço
        }
    }

    override fun onDestroy() {
        escopo.cancel()
        preferencias.parar()
        bolha.remover()
        falador.encerrar()
        cliente.removeLocationUpdates(retorno)
        lendoGps = false
        Rastreamento.parado()
        Log.i(RadarLeoApp.TAG, "GPS desligado")
        super.onDestroy()
    }

    private fun criarCanal() {
        val canal = NotificationChannel(
            CANAL, getString(R.string.canal_monitoramento), NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.canal_monitoramento_descricao) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun notificacao() = NotificationCompat.Builder(this, CANAL)
        .setSmallIcon(R.drawable.ic_radar)
        .setContentTitle(getString(R.string.notificacao_titulo))
        .setContentText(getString(R.string.notificacao_texto))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .addAction(
            0, getString(R.string.parar),
            PendingIntent.getService(
                this, 1, Intent(this, LocalizacaoService::class.java).setAction(ACAO_PARAR),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    companion object {
        private const val CANAL = "radarleo_ativo"
        private const val ID_NOTIFICACAO = 1
        private const val ACAO_PARAR = "br.radarleo.PARAR"
        const val INTERVALO_MS = 1_000L

        fun iniciar(contexto: Context) =
            ContextCompat.startForegroundService(contexto, Intent(contexto, LocalizacaoService::class.java))

        fun parar(contexto: Context) =
            contexto.stopService(Intent(contexto, LocalizacaoService::class.java))
    }
}
