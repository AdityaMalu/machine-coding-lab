package reminders;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    /**
     * Sends notifications. Small on purpose (the mail provider rate-limits us). Tasks carry their tenant across
     * (TaskDecorator), and when the queue is full the submitting thread does the work itself instead of the
     * task being thrown away.
     */
    @Bean
    ThreadPoolTaskExecutor notifyExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(10);
        ex.setThreadNamePrefix("notify-");
        ex.setTaskDecorator(TenantContext::propagate);
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return ex;
    }
}
