"""Testes da deduplicação e da geração de output/."""

import json
from datetime import datetime

import pytest

import consolidar as c
from modelo import Fonte, Radar, ResultadoFonte, TipoRadar

LAT, LON = -15.8, -47.9
METRO_LAT = 1 / 111_195.08  # graus de latitude por metro (Haversine, raio médio)


def radar(norte_m=0.0, direcao=None, fonte=Fonte.OSM, **kw):
    return Radar(latitude=LAT + norte_m * METRO_LAT, longitude=LON,
                 tipo=kw.pop("tipo", TipoRadar.FIXO), fonte=fonte,
                 direcao_graus=direcao, atualizado_em="2026-10-30", **kw)


def test_distancia_haversine():
    assert c.distancia_m(LAT, LON, LAT + 25 * METRO_LAT, LON) == pytest.approx(25, abs=0.01)


# --- os três casos pedidos

def test_mesmo_ponto_vira_um_radar_e_mescla_campos():
    osm_ = radar(0, 180, Fonte.OSM, limite_kmh=60)
    dnit_ = radar(5, None, Fonte.DNIT, rodovia="BR-070", km=6.7, confiabilidade=50)
    lai = radar(2, 175, Fonte.LAI_DETRAN_DF)  # 95, sem limite
    (r,) = c.deduplicar([osm_, dnit_, lai])
    assert r.radar.fonte is Fonte.LAI_DETRAN_DF            # maior confiabilidade prevalece
    assert r.radar.direcao_graus == 175 and r.radar.confiabilidade == 95
    assert r.radar.limite_kmh == 60                        # completado pelo OSM
    assert (r.radar.rodovia, r.radar.km) == ("BR-070", 6.7)  # completado pelo DNIT
    assert r.fontes == ["LAI_DETRAN_DF", "OSM", "DNIT"]


def test_sentidos_opostos_no_mesmo_ponto_sao_dois_radares():
    res = c.deduplicar([radar(0, 0), radar(0, 180)])
    assert sorted(x.radar.direcao_graus for x in res) == [0, 180]
    assert len({x.radar.id for x in res}) == 2


@pytest.mark.parametrize("distancia,esperado", [(25, 1), (35, 2)])
def test_25m_junta_35m_separa(distancia, esperado):
    assert len(c.deduplicar([radar(0, 90), radar(distancia, 90, Fonte.DNIT)])) == esperado


# --- regras de direção

@pytest.mark.parametrize("dir_b,esperado", [(130, 1), (136, 2), (None, 1)])
def test_tolerancia_de_45_graus(dir_b, esperado):
    assert len(c.deduplicar([radar(0, 90), radar(3, dir_b, Fonte.DNIT)])) == esperado


def test_tolerancia_atravessa_o_norte():
    assert len(c.deduplicar([radar(0, 350), radar(3, 20, Fonte.DNIT)])) == 1


def test_sem_direcao_completa_os_dois_sentidos():
    dnit_ = radar(3, None, Fonte.DNIT, rodovia="BR-070", confiabilidade=50)
    res = c.deduplicar([radar(0, 0), radar(0, 180), dnit_])
    assert len(res) == 2
    assert all(x.radar.rodovia == "BR-070" for x in res)


def test_principal_sem_direcao_herda_a_do_membro():
    lai = radar(0, None, Fonte.LAI_DER_DF, limite_kmh=80)
    (r,) = c.deduplicar([lai, radar(4, 90)])
    assert r.radar.fonte is Fonte.LAI_DER_DF and r.radar.direcao_graus == 90


def test_tipo_desconhecido_e_completado():
    lai = radar(0, None, Fonte.LAI_DER_DF, tipo=TipoRadar.DESCONHECIDO)
    (r,) = c.deduplicar([lai, radar(4, None, tipo=TipoRadar.LOMBADA)])
    assert r.radar.tipo is TipoRadar.LOMBADA


def test_resultado_independe_da_ordem_de_entrada():
    entrada = [radar(0, 0), radar(10, None, Fonte.DNIT, confiabilidade=50), radar(20, 10),
               radar(0, 180, Fonte.MANUAL), radar(100, 0)]
    a = sorted(x.radar.id for x in c.deduplicar(entrada))
    b = sorted(x.radar.id for x in c.deduplicar(list(reversed(entrada))))
    assert a == b


