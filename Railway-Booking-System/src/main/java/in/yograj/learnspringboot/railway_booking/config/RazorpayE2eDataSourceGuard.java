package in.yograj.learnspringboot.railway_booking.config;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;

final class RazorpayE2eDataSourceGuard {

    private static final String EXPECTED_DATABASE = "railway_booking_test";
    private static final String EXPECTED_URL_PATH = "/" + EXPECTED_DATABASE;

    private RazorpayE2eDataSourceGuard() {
    }

    static void verify(String configuredJdbcUrl, DataSource dataSource) {
        if (!targetsTestDatabase(configuredJdbcUrl)) {
            throw new IllegalStateException(
                    "The razorpay-e2e profile requires a PostgreSQL JDBC URL targeting railway_booking_test");
        }

        try (Connection connection = dataSource.getConnection()) {
            if (!EXPECTED_DATABASE.equals(connection.getCatalog())
                    || !targetsTestDatabase(connection.getMetaData().getURL())) {
                throw new IllegalStateException(
                        "The razorpay-e2e profile could not verify the connected database as railway_booking_test");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "The razorpay-e2e profile could not verify the connected database as railway_booking_test",
                    exception);
        }
    }

    private static boolean targetsTestDatabase(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            return false;
        }

        try {
            URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
            return "postgresql".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    && EXPECTED_URL_PATH.equals(uri.getRawPath());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
