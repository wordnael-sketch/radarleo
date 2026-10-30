"""Testes do conversor OSM com elementos no formato real devolvido pela Overpass."""

import pytest
import requests

from fontes import osm
from modelo import TipoRadar


def no(id_, lat, lon, **tags):
    e = {"type": "node", "id": id_, "lat": lat, "lon": lon}
    if tags:
        e["tags"] = tags
    return e


def via(id_, nos, **tags):
    """Via com geometria, como em 'out body geom'."""
    return {"type": "way", "id": id_, "nodes": [n["id"] for n in nos],
            "geometry": [{"lat": n["lat"], "lon": n["lon"]} for n in nos],
            "tags": {"highway": "primary", **tags}}


def rel(id_, membros, **tags):
    return {"type": "relation", "id": id_,
            "members": [{"type": "node", "ref": r, "role": p} for p, r in membros],
            "tags": {"type": "enforcement", **tags}}


# Via reta sentido norte (lat crescente), lon constante
A = no(1, -15.800, -47.900)
B = no(2, -15.799, -47.900)
C = no(3, -15.798, -47.900)


def converter(elementos, **kw):
    radares, avisos, _ = osm.converter(elementos, hoje="2026-10-30", **kw)
    return radares, avisos


def test_no_simples_sem_direcao_em_via_mao_dupla():
    cam = no(10, -15.799, -47.900, highway="speed_camera", maxspeed="60")
    w = via(100, [A, {**cam}, C], oneway="no")
    (r,), _ = converter([cam, A, C, w])
    assert r.tipo is TipoRadar.FIXO and r.limite_kmh == 60
    assert r.direcao_graus is None and r.confiabilidade == 60


def test_direction_em_graus_e_cardeal():
    (r,), _ = converter([no(10, -15.8, -47.9, highway="speed_camera", direction="180")])
    assert r.direcao_graus == 180
    (r,), _ = converter([no(10, -15.8, -47.9, highway="speed_camera", direction="SO")])
    assert r.direcao_graus == 225


def test_forward_backward_usam_rumo_da_via():
    fwd = no(10, -15.799, -47.900, highway="speed_camera", direction="forward")
    w = via(100, [A, fwd, C], oneway="no")
    (r,), _ = converter([fwd, A, C, w])
    assert r.direcao_graus == pytest.approx(0, abs=0.5)

    bwd = no(10, -15.799, -47.900, highway="speed_camera", direction="backward")
    w = via(100, [A, bwd, C], oneway="no")
    (r,), _ = converter([bwd, A, C, w])
    assert r.direcao_graus == pytest.approx(180, abs=0.5)


def test_forward_fora_da_via_usa_segmento_mais_proximo():
    cam = no(10, -15.799, -47.9001, highway="speed_camera", direction="forward")  # ~10 m ao lado
    w = via(100, [A, B, C])
    (r,), _ = converter([cam, A, B, C, w])
    assert r.direcao_graus == pytest.approx(0, abs=0.5)


def test_mao_unica_infere_sentido_e_pode_ser_desligado():
    cam = no(10, -15.799, -47.900, highway="speed_camera")
    w = via(100, [C, cam, A], oneway="yes")  # desenhada para o sul
    (r,), _ = converter([cam, A, C, w])
    assert r.direcao_graus == pytest.approx(180, abs=0.5)
    (r,), _ = converter([cam, A, C, w], inferir_mao_unica=False)
    assert r.direcao_graus is None


def test_oneway_menos_um_inverte():
    cam = no(10, -15.799, -47.900, highway="speed_camera")
    w = via(100, [A, cam, C], oneway="-1")
    (r,), _ = converter([cam, A, C, w])
    assert r.direcao_graus == pytest.approx(180, abs=0.5)


def test_calcada_nao_conta_como_via():
    cam = no(10, -15.799, -47.900, highway="speed_camera")
    w = via(100, [A, cam, C], oneway="yes")
    w["tags"]["highway"] = "footway"
    (r,), _ = converter([cam, A, C, w])
    assert r.direcao_graus is None


def test_relacao_from_to_define_sentido_e_absorve_o_no():
    dev = no(10, -15.799, -47.900, highway="speed_camera", maxspeed="40")
    r_ = rel(500, [("from", 3), ("device", 10), ("to", 1)], enforcement="maxspeed", maxspeed="60")
    radares, _ = converter([dev, A, C, r_])
    assert len(radares) == 1  # o nó speed_camera não é duplicado
    (r,) = radares
    assert r.direcao_graus == pytest.approx(180, abs=0.5)  # de C (norte) para A (sul)
    assert r.limite_kmh == 60  # a relação prevalece sobre o nó


def test_relacao_so_com_from():
    dev = no(10, -15.799, -47.900)
    r_ = rel(500, [("from", 1), ("device", 10)], enforcement="traffic_signals")
    (r,), _ = converter([dev, A, r_])
    assert r.tipo is TipoRadar.SEMAFORO
    assert r.direcao_graus == pytest.approx(0, abs=0.5)


def test_mesmo_device_em_dois_sentidos_vira_dois_radares():
    dev = no(10, -15.799, -47.900, highway="speed_camera")
    ida = rel(500, [("from", 1), ("device", 10), ("to", 3)], enforcement="maxspeed")
    volta = rel(501, [("from", 3), ("device", 10), ("to", 1)], enforcement="maxspeed")
    radares, _ = converter([dev, A, C, ida, volta])
    assert sorted(round(r.direcao_graus) for r in radares) == [0, 180]
    assert len({r.id for r in radares}) == 2


def test_relacao_sem_device_gera_aviso():
    r_ = rel(500, [("from", 1), ("to", 3)], enforcement="maxspeed")
    radares, avisos = converter([A, C, r_])
    assert radares == [] and "sem membro device" in avisos[0]


def test_desativado_e_ignorado():
    cam = no(10, -15.8, -47.9, highway="speed_camera", note="destativado")
    radares, avisos = converter([cam])
    assert radares == [] and "desativado" in avisos[0]


def test_semaforo_por_crossing():
    (r,), _ = converter([no(10, -15.8, -47.9, highway="speed_camera", crossing="traffic_signals")])
    assert r.tipo is TipoRadar.SEMAFORO


@pytest.mark.parametrize("valor,esperado", [
    ("60", 60), ("40 mph", 64), ("80 km/h", 80), ("BR:urban", None),
    ("60;40", None), (None, None), ("none", None),
])
def test_ler_maxspeed(valor, esperado):
    assert osm.ler_maxspeed(valor) == esperado


class _Resp:
    def __init__(self, status, texto):
        self.status_code, self.text = status, texto

    def json(self):
        import json
        return json.loads(self.text)


def test_retry_ate_conseguir(monkeypatch):
    monkeypatch.setattr(osm.time, "sleep", lambda s: None)
    respostas = iter([_Resp(429, "Too Many Requests"), _Resp(502, "<html>"),
                      _Resp(200, '{"elements": []}')])
    assert osm.baixar_overpass("q", post=lambda *a, **k: next(respostas)) == {"elements": []}


def test_coletar_nao_quebra_quando_overpass_cai(monkeypatch):
    monkeypatch.setattr(osm.time, "sleep", lambda s: None)

    def falha(*a, **k):
        raise requests.ConnectionError("sem rede")

    monkeypatch.setattr(osm.requests, "post", falha)
    monkeypatch.setattr(osm.baixar_overpass, "__defaults__", (osm.URL_OVERPASS, falha))
    res = osm.coletar()
    assert res.radares == [] and "OSM falhou" in res.avisos[0]
