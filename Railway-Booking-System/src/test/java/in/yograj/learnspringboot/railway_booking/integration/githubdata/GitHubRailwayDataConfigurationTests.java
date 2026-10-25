package in.yograj.learnspringboot.railway_booking.integration.githubdata;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class GitHubRailwayDataConfigurationTests {

    @Test
    void providesRestClientBuilderBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                GitHubRailwayDataConfiguration.class)) {
            assertNotNull(context.getBean(RestClient.Builder.class));
        }
    }
}
