package com.example.monitoramento.probes;

import com.example.monitoramento.circuits.CircuitDefinition;

@FunctionalInterface
public interface ProbeClient {
    ProbeResult probe(CircuitDefinition circuit) throws Exception;
}
