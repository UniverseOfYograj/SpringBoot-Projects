package in.yograj.learnspringboot.railway_booking.config;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayConfigTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FlywayConfig.class)
            .withBean(DataSource.class, () -> {
                JdbcDataSource ds = new JdbcDataSource();
                ds.setURL("jdbc:h2:mem:flyway_order_test;DB_CLOSE_DELAY=-1");
                ds.setUser("sa");
                ds.setPassword("");
                return ds;
            });

    @Test
    void flywayBeansPresentWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "spring.flyway.enabled=true",
                        "spring.flyway.locations=classpath:nonexistent-migration-dir"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(Flyway.class);
                    assertThat(context).hasSingleBean(EntityManagerFactoryDependsOnPostProcessor.class);
                });
    }

    @Test
    void flywayBeansDisabledWhenPropertyFalse() {
        contextRunner
                .withPropertyValues("spring.flyway.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(Flyway.class);
                    assertThat(context).doesNotHaveBean(EntityManagerFactoryDependsOnPostProcessor.class);
                });
    }
}
