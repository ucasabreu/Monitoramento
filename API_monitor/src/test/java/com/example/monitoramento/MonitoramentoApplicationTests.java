package com.example.monitoramento;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "monitoring.demo.enabled=false")
@ActiveProfiles({"demo", "memory"})
class MonitoramentoApplicationTests {

	@Test
	void contextLoads() {
	}

}
