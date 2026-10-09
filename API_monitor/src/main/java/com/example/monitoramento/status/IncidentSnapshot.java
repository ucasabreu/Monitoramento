package com.example.monitoramento.status;

import java.time.Instant;
import java.util.UUID;

/** elapsedSeconds é o intervalo do incidente; lacunas impedem tratá-lo como downtime comprovado. */
public record IncidentSnapshot(UUID id, UUID circuitId, Instant firstFailureAt, Instant downConfirmedAt,
                               Instant firstRecoveryAt, Instant recoveryConfirmedAt,
                               boolean beganWithoutConfirmedUp, boolean hasObservationGap,
                               long elapsedSeconds) {
}
