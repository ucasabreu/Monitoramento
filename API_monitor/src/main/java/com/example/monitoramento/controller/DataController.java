package com.example.monitoramento.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.springframework.web.bind.annotation.CrossOrigin; // Importe a anotação @CrossOrigin

@RestController
public class DataController {

    @GetMapping(value = "/hostdata", produces = MediaType.APPLICATION_JSON_VALUE)
    @CrossOrigin("*") // Substitua pelo endereço do servidor local onde o frontend está sendo servido
    public String getHostData() {
        try {
            // Carrega o arquivo hostdata.json da pasta resources
            Resource resource = new ClassPathResource("hostdata.json");
            byte[] jsonDataBytes = Files.readAllBytes(resource.getFile().toPath());
            return new String(jsonDataBytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            return "Erro ao ler o arquivo hostdata.json.";
        }
    }
}
