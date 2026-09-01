package com.study.monolithic_architecture;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 테스트가 실제로 MySQL에서 도는지 확인한다.
 *
 * <p>조용히 어긋나기 가장 쉬운 지점이다. 어떤 이유로 인메모리 DB로 되돌아가면
 * 테스트는 계속 통과하지만 방언·예약어·트랜잭션 동작이 운영과 달라진다.
 * 그 사실을 배포 후가 아니라 여기서 알아야 한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseEngineTest {

	@Autowired
	DataSource dataSource;

	@Test
	@DisplayName("테스트는 MySQL에 붙는다")
	void 테스트는_MySQL에서_돈다() throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			String product = connection.getMetaData().getDatabaseProductName();
			String url = connection.getMetaData().getURL();

			assertThat(product).isEqualTo("MySQL");
			assertThat(url).startsWith("jdbc:mysql://");
		}
	}

	@Test
	@DisplayName("compose.yaml과 같은 메이저 버전을 쓴다")
	void 컨테이너_버전이_운영과_같다() throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("8.4");
		}
	}
}