# --- geração dos arquivos

def coletores(**fontes):
    return {nome: (lambda rs=rs: rs if isinstance(rs, ResultadoFonte)
                   else ResultadoFonte(Fonte.OSM, list(rs)))
            for nome, rs in fontes.items()}


AGORA = datetime(2026, 11, 2, 6, 0, tzinfo=c.BRASILIA)


def test_gera_json_e_relatorio(tmp_path):
    resumo = c.gerar(tmp_path, coletores(osm=[radar(0, 0), radar(0, 180)],
                                         manual=[radar(1000, None, Fonte.MANUAL)]), AGORA)
    dados = json.loads((tmp_path / "radares.json").read_text(encoding="utf-8"))
    assert set(dados) == {"versao", "gerado_em", "total", "radares"}
    assert dados["total"] == 3 == len(dados["radares"])
    assert dados["versao"].startswith("20261102-") and dados["versao"] == resumo["versao"]
    assert dados["gerado_em"] == "2026-11-02T06:00:00-03:00"
    assert "fontes" in dados["radares"][0]
    rel = (tmp_path / "relatorio.md").read_text(encoding="utf-8")
    assert "Total final:** 3" in rel


def test_invalido_vai_para_o_relatorio(tmp_path):
    fora = Radar(latitude=40.0, longitude=-47.9, tipo="FIXO", fonte="MANUAL")
    c.gerar(tmp_path, coletores(manual=[radar(0), fora]), AGORA)
    assert json.loads((tmp_path / "radares.json").read_text(encoding="utf-8"))["total"] == 1
    assert "fora do Brasil" in (tmp_path / "relatorio.md").read_text(encoding="utf-8")


def test_sem_mudanca_nao_regrava(tmp_path):
    fontes = coletores(manual=[radar(0, 90, Fonte.MANUAL)])
    v1 = c.gerar(tmp_path, fontes, AGORA)
    depois = datetime(2026, 11, 9, 6, 0, tzinfo=c.BRASILIA)
    v2 = c.gerar(tmp_path, fontes, depois)
    assert v2 == {**v1, "mudou": False}
    novo = coletores(manual=[radar(0, 90, Fonte.MANUAL, limite_kmh=60)])
    v3 = c.gerar(tmp_path, novo, depois)
    assert v3["mudou"] and v3["versao"].startswith("20261109-")


def test_fonte_que_falha_usa_a_ultima_coleta_boa(tmp_path):
    c.gerar(tmp_path, coletores(osm=[radar(0, 0), radar(500, 0)]), AGORA)
    falha = ResultadoFonte(Fonte.OSM, avisos=["OSM falhou: sem rede"], falhou=True)
    resumo = c.gerar(tmp_path, coletores(osm=falha), AGORA)
    assert resumo["total"] == 2 and not resumo["mudou"]


def test_cache_e_saida_nao_mudam_se_so_a_data_mudou(tmp_path):
    c.gerar(tmp_path, coletores(osm=[radar(0, 0)]), AGORA)
    arquivos = [tmp_path / "cache" / "osm.json", tmp_path / "radares.json"]
    antes = [a.read_bytes() for a in arquivos]
    outro_dia = Radar(**{**radar(0, 0).para_dict(), "atualizado_em": "2026-11-09"})
    c.gerar(tmp_path, coletores(osm=[outro_dia]), datetime(2026, 11, 9, 6, tzinfo=c.BRASILIA))
    assert [a.read_bytes() for a in arquivos] == antes
    assert b"\r\n" not in antes[1]


def test_fonte_que_encolhe_demais_e_suspeita(tmp_path):
    c.gerar(tmp_path, coletores(osm=[radar(i * 100, 0) for i in range(10)]), AGORA)
    resumo = c.gerar(tmp_path, coletores(osm=[radar(0, 0)]), AGORA)
    assert resumo["total"] == 10


def test_coletor_que_levanta_nao_derruba(tmp_path):
    def explode():
        raise RuntimeError("bug")
    resumo = c.gerar(tmp_path, {"osm": explode, **coletores(manual=[radar(0)])}, AGORA)
    assert resumo["total"] == 1
    assert "não há cache" in (tmp_path / "relatorio.md").read_text(encoding="utf-8")
