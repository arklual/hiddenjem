package dev.horizon.trends.adapter.web;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Корень контекста для веб-среза.
 *
 * <p>Класс приложения указывать нельзя: он лежит в пакете {@code config} рядом с конфигурациями
 * персистентности и обмена сообщениями, а фильтр среза отсеивает компоненты, но не конфигурации —
 * контекст падал бы на отсутствующем {@code EntityManagerFactory}. Собственный корень оставляет в
 * контексте ровно веб-контур, который и является предметом проверки.
 *
 * <p>Сканирования здесь нет намеренно: объявленный вручную {@code @ComponentScan} фильтром среза не
 * ограничивается и втягивает все контроллеры пакета вместе с их зависимостями. Проверяемые
 * контроллеры перечислены явно в базовом классе — так в контексте оказывается ровно то, о чём
 * говорят утверждения.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class WebSliceApplication {}
