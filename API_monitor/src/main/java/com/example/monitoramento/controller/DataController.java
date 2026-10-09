package com.example.monitoramento.controller;

import com.example.monitoramento.demo.DemoMonitoringService;
import com.example.monitoramento.status.CircuitSnapshot;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Alias temporário para a API nova; não lê nem publica o JSON institucional. */
@Deprecated
@RestController
@Profile("demo")
public class DataController {

    private final DemoMonitoringService monitoring;

    public DataController(DemoMonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    @GetMapping("/hostdata")
    public List<CircuitSnapshot> getHostData() {
        return monitoring.circuits();
    }
}
