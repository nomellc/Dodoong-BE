package com.samdasu.dodoong.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Testcontainers MySQL을 공유하는 통합 테스트 베이스 (Phase 2).
 * 컨테이너는 싱글턴으로 1회만 기동해 서브클래스들이 공유한다
 * (@Container 방식은 테스트 클래스마다 컨테이너를 새로 띄워 느려짐).
 * Redis는 사용하는 테스트가 없어 기동하지 않는다(Spring의 Redis 연결은 lazy).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=Y29kZXgtdGVzdC1qd3Qtc2VjcmV0LWtleS0zMi1ieXRlcyEh",
        "jwt.access-token-expiration=1800000",
        "jwt.refresh-token-expiration=1209600000"
})
public abstract class IntegrationTestSupport {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
