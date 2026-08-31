package com.study.monolithic_architecture;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MonolithicArchitectureApplicationTests {

	@Test
	void contextLoads() {
	}

}
