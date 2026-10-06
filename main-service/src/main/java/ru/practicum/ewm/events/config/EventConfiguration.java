package ru.practicum.ewm.events.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class EventConfiguration {
    @Bean
    public Clock eventClock() {
        return Clock.systemDefaultZone();
    }
}
