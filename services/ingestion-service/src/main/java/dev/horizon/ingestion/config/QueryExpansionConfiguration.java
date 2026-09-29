package dev.horizon.ingestion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.domain.port.QueryExpander;
import dev.horizon.ingestion.expansion.HttpQueryExpander;

/** Расширение запроса включается явно и только с адресом сервиса моделей. */
@Configuration
public class QueryExpansionConfiguration {

    @Bean
    public QueryExpander queryExpander(QueryExpansionProperties properties, ObjectMapper objectMapper) {
        return properties.active() ? new HttpQueryExpander(properties, objectMapper) : QueryExpander.NONE;
    }
}
