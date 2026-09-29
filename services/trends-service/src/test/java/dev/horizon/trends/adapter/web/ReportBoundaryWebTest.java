package dev.horizon.trends.adapter.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.trends.application.port.TermTraceExplainer;
import dev.horizon.trends.support.Fixtures;

/**
 * What the boundary answers when the feature is on (BR-A82, BR-A84).
 *
 * <p>Второй контекст с флагами по умолчанию: первый проверяет, что выключенного нет, этот — что
 * включённое отвечает верно. Разделены потому, что флаги читаются один раз при старте, и смешивать
 * два состояния в одном контексте значило бы проверять не то, что написано.
 */
class ReportBoundaryWebTest extends WebSliceTestBase {

    private static final UUID REPORT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Autowired
    private MockMvc mvc;

    @Test
    void aForeignReportAnswersExactlyLikeAMissingOne() throws Exception {
        // BR-A82. Разные ответы на «нет такого» и «есть, но не ваше» — это подтверждение
        // существования отчёта тому, кто перебирает идентификаторы. Правило живёт в домене; здесь
        // проверяется, что до HTTP оно доезжает неизменным.
        when(reports.get(any(), any())).thenThrow(HorizonException.notFound("Отчёт", REPORT));

        mvc.perform(withAuth(get("/api/v1/reports/" + REPORT)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                // В теле нет ничего, кроме эха присланного идентификатора: ни организации-владельца,
                // ни причины отказа, по которой два случая можно было бы различить.
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(REPORT.toString())));
    }

    @Test
    void anUnfinishedTraceAnswersAcceptedWithoutHoldingTheConnection() throws Exception {
        // Повтор анализа идёт минутами; соединение, которое столько молчит, обрывает прокси, и
        // аналитик видит 502 при исправном движке. Пока ответа нет — 202 и когда спросить снова.
        when(explanations.explainIfReady(any(), any(), any())).thenReturn(Optional.empty());

        mvc.perform(withAuth(get("/api/v1/reports/" + REPORT + "/explain?term=firewall")))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(3));
    }

    @Test
    void aFinishedTraceIsReturnedByTheSameAddress() throws Exception {
        var trace = new TermTraceExplainer.TermTrace(
                "firewall", null, "unigram_termhood", "dropped", "ниже порога", java.util.Map.of(), false);
        when(explanations.explainIfReady(any(), any(), any()))
                .thenReturn(Optional.of(new TermTraceExplainer.Explanation(List.of("extracted"), List.of(trace))));

        mvc.perform(withAuth(get("/api/v1/reports/" + REPORT + "/explain?term=firewall")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traces[0].stage").value("unigram_termhood"));
    }

    @Test
    void anUnknownExportFormatIsRefusedWithTheListOfSupportedOnes() throws Exception {
        // BR-A84. Молчаливая подмена формата — это файл не того вида под нужным именем, и узнают об
        // этом, открыв его.
        when(reports.get(any(), any())).thenReturn(Fixtures.report());

        mvc.perform(withAuth(get("/api/v1/reports/" + REPORT + "/export?format=docx")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("markdown")));
    }

    @Test
    void theBriefingIsSentAsAFileAndNotAsAPage() throws Exception {
        // Заголовки — это то, из-за чего браузер сохраняет файл, а не показывает разметку. Ни один
        // тест до сих пор их не исполнял, а меняются они одной строкой.
        when(reports.get(any(), any())).thenReturn(Fixtures.report());
        when(export.toMarkdown(any())).thenReturn("# отчёт\n");

        mvc.perform(withAuth(get("/api/v1/reports/" + REPORT + "/export?format=markdown")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".md")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    void theSpreadsheetExportKeepsItsOwnContentType() throws Exception {
        // Контраст к предыдущему: три формата на одном эндпоинте, и перепутанный тип содержимого
        // ломает ровно тот формат, который открывают чаще всего.
        when(reports.get(any(), any())).thenReturn(Fixtures.report());
        when(export.toCsv(any())).thenReturn("rank;trend\n");

        mvc.perform(withAuth(get("/api/v1/reports/" + REPORT + "/export?format=csv")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".csv")));
    }
}
