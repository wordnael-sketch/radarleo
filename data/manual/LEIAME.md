# data/manual

Todo arquivo `.csv` desta pasta entra no ETL (exceto `MODELO*.csv`, que é só exemplo).

## Formato

Copie `MODELO.csv`, renomeie (ex.: `lai_detran_df_2026-11.csv`) e preencha. Cabeçalho:

```
latitude,longitude,tipo,limite_kmh,direcao_graus,rodovia,km,fonte,observacao
```

| coluna | obrigatória | valores |
|---|---|---|
| latitude, longitude | sim | graus decimais (`-15.7939` ou `-15,7939`) |
| tipo | não | FIXO, SEMAFORO, LOMBADA, VELOCIDADE_MEDIA (vazio = DESCONHECIDO) |
| limite_kmh | não | número em km/h |
| direcao_graus | não | rumo do tráfego fiscalizado, 0 = norte, 90 = leste; vazio = os dois sentidos |
| rodovia, km | não | `DF-085`, `12.5` |
| fonte | não | MANUAL (padrão), LAI_DETRAN_DF, LAI_DER_DF |
| observacao | não | texto livre; "desligado", "inativo" ou "desativado" rebaixa a confiabilidade para 50 |

Confiabilidade: LAI = 95, MANUAL = 90.

Separador `,` ou `;`, decimal com ponto ou vírgula, UTF-8 ou Latin-1: dá para salvar direto do Excel como "CSV (separado por ponto e vírgula)".

Linhas com problema (sem coordenada, número inválido, fonte desconhecida) são puladas e aparecem como aviso no log e no relatório; o restante do arquivo é aproveitado.

## Pedidos de LAI

Os textos prontos para o Participa DF estão em [`lai/`](lai/). Quando a resposta chegar, converta a planilha recebida para o formato acima e salve aqui.

Se o órgão mandar o sentido como texto ("sentido Plano Piloto"), converta para graus olhando o mapa: é o rumo para onde o carro fiscalizado está indo.
