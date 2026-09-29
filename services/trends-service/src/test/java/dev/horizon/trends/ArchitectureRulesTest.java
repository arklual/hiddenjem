package dev.horizon.trends;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures;

/**
 * Границы гексагональной архитектуры, проверяемые, а не декларируемые.
 *
 * <p>README утверждал в двух местах, что чистота слоёв «проверяется ArchUnit». Зависимость была
 * объявлена в `services/pom.xml`, а тестов не существовало ни одного: утверждение держалось на
 * дисциплине автора. Технический читатель проверяет такое одной командой, и несовпадение стоит
 * доверия ко всему остальному тексту — та же асимметрия, которую продукт не терпит в оценке
 * собственного качества.
 *
 * <p>Правила ниже — не общие пожелания, а ровно те границы, на которых держится этот код:
 * доменный слой обязан оставаться обычным Java-кодом, чтобы его можно было считать без контейнера,
 * а прикладной слой обязан ходить в мир только через порты, чтобы адаптер можно было заменить, не
 * трогая сценарии использования.
 *
 * <p>Правила «из конфигурации видны только настройки» здесь нет, хотя в службах удостоверения и
 * сбора оно есть. Причина не в том, что здесь чище: правило было написано, прогнано и нашло
 * четырнадцать связей — `config.FeatureGate` и `config.FeatureFlags` приходят в шесть контроллеров,
 * в `ReportViewMapper` и даже в `ExportReportUseCase`. Это не настройки, а сотрудник времени
 * выполнения, поселившийся в компоновочном корне, и вопрос о его месте адресован автору шлюза
 * функций, а не решается тестом. Правило, подогнанное под существующий код, перестало бы быть
 * правилом; правило, роняющее чужую незаконченную работу, — тоже не улучшение. Оно ждёт переезда
 * шлюза в прикладной слой.
 */
class ArchitectureRulesTest {

    private static final String ROOT = "dev.horizon.trends";

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionCode() {
        // Только боевой код: тесты законно знают обо всём сразу, и включать их значило бы
        // проверять правила, которых к ним никто не предъявлял.
        classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("слои ходят только вниз: адаптер → приложение → домен")
    void thelayersOnlyPointInwards() {
        Architectures.layeredArchitecture()
                .consideringOnlyDependenciesInAnyPackage(ROOT + "..")
                .layer("Домен")
                .definedBy(ROOT + ".domain..")
                .layer("Приложение")
                .definedBy(ROOT + ".application..")
                .layer("Адаптеры")
                .definedBy(ROOT + ".adapter..")
                .layer("Конфигурация")
                .definedBy(ROOT + ".config..")
                .whereLayer("Адаптеры")
                .mayOnlyBeAccessedByLayers("Конфигурация")
                .whereLayer("Приложение")
                .mayOnlyBeAccessedByLayers("Адаптеры", "Конфигурация")
                .check(classes);
    }

    @Test
    @DisplayName("домен не знает про Spring")
    void thedomainDoesNotKnowAboutSpring() {
        // Домен, знающий про Spring, нельзя посчитать без контейнера — и он перестаёт быть местом,
        // где инварианты проверяются в конструкторе, становясь местом, где они проверяются, если
        // повезёт с порядком инициализации бинов.
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "jakarta.servlet..")
                .check(classes);
    }

    @Test
    @DisplayName("домен не знает про базу, HTTP и очередь")
    void thedomainDoesNotKnowAboutInfrastructure() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.sql..", "javax.sql..", "org.apache.kafka..", "com.fasterxml.jackson..")
                .check(classes);
    }

    @Test
    @DisplayName("прикладной слой ходит в мир только через порты")
    void theapplicationLayerReachesTheWorldThroughPortsOnly() {
        // Сценарий использования, дотянувшийся до адаптера напрямую, нельзя заменить другим
        // адаптером — а вся возможность подменить источник, хранилище или движок держится именно
        // на этом.
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".application..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + ".adapter..")
                .check(classes);
    }

    @Test
    @DisplayName("зависимости не внедряются в поля")
    void dependenciesAreNotInjectedIntoFields() {
        // Внедрение в поле прячет зависимость от компилятора и от того, кто читает конструктор:
        // класс с пятью полями выглядит так же, как с одним, а собрать его в тесте без контейнера
        // нельзя вовсе.
        //
        // Первая версия правила смотрела на `noClasses().should().beAnnotatedWith(@Autowired)` —
        // то есть на аннотацию, повешенную на класс, тогда как внедряют в поле. Правило проходило
        // и прошло бы при любом внедрении: оно проверяло то, чего никто никогда не пишет.
        noFields()
                .should()
                .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .check(classes);
    }
}
