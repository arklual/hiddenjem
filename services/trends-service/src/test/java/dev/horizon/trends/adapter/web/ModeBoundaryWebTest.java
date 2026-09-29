package dev.horizon.trends.adapter.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Незнакомый режим анализа на границе HTTP — 400 с перечнем известных.
 *
 * <p>Проверяется на сохранении направления, а не на запуске анализа: разбор параметров у них общий
 * ({@code AnalysisParametersDto}), а сохранение входит в срез. Важно, что отказ доезжает до клиента
 * как ошибка валидации с перечнем, а не как 500 и не как тихий быстрый режим.
 */
class ModeBoundaryWebTest extends WebSliceTestBase {

    @Autowired
    private MockMvc mvc;

    @Test
    void anUnknownModeIsRefusedWithTheKnownOnes() throws Exception {
        mvc.perform(
                        withAuth(
                                post("/api/v1/saved-domains")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                {"query": "квантовые вычисления", "parameters": {"mode": "slow"}}
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("fast, quality")));

        verify(savedDomains, never()).save(any(), anyString(), any());
    }
}
