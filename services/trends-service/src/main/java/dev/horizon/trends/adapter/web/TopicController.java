package dev.horizon.trends.adapter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.horizon.platform.spring.caller.CurrentCaller;
import dev.horizon.trends.adapter.web.dto.TopicSearchView;
import dev.horizon.trends.application.usecase.SearchTopicsUseCase;
import dev.horizon.trends.config.FeatureFlag;
import dev.horizon.trends.config.FeatureGate;

/** Поиск по темам накопленных отчётов (OpenAPI tag {@code Topics}). */
@RestController
@RequestMapping("/api/v1/topics")
public class TopicController {

    private final SearchTopicsUseCase topics;
    private final FeatureGate features;
    private final CurrentCaller currentUser;

    public TopicController(SearchTopicsUseCase topics, FeatureGate features, CurrentCaller currentUser) {
        this.topics = topics;
        this.features = features;
        this.currentUser = currentUser;
    }

    /**
     * Где эта тема уже встречалась (UC-17).
     *
     * <p>GET, а не POST: запрос ничего не меняет, а ссылку на выдачу аналитик пересылает коллеге —
     * и она обязана открыться у него в его же границах видимости, а не показать сохранённый чужой
     * ответ.
     */
    @GetMapping("/search")
    public TopicSearchView search(@RequestParam("q") String query) {
        features.require(FeatureFlag.TOPIC_SEARCH);
        return TopicSearchView.from(topics.search(query, ReportViewers.of(currentUser.require())));
    }
}
