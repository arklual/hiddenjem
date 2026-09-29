package dev.horizon.ingestion;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
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
 * Границы слоёв службы сбора — проверяемые, а не подразумеваемые.
 *
 * <p>Здесь эти границы стоят дороже, чем в соседних службах: коннекторов шесть, и final-требования
 * почти наверняка принесут седьмой. Ценность правил в том, что новый коннектор нельзя будет добавить
 * «быстро» — дотянувшись из адаптера прямо до репозитория или протащив разбор ответа в домен.
 * Именно так расширяемые точки перестают быть расширяемыми: не одним решением, а десятком мелких
 * срезаний угла, каждое из которых по отдельности выглядит безобидно.
 *
 * <p>Раскладка своя, и правила написаны по факту. Порты лежат в `domain.port`, а не в прикладном
 * слое, как в службе удостоверения, — обе раскладки законны, но правило, перенесённое между ними
 * не глядя, проверяло бы несуществующую границу. Адаптеров четыре — `web`, `persistence`,
 * `messaging`, `connector`, — и они образуют один слой.
 *
 * <p>Про зависимость от `config` — см. то же правило в службе удостоверения: из компоновочного
 * корня видны только записи `*Properties`. В этой службе связь заметнее (`ConnectorsProperties`
 * читают восемь классов коннекторов), и тем важнее, чтобы она была именно такой, а не разрасталась
 * до `ConnectorConfiguration`.
 */
class ArchitectureRulesTest {

    private static final String ROOT = "dev.horizon.ingestion";

    /**
     * Полное имя записи настроек — включая вложенные группы.
     *
     * <p>Первая версия правила смотрела на простое имя и требовала окончания «Properties». Она
     * нашла двенадцать нарушений, и ни одно не было изъяном: настройки группируют вложенными
     * записями, и `IamProperties$Cookie` — те же настройки, только под своим заголовком. Проверка
     * по полному имени принимает такую группировку и по-прежнему не пропускает проводку:
     * `IamBeansConfiguration` и `ConnectorConfiguration` под неё не подходят.
     */
    private static final String SETTINGS = ".*Properties(\\$.*)?";

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionCode() {
        classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("слои ходят только вниз: адаптер → приложение → домен")
    void theLayersOnlyPointInwards() {
        Architectures.layeredArchitecture()
                .consideringOnlyDependenciesInAnyPackage(ROOT + "..")
                .layer("Домен")
                .definedBy(ROOT + ".domain..")
                .layer("Приложение")
                .definedBy(ROOT + ".application..")
                .layer("Адаптеры")
                .definedBy(ROOT + ".web..", ROOT + ".persistence..", ROOT + ".messaging..", ROOT + ".connector..")
                .layer("Конфигурация")
                .definedBy(ROOT + ".config..")
                .whereLayer("Адаптеры")
                .mayOnlyBeAccessedByLayers("Конфигурация")
                .whereLayer("Приложение")
                .mayOnlyBeAccessedByLayers("Адаптеры", "Конфигурация")
                .check(classes);
    }

    @Test
    @DisplayName("домен не знает про Spring, хранилище, очередь и разбор ответов")
    void theDomainStaysPlainJava() {
        // Соблазн здесь конкретный: у документа есть поля, у ответа arXiv — те же поля, и склеить
        // их одной аннотацией Jackson быстрее, чем писать преобразование в коннекторе. После этого
        // формат внешнего источника становится доменной моделью, и его смена ломает всё сразу.
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "jakarta.servlet..",
                        "com.fasterxml.jackson..",
                        "org.apache.kafka..",
                        "java.sql..",
                        "javax.sql..")
                .check(classes);
    }

    @Test
    @DisplayName("прикладной слой ходит в мир только через порты")
    void theApplicationLayerReachesTheWorldThroughPortsOnly() {
        // Сценарий сбора, дотянувшийся до конкретного коннектора, перестаёт быть сценарием сбора и
        // становится сценарием сбора из arXiv. Подмена источника фикстурой — тоже держится на этом.
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        ROOT + ".web..", ROOT + ".persistence..", ROOT + ".messaging..", ROOT + ".connector..")
                .check(classes);
    }

    @Test
    @DisplayName("из конфигурации видны только настройки, но не проводка")
    void onlyPropertiesLeakOutOfTheCompositionRoot() {
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".config..")
                .should()
                .dependOnClassesThat(resideInAPackage(ROOT + ".config..").and(not(nameMatching(SETTINGS))))
                .check(classes);
    }

    @Test
    @DisplayName("зависимости не внедряются в поля")
    void dependenciesAreNotInjectedIntoFields() {
        noFields()
                .should()
                .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .check(classes);
    }
}
