package reminders;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RemindersApplication {

    public static void main(String[] args) {
        SpringApplication.run(RemindersApplication.class, args);
    }

    /** The only source of "now" in the app. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
