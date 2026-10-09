package com.example.monitoramento;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;



@SpringBootApplication
@ConfigurationPropertiesScan
public class MonitoramentoApplication {
    public static void main(String[] args) {
        // Inicia a aplicação Spring Boot
        SpringApplication.run(MonitoramentoApplication.class, args);
    }
}
