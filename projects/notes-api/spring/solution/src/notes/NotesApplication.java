package notes;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class NotesApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotesApplication.class, args);
    }

    /** The only source of time in the app, so tests could swap it for a fixed clock. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
