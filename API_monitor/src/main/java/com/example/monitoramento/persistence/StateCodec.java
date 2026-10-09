package com.example.monitoramento.persistence;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class StateCodec {
    private final JsonMapper mapper;
    public StateCodec(JsonMapper mapper) { this.mapper = mapper; }
    public String encode(Object value) { return mapper.writeValueAsString(value); }
    public <T> T decode(String json, Class<T> type) { return mapper.readValue(json, type); }
}
