"""Testes do leitor de CSVs manuais e de LAI."""

import shutil
from pathlib import Path

from fontes import manual
from modelo import Fonte, TipoRadar

CAB = "latitude,longitude,tipo,limite_kmh,direcao_graus,rodovia,km,fonte,observacao\n"


def ler(texto):
    return manual.ler_texto_csv(texto, "t.csv", hoje="2026-10-30")


def test_linha_completa():
    (r,), avisos = ler(CAB + "-15.79,-47.88,FIXO,60,180,DF-085,12.5,LAI_DER_DF,ok\n")
    assert avisos == []
    assert (r.latitude, r.longitude, r.tipo, r.fonte) == (-15.79, -47.88, TipoRadar.FIXO,
                                                           Fonte.LAI_DER_DF)
    assert (r.limite_kmh, r.direcao_graus, r.rodovia, r.km) == (60, 180, "DF-085", 12.5)
    assert r.confiabilidade == 95


def test_campos_opcionais_vazios():
    (r,), _ = ler(CAB + "-15.79,-47.88,,,,,,,\n")
    assert r.fonte is Fonte.MANUAL and r.confiabilidade == 90
    assert r.tipo is TipoRadar.DESCONHECIDO
    assert r.limite_kmh is None and r.direcao_graus is None and r.rodovia is None


def test_excel_ptbr_ponto_e_virgula_e_decimal_com_virgula():
    texto = CAB.replace(",", ";") + "-15,79;-47,88;Semáforo;;;;;lai_detran_df;\n"
    (r,), avisos = ler(texto)
    assert avisos == []
    assert (r.latitude, r.longitude) == (-15.79, -47.88)
    assert r.tipo is TipoRadar.SEMAFORO and r.fonte is Fonte.LAI_DETRAN_DF


def test_apelidos_de_tipo():
    assert manual.ler_tipo("Lombada eletrônica") is TipoRadar.LOMBADA
    assert manual.ler_tipo("velocidade média") is TipoRadar.VELOCIDADE_MEDIA
    assert manual.ler_tipo("pardal") is TipoRadar.FIXO
    assert manual.ler_tipo("xyz") is None


def test_desligado_rebaixa_confiabilidade():
    (r,), _ = ler(CAB + "-15.79,-47.88,FIXO,60,,,,LAI_DETRAN_DF,Equipamento DESLIGADO\n")
    assert r.confiabilidade == 50


def test_linhas_ruins_viram_aviso_e_o_resto_e_aproveitado():
    texto = CAB + (
        ",,FIXO,60,,,,,sem coordenada\n"
        "-15.79,abc,FIXO,60,,,,,número ruim\n"
        "-15.79,-47.88,FIXO,60,,,,PREFEITURA,fonte ruim\n"
        "-15.79,-47.88,TORRE,60,,,,,tipo ruim\n"
        "\n"
        "-15.80,-47.89,LOMBADA,40,,,,,boa\n"
    )
    radares, avisos = ler(texto)
    assert len(radares) == 2  # tipo ruim entra como DESCONHECIDO
    assert radares[0].tipo is TipoRadar.DESCONHECIDO
    assert len(avisos) == 4
    assert avisos[0].startswith("t.csv:2:")


def test_cabecalho_incompleto_ignora_o_arquivo():
    radares, avisos = ler("lat,lon\n-15.79,-47.88\n")
    assert radares == [] and "cabeçalho sem as colunas" in avisos[0]


def test_coletar_le_a_pasta_e_pula_modelo(tmp_path):
    raiz = Path(__file__).resolve().parents[2]
    shutil.copy(raiz / "data" / "manual" / "MODELO.csv", tmp_path / "MODELO.csv")
    (tmp_path / "meus.csv").write_text(CAB + "-15.79,-47.88,FIXO,60,,,,,\n", encoding="utf-8")
    (tmp_path / "latin1.csv").write_bytes(
        (CAB + "-15.80,-47.89,SEMAFORO,,,,,,avanço de sinal\n").encode("latin-1"))
    (tmp_path / "ruim.csv").write_text("x,y\n1,2\n", encoding="utf-8")
    res = manual.coletar(tmp_path)
    assert len(res.radares) == 2
    assert len(res.avisos) == 1 and "ruim.csv" in res.avisos[0]


def test_modelo_csv_e_valido():
    """O próprio MODELO.csv tem que passar pelo leitor sem avisos."""
    raiz = Path(__file__).resolve().parents[2]
    radares, avisos = manual.ler_texto_csv(
        (raiz / "data" / "manual" / "MODELO.csv").read_text(encoding="utf-8"), "MODELO.csv")
    assert avisos == [] and len(radares) == 3


def test_coletar_pasta_inexistente_nao_quebra(tmp_path):
    res = manual.coletar(tmp_path / "nao_existe")
    assert res.radares == [] and res.avisos == []
