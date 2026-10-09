package com.example.monitoramento;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "monitoring.demo.enabled=false")
class MonitoramentoApplicationTests {

	@Test
	void contextLoads() {
	}

}
