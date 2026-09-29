package dev.horizon.trends.adapter.persistence;

import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import dev.horizon.trends.config.TrendsServiceApplication;

/**
 * The middle floor of the pyramid, in the service where the shipped defects actually lived.
 *
 * <p>A pointer nobody wrote, a lineage keyed by the wrong column, an in-flight lookup scoped to the
 * wrong owner — all three were single {@code WHERE} clauses, all three were covered by unit tests
 * that replaced the repository, and all three reached production.
 *
 * <p>{@code @ContextConfiguration} is explicit because the application class sits in
 * {@code dev.horizon.trends.config}, a sibling of this package rather than an ancestor — the upward
 * search {@code // Без этого @DataJpaTest подменяет датасорс встроенным со случайным именем, и все свойства ниже
 * // становятся украшением: тесты идут на H2 в обычном режиме, а файл утверждает, что в режиме
 * // совместимости с PostgreSQL. Ровно то «объявлено и не подключено», ради которого слой и заведён.
 * @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
 * @DataJpaTest} performs would not find it.
 *
 * <p>H2 in PostgreSQL mode. It runs JPQL and derived names; it does not run the native
 * {@code DISTINCT ON} of the carried-feedback lookup, the JDBC report repository, partial indexes or
 * the migrations. Those need Testcontainers, and therefore Docker.
 */
@DataJpaTest
@ContextConfiguration(classes = TrendsServiceApplication.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:trends;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.default_schema=",
            "spring.flyway.enabled=false"
        })
public abstract class PersistenceTestBase {}
