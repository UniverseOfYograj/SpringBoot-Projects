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

    @Test
    void allNineMigrationsDiscoveredInStrictSequentialOrder() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:flyway_discovery_test;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        org.flywaydb.core.api.MigrationInfo[] migrations = flyway.info().all();
        assertThat(migrations).hasSize(9);
        assertThat(migrations[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(migrations[1].getVersion().getVersion()).isEqualTo("2");
        assertThat(migrations[2].getVersion().getVersion()).isEqualTo("3");
        assertThat(migrations[3].getVersion().getVersion()).isEqualTo("4");
        assertThat(migrations[4].getVersion().getVersion()).isEqualTo("5");
        assertThat(migrations[5].getVersion().getVersion()).isEqualTo("6");
        assertThat(migrations[6].getVersion().getVersion()).isEqualTo("7");
        assertThat(migrations[7].getVersion().getVersion()).isEqualTo("8");
        assertThat(migrations[7].getDescription()).isEqualTo("add passengers and multi seat support");
        assertThat(migrations[8].getVersion().getVersion()).isEqualTo("9");
        assertThat(migrations[8].getDescription()).isEqualTo("add booking fare breakdown");
    }
}
