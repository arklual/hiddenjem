package dev.horizon.platform.spring.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import dev.horizon.platform.spring.caller.CurrentCaller;
import dev.horizon.platform.spring.web.ProblemDetailAdvice;

/** Registers the shared web concerns: uniform error rendering and caller resolution. */
@AutoConfiguration(after = PlatformAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetailAdvice problemDetailAdvice(Clock clock) {
        return new ProblemDetailAdvice(clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public CurrentCaller currentCaller() {
        return new CurrentCaller();
    }
}
