package com.study.monolithic_architecture;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트도 실제 MySQL에서 돈다.
 *
 * <p>H2로 시험하면 방언·예약어·트랜잭션 동작이 운영과 달라 여기서는 통과하고 배포 후에 깨진다.
 * 테스트 실행마다 새 컨테이너를 쓰므로 초기화된 환경이 보장된다. (NFR-09)
 *
 * <p>컨테이너 이미지는 compose.yaml과 같은 버전을 쓴다. 둘이 어긋나면 시험한 것과
 * 운영하는 것이 달라진다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.4");

	@Bean
	@ServiceConnection
	MySQLContainer<?> mysqlContainer() {
		return new MySQLContainer<>(MYSQL_IMAGE)
			.withDatabaseName("monolith")
			.withUsername("monolith")
			.withPassword("monolith");
	}
}
