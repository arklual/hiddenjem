package dev.horizon.ingestion.connector.nsf.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * Ответ NSF Award Search API ({@code /services/v1/awards.json}).
 *
 * <p>Поля названы так, как их называет API; {@code printFields} сервис на деле игнорирует и отдаёт
 * запись целиком, поэтому неизвестные поля пропускаются, а не роняют разбор.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NsfResponse(Body response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(List<Award> award, Metadata metadata, List<Notification> serviceNotification) {

        public Body {
            award = award == null ? List.of() : List.copyOf(award);
            serviceNotification = serviceNotification == null ? List.of() : List.copyOf(serviceNotification);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadata(Integer offset, Integer rpp, Integer totalCount) {}

    /** Сообщение сервиса об ошибке запроса — так API отвечает на неверный параметр. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Notification(String notificationType, String notificationCode, String notificationMessage) {}

    /**
     * Награда.
     *
     * @param date дата решения о награде, {@code MM/dd/yyyy}; по ней API фильтрует
     *     {@code dateStart}/{@code dateEnd}
     * @param startDate начало работ — бывает и в будущем относительно {@code date}
     * @param fundProgramName программа финансирования: «SBIR Phase I», «I-Corps», «CAREER»…
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Award(
            String id,
            String title,
            String date,
            String startDate,
            String awardeeName,
            String awardeeCountryCode,
            String abstractText,
            String fundProgramName,
            String piFirstName,
            String piLastName) {}

    /** Награда вместе с тем, откуда и когда она получена. */
    public record Raw(Award award, String sourceId, String externalId, Provenance provenance) implements RawDocument {}
}
