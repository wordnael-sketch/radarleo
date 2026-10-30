"""Fonte OSM: radares do OpenStreetMap via Overpass API.

Coleta:
  - nós highway=speed_camera;
  - relações type=enforcement com enforcement=maxspeed / traffic_signals
    (membro role=device = posição; role=from/to = sentido fiscalizado).

Rumo (direcao_graus), em ordem de prioridade:
  1. relação enforcement: rumo de from → to (ou from → device, ou device → to);
  2. tag direction em graus ou ponto cardeal (N, NE, SSO...);
  3. direction=forward/backward: rumo do segmento da via mais próxima;
  4. sem direction, mas o nó está sobre uma via de mão única (oneway): rumo da via.
     Pode ser desligado com inferir_mao_unica=False.
  Fora disso, None (= fiscaliza os dois sentidos).

Uso: python -m fontes.osm [--bbox sul,oeste,norte,leste]
"""

from __future__ import annotations

import argparse
import logging
import math
import re
import time
from datetime import date
from typing import Callable

import requests
from pyproj import Geod

from modelo import Fonte, Radar, ResultadoFonte, TipoRadar

log = logging.getLogger("radarleo.osm")

# (sul, oeste, norte, leste) — Distrito Federal
BBOX_DF = (-16.05, -48.29, -15.50, -47.31)

URL_OVERPASS = "https://overpass-api.de/api/interpreter"
USER_AGENT = "RadarLeo-ETL/0.1 (+https://github.com/; alertas de radar pessoais)"
TENTATIVAS = 4
ESPERA_BASE_S = 20
TIMEOUT_S = (15, 240)  # (conexão, leitura)

CONFIABILIDADE = 60

# Vias por onde passam carros; o resto (calçada, ciclovia, obra) é ignorado no cálculo de rumo.
VIAS_VEICULARES = {
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
    "residential", "living_street", "service", "road",
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
}

PONTOS_CARDEAIS = {
    nome: i * 22.5
    for i, nome in enumerate(
        "N NNE NE ENE E ESE SE SSE S SSW SW WSW W WNW NW NNW".split()
    )
}
# Variantes em português (O = oeste)
PONTOS_CARDEAIS.update({k.replace("W", "O"): v for k, v in PONTOS_CARDEAIS.items() if "W" in k})

# Nota que indica equipamento desativado (inclusive com o erro de digitação visto no DF)
RE_DESATIVADO = re.compile(r"des?t?ativad|removid|inativ", re.IGNORECASE)

_geod = Geod(ellps="WGS84")


# ───────────────────────────── consulta ─────────────────────────────

def montar_consulta(bbox: tuple[float, float, float, float]) -> str:
    b = ",".join(str(v) for v in bbox)
    return f"""
[out:json][timeout:180];
node["highway"="speed_camera"]({b})->.cams;
rel["type"="enforcement"]["enforcement"~"maxspeed|traffic_signals"]({b})->.rels;
node(r.rels)->.membros;
(.cams; .membros;)->.pontos;
(
  way(bn.pontos)["highway"];
  way(around.pontos:30)["highway"];
)->.vias;
.cams out body;
.rels out body;
.membros out body;
.vias out body geom;
"""


def baixar_overpass(consulta: str, url: str = URL_OVERPASS,
                    post: Callable = requests.post) -> dict:
    """POST na Overpass com retry. Repete em 429/5xx, timeout e resposta não-JSON."""
    ultimo_erro = None
    for tentativa in range(1, TENTATIVAS + 1):
        try:
            r = post(url, data={"data": consulta}, timeout=TIMEOUT_S,
                     headers={"User-Agent": USER_AGENT})
            if r.status_code == 200 and r.text.lstrip().startswith("{"):
                return r.json()
            ultimo_erro = f"HTTP {r.status_code}: {r.text[:120]!r}"
        except requests.RequestException as e:
            ultimo_erro = f"{type(e).__name__}: {e}"
        if tentativa < TENTATIVAS:
            espera = ESPERA_BASE_S * tentativa
            log.warning("Overpass falhou (tentativa %d/%d): %s — nova tentativa em %ds",
                        tentativa, TENTATIVAS, ultimo_erro, espera)
            time.sleep(espera)
    raise RuntimeError(f"Overpass indisponível após {TENTATIVAS} tentativas: {ultimo_erro}")


# ───────────────────────────── geometria ─────────────────────────────

