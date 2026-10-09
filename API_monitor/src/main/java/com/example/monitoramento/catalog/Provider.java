package com.example.monitoramento.catalog;

import java.time.Instant;
import java.util.UUID;

public record Provider(UUID id, String name, String contact, Instant createdAt, Instant updatedAt) {}
