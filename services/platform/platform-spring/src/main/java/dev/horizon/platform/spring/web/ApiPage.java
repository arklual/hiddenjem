package dev.horizon.platform.spring.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** Wire format for paginated responses, matching {@code PageMeta} in the OpenAPI contract. */
public record ApiPage<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <S, T> ApiPage<T> of(Page<S> page, Function<S, T> mapper) {
        return new ApiPage<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
