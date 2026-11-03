package in.yograj.learnspringboot.railway_booking.config;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RazorpayE2eDataSourceGuardTests {

    private static final String TEST_DATABASE_URL =
            "jdbc:postgresql://localhost:5432/railway_booking_test";

    @Test
    void acceptsOnlyExplicitTestDatabaseUrl() {
        DataSource dataSource = mock(DataSource.class);

        assertThrows(IllegalStateException.class,
                () -> RazorpayE2eDataSourceGuard.verify(
                        "jdbc:postgresql://localhost:5432/railway_booking", dataSource));
        assertThrows(IllegalStateException.class,
                () -> RazorpayE2eDataSourceGuard.verify(null, dataSource));
    }

    @Test
    void rejectsNonPostgresAndUrlsWithEmbeddedCredentials() {
        DataSource dataSource = mock(DataSource.class);

        assertThrows(IllegalStateException.class,
                () -> RazorpayE2eDataSourceGuard.verify(
                        "jdbc:h2:mem:railway_booking_test", dataSource));
        assertThrows(IllegalStateException.class,
                () -> RazorpayE2eDataSourceGuard.verify(
                        "jdbc:postgresql://user:password@localhost:5432/railway_booking_test", dataSource));
    }

    @Test
    void acceptsOnlyVerifiedConnectionToTestDatabase() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("railway_booking_test");
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn(TEST_DATABASE_URL);

        assertDoesNotThrow(() -> RazorpayE2eDataSourceGuard.verify(TEST_DATABASE_URL, dataSource));
    }

    @Test
    void rejectsConnectionWhoseCatalogDoesNotMatchTestDatabase() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("railway_booking");

        assertThrows(IllegalStateException.class,
                () -> RazorpayE2eDataSourceGuard.verify(TEST_DATABASE_URL, dataSource));
    }
}
