package com.example.monitoramento.catalog;

import java.time.Instant;
import java.util.UUID;

public record Site(UUID id, String name, String location, Instant createdAt, Instant updatedAt) {}
