package com.example.monitoramento.circuits;

import java.util.Objects;
import java.util.UUID;

/** Identidade do circuito, independente de arquivos e do endereço monitorado. */
public record CircuitDefinition(UUID id, String name, String site, String provider, String role) {
    public CircuitDefinition {
        Objects.requireNonNull(id, "id");
        for (String value : new String[] {name, site, provider, role}) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Identificação do circuito não pode estar vazia");
            }
        }
    }
}
