package dev.horizon.trends.adapter.web;

import java.time.Clock;

import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.horizon.platform.spring.caller.CurrentCaller;
import dev.horizon.platform.spring.web.ProblemDetailAdvice;
import dev.horizon.trends.adapter.web.mapper.ReportViewMapper;
import dev.horizon.trends.application.port.TrendFeedbackRepository;
import dev.horizon.trends.application.usecase.ExplainAbsentTermUseCase;
import dev.horizon.trends.application.usecase.ExportReportUseCase;
import dev.horizon.trends.application.usecase.GetTrendReportUseCase;
import dev.horizon.trends.application.usecase.RadarDigestUseCase;
import dev.horizon.trends.application.usecase.RecordTrendFeedbackUseCase;
import dev.horizon.trends.application.usecase.RefreshRadarUseCase;
import dev.horizon.trends.application.usecase.SavedDomainService;
import dev.horizon.trends.application.usecase.SearchTopicsUseCase;
import dev.horizon.trends.config.FeatureFlags;
import dev.horizon.trends.config.FeatureGate;

/**
 * The HTTP boundary itself, executed.
 *
 * <p>Слой поднимается целиком, а сценарии подменяются — потому что предмет проверки здесь именно
 * контур: код ответа, тело ошибки, заголовки, порядок проверок. Логика сценариев проверяется своими
 * тестами и повторно здесь ничего не доказывала бы.
 *
 * <p>Что <b>не</b> подменяется — {@link FeatureGate} и {@link FeatureFlags}: дефект, ради которого
 * слой заведён, живёт ровно в том, дошёл ли вызов до настоящего гейта, и заглушка вместо него
 * проверяла бы заглушку.
 */
@WebMvcTest(
        controllers = {
            ReportController.class,
            SavedDomainController.class,
            TopicController.class,
            FeedbackController.class
        })
@Import({
    ReportController.class,
    SavedDomainController.class,
    TopicController.class,
    FeedbackController.class,
    FeatureGate.class,
    FeatureFlags.class,
    CurrentCaller.class,
    ProblemDetailAdvice.class
})
public abstract class WebSliceTestBase {

    @MockBean
    protected GetTrendReportUseCase reports;

    @MockBean
    protected ExplainAbsentTermUseCase explanations;

    @MockBean
    protected ExportReportUseCase export;

    @MockBean
    protected TrendFeedbackRepository feedback;

    @MockBean
    protected ReportViewMapper mapper;

    @MockBean
    protected SavedDomainService savedDomains;

    @MockBean
    protected RefreshRadarUseCase refresh;

    @MockBean
    protected RadarDigestUseCase radar;

    @MockBean
    protected SearchTopicsUseCase topics;

    @MockBean
    protected RecordTrendFeedbackUseCase recordFeedback;

    @MockBean
    protected Clock clock;

    /**
     * Запрос как есть: входа нет, и спрашивающий всегда один — {@code Caller.SINGLE}.
     *
     * <p>Помощник оставлен, чтобы сценарии читались так же, как раньше; роли больше ничего не
     * значат и игнорируются.
     */
    protected static MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request, String... roles) {
        return request;
    }
}
