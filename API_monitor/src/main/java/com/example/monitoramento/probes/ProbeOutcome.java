package com.example.monitoramento.probes;

public enum ProbeOutcome {
    SUCCESS,
    FAILURE,
    /** Falha de execução/coletor; não é evidência de queda do destino. */
    ERROR
}
