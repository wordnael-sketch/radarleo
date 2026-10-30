"""Modelo de dados comum a todas as fontes de radares do RadarLeo."""

from __future__ import annotations

import hashlib
import math
from dataclasses import asdict, dataclass, field
from datetime import date
from enum import Enum


class TipoRadar(str, Enum):
    FIXO = "FIXO"
    SEMAFORO = "SEMAFORO"
    LOMBADA = "LOMBADA"
    VELOCIDADE_MEDIA = "VELOCIDADE_MEDIA"
    DESCONHECIDO = "DESCONHECIDO"


class Fonte(str, Enum):
    OSM = "OSM"
    ANTT = "ANTT"
    DNIT = "DNIT"
    LAI_DETRAN_DF = "LAI_DETRAN_DF"
    LAI_DER_DF = "LAI_DER_DF"
    MANUAL = "MANUAL"


# Confiabilidade padrão de cada fonte (0–100). O DNIT pode ser rebaixado para 50
# quando o equipamento estiver sem confirmação de funcionamento.
CONFIABILIDADE_PADRAO: dict[Fonte, int] = {
    Fonte.OSM: 60,
    Fonte.ANTT: 85,
    Fonte.DNIT: 85,
    Fonte.LAI_DETRAN_DF: 95,
    Fonte.LAI_DER_DF: 95,
    Fonte.MANUAL: 90,
}

# Retângulo envolvente do território brasileiro, incluindo as ilhas oceânicas
# (Fernando de Noronha, Trindade). É uma checagem grosseira: barra coordenadas
# trocadas (lat/lon invertidos) ou com sinal errado, não pontos em países vizinhos.
LAT_MIN, LAT_MAX = -33.80, 5.30
LON_MIN, LON_MAX = -74.00, -28.80

LIMITE_MIN_KMH, LIMITE_MAX_KMH = 20, 130

# Casas decimais usadas no id: 5 casas ≈ 1,1 m no equador.
CASAS_ID = 5


def gerar_id(latitude: float, longitude: float, tipo: TipoRadar | str,
             direcao_graus: float | None) -> str:
    """Hash estável a partir de lat/lon/tipo arredondados.

    A direção também entra no hash (arredondada ao grau inteiro, ou "AMBOS" quando
    nula); sem ela, dois radares no mesmo ponto em sentidos opostos teriam o mesmo id.
    """
    tipo_txt = tipo.value if isinstance(tipo, TipoRadar) else str(tipo)
    dir_txt = "AMBOS" if direcao_graus is None else str(round(direcao_graus) % 360)
    chave = f"{latitude:.{CASAS_ID}f}|{longitude:.{CASAS_ID}f}|{tipo_txt}|{dir_txt}"
    return hashlib.sha1(chave.encode("utf-8")).hexdigest()[:16]


@dataclass
class Radar:
    latitude: float
    longitude: float
    tipo: TipoRadar
    fonte: Fonte
    limite_kmh: int | None = None
    direcao_graus: float | None = None  # None = fiscaliza os dois sentidos
    rodovia: str | None = None
    km: float | None = None
    confiabilidade: int | None = None  # None = usa o padrão da fonte
    atualizado_em: str = field(default_factory=lambda: date.today().isoformat())
    id: str = ""

    def __post_init__(self) -> None:
        self.tipo = TipoRadar(self.tipo)
        self.fonte = Fonte(self.fonte)
        if self.direcao_graus is not None and not _eh_nan(self.direcao_graus):
            # 360° e 0° são o mesmo rumo; guardamos sempre em [0, 360).
            if self.direcao_graus == 360:
                self.direcao_graus = 0.0
        if self.confiabilidade is None:
            self.confiabilidade = CONFIABILIDADE_PADRAO[self.fonte]
        if not self.id:
            self.recalcular_id()

    def recalcular_id(self) -> None:
        self.id = gerar_id(self.latitude, self.longitude, self.tipo, self.direcao_graus)

    def para_dict(self) -> dict:
        d = asdict(self)
        d["tipo"] = self.tipo.value
        d["fonte"] = self.fonte.value
        # id primeiro, para facilitar a leitura do JSON
        return {"id": d.pop("id"), **d}


def _eh_nan(valor) -> bool:
    return isinstance(valor, float) and math.isnan(valor)


def validar(radar: Radar) -> list[str]:
    """Retorna a lista de motivos de invalidez (vazia = radar válido)."""
    motivos: list[str] = []

    lat, lon = radar.latitude, radar.longitude
    if lat is None or lon is None or _eh_nan(lat) or _eh_nan(lon):
        motivos.append("coordenadas ausentes")
    elif not (LAT_MIN <= lat <= LAT_MAX and LON_MIN <= lon <= LON_MAX):
        motivos.append(f"coordenadas fora do Brasil ({lat}, {lon})")

    lim = radar.limite_kmh
    if lim is not None and not _eh_nan(lim):
        if not (LIMITE_MIN_KMH <= lim <= LIMITE_MAX_KMH):
            motivos.append(f"limite fora da faixa {LIMITE_MIN_KMH}–{LIMITE_MAX_KMH} km/h ({lim})")
    elif _eh_nan(lim):
        motivos.append("limite inválido (NaN)")

    d = radar.direcao_graus
    if d is not None:
        if _eh_nan(d) or not (0 <= d <= 360):
            motivos.append(f"direção fora de 0–360° ({d})")

    c = radar.confiabilidade
    if c is None or not (0 <= c <= 100):
        motivos.append(f"confiabilidade fora de 0–100 ({c})")

    return motivos


@dataclass
class ResultadoFonte:
    """O que cada coletor devolve. Uma fonte que falhou volta com radares=[], o aviso
    e falhou=True (aí a consolidação reaproveita a última coleta boa dela)."""
    fonte: Fonte
    radares: list[Radar] = field(default_factory=list)
    avisos: list[str] = field(default_factory=list)
    falhou: bool = False


@dataclass
class Invalido:
    radar: Radar
    motivos: list[str]


def separar_validos(radares: list[Radar]) -> tuple[list[Radar], list[Invalido]]:
    """Divide a lista em válidos (vão para a base) e inválidos (vão para o relatório)."""
    validos: list[Radar] = []
    invalidos: list[Invalido] = []
    for r in radares:
        motivos = validar(r)
        if motivos:
            invalidos.append(Invalido(r, motivos))
        else:
            validos.append(r)
    return validos, invalidos


def relatorio_invalidos(invalidos: list[Invalido]) -> str:
    """Trecho em Markdown com os registros descartados, para o relatorio.md."""
    if not invalidos:
        return "Nenhum registro inválido.\n"
    linhas = [
        "| Fonte | Latitude | Longitude | Tipo | Motivos |",
        "|---|---|---|---|---|",
    ]
    for inv in invalidos:
        r = inv.radar
        linhas.append(
            f"| {r.fonte.value} | {r.latitude} | {r.longitude} | {r.tipo.value} | "
            f"{'; '.join(inv.motivos)} |"
        )
    return "\n".join(linhas) + "\n"
