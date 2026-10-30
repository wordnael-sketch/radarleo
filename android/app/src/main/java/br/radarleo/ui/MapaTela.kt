package br.radarleo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.radarleo.R
import br.radarleo.data.Caixa
import br.radarleo.data.Radar
import br.radarleo.data.RadarRepositorio
import br.radarleo.data.TipoRadar
import br.radarleo.location.Posicao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File

private val CENTRO_DF = GeoPoint(-15.7939, -47.8828)
private const val ZOOM_MINIMO_RADARES = 11.0
private const val MAXIMO_MARCADORES = 400

/** Mapa do OpenStreetMap (osmdroid, sem chave de API) com os radares coloridos por fonte. */
@Composable
fun MapaTela(repo: RadarRepositorio, posicao: Posicao?) {
    val contexto = LocalContext.current
    val mapa = remember { criarMapa(contexto, posicao) }
    val radares = remember { FolderOverlay().also { mapa.overlays.add(it) } }
    val voce = remember { marcadorVoce(mapa, contexto) }
    val caixa = remember { MutableStateFlow<Caixa?>(null) }
    var poucoZoom by remember { mutableStateOf(false) }

    // o MapView precisa acompanhar o ciclo de vida da tela (baixar tiles só quando visível)
    val dono = LocalLifecycleOwner.current
    DisposableEffect(dono) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapa.onResume()
                Lifecycle.Event.ON_PAUSE -> mapa.onPause()
                else -> Unit
            }
        }
        dono.lifecycle.addObserver(obs)
        val ouvinte = DelayedMapListener(object : MapListener {
            override fun onScroll(e: ScrollEvent?): Boolean { caixa.value = caixaVisivel(mapa); return false }
            override fun onZoom(e: ZoomEvent?): Boolean { caixa.value = caixaVisivel(mapa); return false }
        }, 250)
        mapa.addMapListener(ouvinte)
        mapa.addOnFirstLayoutListener { _, _, _, _, _ -> caixa.value = caixaVisivel(mapa) }
        onDispose {
            dono.lifecycle.removeObserver(obs)
            mapa.removeMapListener(ouvinte)
            mapa.onDetach()
        }
    }

    // só busca no banco o que está na tela; abaixo do zoom 11 seriam pontos demais
    LaunchedEffect(Unit) {
        caixa.filterNotNull().collectLatest { c ->
            poucoZoom = mapa.zoomLevelDouble < ZOOM_MINIMO_RADARES
            val lista = if (poucoZoom) emptyList() else repo.radaresNaCaixa(c, MAXIMO_MARCADORES)
            radares.items.clear()
            lista.forEach { radares.add(marcadorRadar(mapa, contexto, it)) }
            mapa.invalidate()
        }
    }

    LaunchedEffect(posicao) {
        posicao?.let {
            voce.position = GeoPoint(it.latitude, it.longitude)
            if (voce !in mapa.overlays) mapa.overlays.add(voce)
            mapa.invalidate()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapa }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomStart).padding(12.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (poucoZoom) Text(stringResource(R.string.mapa_aproxime), style = MaterialTheme.typography.labelMedium)
            CoresFonte.legenda.forEach { (nome, cor) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(cor, CircleShape))
                    Text(stringResource(nome), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        FloatingActionButton(
            onClick = { posicao?.let { mapa.controller.animateTo(GeoPoint(it.latitude, it.longitude)) } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.LocationOn, contentDescription = stringResource(R.string.mapa_centralizar)) }
        // atribuição exigida pela licença dos dados do OpenStreetMap
        Text(
            stringResource(R.string.mapa_atribuicao), style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)).padding(horizontal = 4.dp),
        )
    }
}

private fun criarMapa(contexto: Context, posicao: Posicao?): MapView {
    Configuration.getInstance().apply {
        userAgentValue = contexto.packageName // exigido pela política de uso dos tiles do OSM
        osmdroidBasePath = File(contexto.cacheDir, "osmdroid")
        osmdroidTileCache = File(osmdroidBasePath, "tiles")
    }
    return MapView(contexto).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        controller.setZoom(14.0)
        controller.setCenter(posicao?.let { GeoPoint(it.latitude, it.longitude) } ?: CENTRO_DF)
    }
}

private fun caixaVisivel(m: MapView): Caixa {
    val b = m.boundingBox
    return Caixa(b.latSouth, b.latNorth, b.lonWest, b.lonEast)
}

private fun marcadorRadar(mapa: MapView, contexto: Context, r: Radar) = Marker(mapa).apply {
    position = GeoPoint(r.latitude, r.longitude)
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    icon = iconeRadar(contexto, CoresFonte.de(r.fonte).toArgb(), r.direcaoGraus != null)
    // o desenho aponta para o norte; o osmdroid gira no sentido anti-horário
    r.direcaoGraus?.let { rotation = -it.toFloat() }
    val tipo = contexto.getString(nomeDoTipo(TipoRadar.de(r.tipo)))
    title = r.limiteKmh?.let { contexto.getString(R.string.mapa_titulo_limite, tipo, it) } ?: tipo
    snippet = contexto.getString(
        R.string.mapa_detalhe,
        r.fontes.replace(",", ", "),
        r.direcaoGraus?.let { "%.0f°".format(it) } ?: contexto.getString(R.string.mapa_dois_sentidos),
        r.confiabilidade,
    )
}

private fun marcadorVoce(mapa: MapView, contexto: Context) = Marker(mapa).apply {
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    icon = iconeRadar(contexto, android.graphics.Color.BLACK, comDirecao = false, raioDp = 7f)
    title = contexto.getString(R.string.mapa_voce)
}

private val cacheIcones = HashMap<String, BitmapDrawable>()

/** Bolinha colorida com borda branca; com direção, ganha uma seta apontando o sentido fiscalizado. */
private fun iconeRadar(contexto: Context, cor: Int, comDirecao: Boolean, raioDp: Float = 9f): BitmapDrawable =
    cacheIcones.getOrPut("$cor-$comDirecao-$raioDp") {
        val d = contexto.resources.displayMetrics.density
        val raio = raioDp * d
        val lado = ((raio + 7 * d) * 2).toInt()
        val c = lado / 2f
        val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val tela = Canvas(bmp)
        val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
        if (comDirecao) {
            tinta.color = cor
            tela.drawPath(Path().apply {
                moveTo(c, 0f); lineTo(c - 5 * d, c - raio + 2 * d); lineTo(c + 5 * d, c - raio + 2 * d); close()
            }, tinta)
        }
        tinta.color = android.graphics.Color.WHITE
        tela.drawCircle(c, c, raio, tinta)
        tinta.color = cor
        tela.drawCircle(c, c, raio - 2 * d, tinta)
        BitmapDrawable(contexto.resources, bmp)
    }