def rumo(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Rumo geodésico de 1 → 2, em graus [0, 360)."""
    az, _, _ = _geod.inv(lon1, lat1, lon2, lat2)
    return round(az % 360, 1)


def _metros_locais(lat0: float, lat: float, lon: float, lat_ref: float, lon_ref: float):
    k = 111_320.0
    return (lon - lon_ref) * k * math.cos(math.radians(lat0)), (lat - lat_ref) * k


def _dist_ponto_segmento(p, a, b) -> float:
    (px, py), (ax, ay), (bx, by) = p, a, b
    dx, dy = bx - ax, by - ay
    if dx == dy == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def rumo_na_via(via: dict, indice: int) -> float:
    """Rumo da via (no sentido de desenho) em torno do nó de índice `indice`."""
    g = via["geometry"]
    i0, i1 = max(indice - 1, 0), min(indice + 1, len(g) - 1)
    return rumo(g[i0]["lat"], g[i0]["lon"], g[i1]["lat"], g[i1]["lon"])


def rumo_segmento_mais_proximo(lat: float, lon: float, vias: list[dict]) -> float | None:
    """Rumo (sentido de desenho) do segmento de via veicular mais próximo do ponto."""
    melhor, melhor_d = None, float("inf")
    for via in vias:
        g = via.get("geometry") or []
        pts = [_metros_locais(lat, q["lat"], q["lon"], lat, lon) for q in g]
        for i in range(len(pts) - 1):
            d = _dist_ponto_segmento((0.0, 0.0), pts[i], pts[i + 1])
            if d < melhor_d:
                melhor_d = d
                melhor = rumo(g[i]["lat"], g[i]["lon"], g[i + 1]["lat"], g[i + 1]["lon"])
    return melhor


def sentido_mao_unica(tags: dict) -> int:
    """+1 = mão única no sentido de desenho, -1 = contrário, 0 = mão dupla."""
    ow = tags.get("oneway")
    if ow in ("yes", "true", "1"):
        return 1
    if ow == "-1":
        return -1
    if ow == "no":
        return 0
    if tags.get("junction") in ("roundabout", "circular") or tags.get("highway") == "motorway":
        return 1
    return 0


# ───────────────────────────── tags ─────────────────────────────

def ler_maxspeed(valor: str | None) -> int | None:
    """'60' → 60; '40 mph' → 64; 'BR:urban', 'none', '60;40' → None."""
    if not valor:
        return None
    m = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*(mph|km/h|kmh)?\s*", valor)
    if not m:
        return None
    v = float(m.group(1))
    if m.group(2) == "mph":
        v *= 1.609344
    return int(round(v))


def ler_direction_tag(valor: str | None) -> float | str | None:
    """Graus (float), 'forward'/'backward' (str) ou None."""
    if not valor:
        return None
    v = valor.strip()
    if v in ("forward", "backward"):
        return v
    if v.upper() in PONTOS_CARDEAIS:
        return PONTOS_CARDEAIS[v.upper()]
    try:
        return float(v) % 360
    except ValueError:
        return None


def tipo_do_no(tags: dict) -> TipoRadar:
    nome = (tags.get("name") or "").lower()
    if tags.get("crossing") == "traffic_signals" or "semáforo" in nome or "semaforo" in nome:
        return TipoRadar.SEMAFORO
    return TipoRadar.FIXO


def tipo_da_relacao(enforcement: str) -> TipoRadar:
    return TipoRadar.SEMAFORO if "traffic_signals" in enforcement else TipoRadar.FIXO


# ───────────────────────────── conversão ─────────────────────────────

class _Indice:
    """Organiza os elementos da resposta da Overpass."""

    def __init__(self, elementos: list[dict]):
        self.nos: dict[int, dict] = {}
        self.relacoes: list[dict] = []
        self.vias: list[dict] = []
        vias_vistas = set()
        for e in elementos:
            if e["type"] == "node":
                self.nos[e["id"]] = e
            elif e["type"] == "relation":
                self.relacoes.append(e)
            elif e["type"] == "way" and e["id"] not in vias_vistas:
                vias_vistas.add(e["id"])
                if e.get("tags", {}).get("highway") in VIAS_VEICULARES and e.get("geometry"):
                    self.vias.append(e)
        self.vias_do_no: dict[int, list[tuple[dict, int]]] = {}
        for via in self.vias:
            for i, nid in enumerate(via.get("nodes", [])):
                self.vias_do_no.setdefault(nid, []).append((via, i))


def _rumo_por_tag(no: dict, idx: _Indice, inferir_mao_unica: bool) -> tuple[float | None, str]:
    """Rumo de um nó isolado, e a origem da informação (para o log)."""
    tags = no.get("tags", {})
    d = ler_direction_tag(tags.get("direction"))
    if isinstance(d, float):
        return d, "tag direction"

    sobre = idx.vias_do_no.get(no["id"], [])
    if d in ("forward", "backward"):
        if len(sobre) >= 1:
            via, i = sobre[0]
            base = rumo_na_via(via, i)
        else:
            base = rumo_segmento_mais_proximo(no["lat"], no["lon"], idx.vias)
        if base is None:
            return None, "forward/backward sem via próxima"
        return (base if d == "forward" else (base + 180) % 360), f"direction={d}"

    if inferir_mao_unica and len(sobre) == 1:
        via, i = sobre[0]
        s = sentido_mao_unica(via.get("tags", {}))
        if s:
            base = rumo_na_via(via, i)
            return (base if s > 0 else (base + 180) % 360), "via de mão única"
    return None, "sem direção"


def converter(elementos: list[dict], inferir_mao_unica: bool = True,
              hoje: str | None = None) -> tuple[list[Radar], list[str], dict[str, int]]:
    """Converte a resposta da Overpass em radares. Retorna (radares, avisos, estatísticas)."""
    hoje = hoje or date.today().isoformat()
    idx = _Indice(elementos)
    radares: list[Radar] = []
    avisos: list[str] = []
    estat: dict[str, int] = {}

    def conta(chave: str) -> None:
        estat[chave] = estat.get(chave, 0) + 1

    def desativado(tags: dict) -> bool:
        return any(RE_DESATIVADO.search(tags.get(k, "")) for k in ("note", "description", "fixme"))

    nos_usados_por_relacao: set[int] = set()

    # 1) Relações enforcement — trazem o sentido explícito
    for rel in idx.relacoes:
        tags = rel.get("tags", {})
        papeis: dict[str, list[dict]] = {}
        for m in rel.get("members", []):
            if m["type"] == "node" and m["ref"] in idx.nos:
                papeis.setdefault(m["role"], []).append(idx.nos[m["ref"]])
        devices = papeis.get("device", [])
        if not devices:
            avisos.append(f"relação {rel['id']} sem membro device: ignorada")
            conta("relacao_sem_device")
            continue
        if desativado(tags):
            conta("desativado")
            continue
        origem = papeis.get("from", [None])[0]
        destino = papeis.get("to", [None])[0]
        for dev in devices:
            if origem and destino:
                a, b = origem, destino
            elif origem:
                a, b = origem, dev
            elif destino:
                a, b = dev, destino
            else:
                a = b = None
            direcao = rumo(a["lat"], a["lon"], b["lat"], b["lon"]) if a and b and a is not b else None
            if direcao is None:
                # sem from/to utilizáveis: tenta as tags do próprio device
                direcao, _ = _rumo_por_tag(dev, idx, inferir_mao_unica)
            dev_tags = dev.get("tags", {})
            limite = ler_maxspeed(tags.get("maxspeed")) or ler_maxspeed(dev_tags.get("maxspeed"))
            radares.append(Radar(
                latitude=dev["lat"], longitude=dev["lon"],
                tipo=tipo_da_relacao(tags.get("enforcement", "")),
                fonte=Fonte.OSM, limite_kmh=limite, direcao_graus=direcao,
                confiabilidade=CONFIABILIDADE, atualizado_em=hoje,
            ))
            nos_usados_por_relacao.add(dev["id"])
            conta("relacao")

    # 2) Nós speed_camera que não são device de nenhuma relação
    for no in idx.nos.values():
        tags = no.get("tags", {})
        if tags.get("highway") != "speed_camera" or no["id"] in nos_usados_por_relacao:
            continue
        if desativado(tags):
            avisos.append(f"nó {no['id']} marcado como desativado ({tags.get('note')!r}): ignorado")
            conta("desativado")
            continue
        direcao, origem = _rumo_por_tag(no, idx, inferir_mao_unica)
        conta(f"no ({origem})")
        radares.append(Radar(
            latitude=no["lat"], longitude=no["lon"], tipo=tipo_do_no(tags),
            fonte=Fonte.OSM, limite_kmh=ler_maxspeed(tags.get("maxspeed")),
            direcao_graus=direcao, confiabilidade=CONFIABILIDADE, atualizado_em=hoje,
        ))
    return radares, avisos, estat


def coletar(bbox: tuple[float, float, float, float] = BBOX_DF,
            inferir_mao_unica: bool = True) -> ResultadoFonte:
    """Ponto de entrada do pipeline. Nunca levanta exceção: falha vira aviso."""
    resultado = ResultadoFonte(Fonte.OSM)
    try:
        dados = baixar_overpass(montar_consulta(bbox))
        radares, avisos, estat = converter(dados.get("elements", []), inferir_mao_unica)
        resultado.radares = radares
        resultado.avisos = avisos
        log.info("OSM: %d radares (%s)", len(radares),
                 ", ".join(f"{k}: {v}" for k, v in sorted(estat.items())))
    except Exception as e:  # noqa: BLE001 — uma fonte não pode derrubar o pipeline
        msg = f"OSM falhou: {e}"
        log.error(msg)
        resultado.avisos.append(msg)
        resultado.falhou = True
    return resultado


def _main() -> None:
    import json

    p = argparse.ArgumentParser(description="Coleta radares do OSM")
    p.add_argument("--bbox", default=",".join(map(str, BBOX_DF)),
                   help="sul,oeste,norte,leste (padrão: Distrito Federal)")
    p.add_argument("--sem-mao-unica", action="store_true",
                   help="não inferir o sentido pela via de mão única")
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    bbox = tuple(float(v) for v in args.bbox.split(","))
    res = coletar(bbox, inferir_mao_unica=not args.sem_mao_unica)
    for a in res.avisos:
        print("AVISO:", a)
    print(f"Total: {len(res.radares)} radares")
    for r in res.radares[:5]:
        print(json.dumps(r.para_dict(), ensure_ascii=False))


if __name__ == "__main__":
    _main()
