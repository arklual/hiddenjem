"""Tokenisation, stemming, acronym mining, candidate generation and termhood."""

from __future__ import annotations

import pytest
from tests.conftest import make_document

from horizon_analytics.domain.extraction.blacklist import (
    GenericTermFilter,
    load_generic_terms,
    load_stopwords,
    parse_resource_lines,
    stem_phrase,
)
from horizon_analytics.domain.extraction.candidates import extract_candidates
from horizon_analytics.domain.extraction.normalization import (
    TermNormalizer,
    mine_acronyms,
    normalize_tokens,
    stem_english,
    stem_russian,
    stem_token,
)
from horizon_analytics.domain.extraction.termhood import c_values, compute_termhood, yake_scores
from horizon_analytics.domain.extraction.tokenizer import (
    is_year_like,
    normalize_text,
    split_sentences,
    tokenize,
)


class TestTokenizer:
    def test_splits_words_and_numbers(self) -> None:
        tokens = [token.normal for token in tokenize("Neural networks in 2024 reached 95.5%")]
        assert tokens == ["neural", "networks", "in", "2024", "reached", "95.5"]

    def test_keeps_hyphenated_words_together(self) -> None:
        assert [token.normal for token in tokenize("state-of-the-art")] == ["state-of-the-art"]

    def test_keeps_alphanumeric_model_names(self) -> None:
        assert [token.normal for token in tokenize("gpt4 and bert")] == ["gpt4", "and", "bert"]

    def test_handles_cyrillic(self) -> None:
        tokens = [token.normal for token in tokenize("Нейронные сети обучаются")]
        assert tokens == ["нейронные", "сети", "обучаются"]

    def test_offsets_point_back_into_the_text(self) -> None:
        text = "alpha beta"
        token = tokenize(text)[1]
        assert text[token.start : token.end] == "beta"

    def test_upper_surface_detection(self) -> None:
        tokens = tokenize("LLM models")
        assert tokens[0].is_upper_surface
        assert not tokens[1].is_upper_surface

    def test_single_letter_is_not_an_acronym(self) -> None:
        assert not tokenize("A test")[0].is_upper_surface

    def test_numeric_and_alphabetic_flags(self) -> None:
        tokens = tokenize("2024 year")
        assert tokens[0].is_numeric and not tokens[0].is_alphabetic
        assert tokens[1].is_alphabetic


class TestNormalizeText:
    def test_unifies_yo(self) -> None:
        assert normalize_text("ёлка Ёж") == "елка Еж"

    def test_collapses_whitespace(self) -> None:
        assert normalize_text("a  \n b\tc") == "a b c"


class TestSentenceSplitting:
    def test_splits_on_terminators(self) -> None:
        sentences = split_sentences("First one. Second one! Third one?")
        assert [item.text for item in sentences] == ["First one.", "Second one!", "Third one?"]

    def test_does_not_split_on_known_abbreviations(self) -> None:
        sentences = split_sentences("Smith et al. showed a result. Then it stopped.")
        assert len(sentences) == 2

    def test_empty_text(self) -> None:
        assert split_sentences("   ") == ()

    def test_indices_are_sequential(self) -> None:
        sentences = split_sentences("A first sentence. A second sentence.")
        assert [item.index for item in sentences] == [0, 1]


class TestIsYearLike:
    @pytest.mark.parametrize("value", ["1999", "2024", "1500", "2200"])
    def test_years(self, value: str) -> None:
        assert is_year_like(value)

    @pytest.mark.parametrize("value", ["24", "20245", "abcd", "1499"])
    def test_non_years(self, value: str) -> None:
        assert not is_year_like(value)


class TestRussianStemmer:
    @pytest.mark.parametrize(
        ("word", "expected"),
        [
            ("технология", "технолог"),
            ("технологии", "технолог"),
            ("технологий", "технолог"),
            ("нейронных", "нейрон"),
            ("квантовые", "квантов"),
            ("квантовых", "квантов"),
        ],
    )
    def test_inflections_collapse(self, word: str, expected: str) -> None:
        assert stem_russian(word) == expected

    def test_yo_is_normalised(self) -> None:
        assert stem_russian("ёмкость") == stem_russian("емкость")

    def test_short_words_are_untouched(self) -> None:
        assert stem_russian("ИИ") == "ии"

    def test_stemming_is_deliberately_not_idempotent(self) -> None:
        """Pins a known property of Snowball so it cannot regress silently.

        Re-stemming a stem strips further ("обучение" → "обучен" → "обуч"). The pipeline never
        does that — a term is stemmed once at extraction and the key is opaque afterwards — so
        the behaviour is safe, but it must stay visible: if some future code path re-normalises
        a stored key, this test is the note explaining why the results shift.
        """
        once = stem_russian("обучение")
        assert once == "обучен"
        assert stem_russian(once) == "обуч"

    def test_inflections_of_one_lemma_share_a_stem(self) -> None:
        """The property the pipeline actually relies on: variants collapse together."""
        stems = {stem_russian(word) for word in ("квантовый", "квантовые", "квантовых")}
        assert len(stems) == 1


class TestEnglishStemmer:
    @pytest.mark.parametrize(
        ("word", "expected"),
        [
            ("networks", "network"),
            ("technologies", "technology"),
            ("classes", "class"),
            ("learning", "learn"),
            ("computed", "comput"),
            ("computing", "comput"),
        ],
    )
    def test_inflections_collapse(self, word: str, expected: str) -> None:
        assert stem_english(word) == expected

    def test_keeps_double_s(self) -> None:
        assert stem_english("class") == "class"

    def test_keeps_latin_plurals(self) -> None:
        assert stem_english("corpus") == "corpus"

    def test_short_words_untouched(self) -> None:
        assert stem_english("gpu") == "gpu"

    def test_singular_and_plural_agree(self) -> None:
        assert stem_english("embedding") == stem_english("embeddings")


class TestStemDispatch:
    def test_cyrillic_goes_to_the_russian_stemmer(self) -> None:
        assert stem_token("сети") == stem_russian("сети")

    def test_latin_goes_to_the_english_stemmer(self) -> None:
        assert stem_token("networks") == stem_english("networks")

    def test_normalize_tokens_joins_stems(self) -> None:
        assert normalize_tokens(["neural", "networks"]) == "neural network"


class TestAcronymMining:
    def test_mines_long_form_acronym(self) -> None:
        dictionary = mine_acronyms(
            ["We study large language models (LLM) in production.", "Another large language model."]
        )
        entry = dictionary.resolve("llm")
        assert entry is not None
        assert "languag" in entry.long_form_key or "language" in entry.long_form_key

    def test_canonical_key_folds_acronym_onto_long_form(self) -> None:
        dictionary = mine_acronyms(["Retrieval augmented generation (RAG) is useful."])
        assert dictionary.canonical_key("rag") != "rag"

    def test_unknown_key_is_returned_unchanged(self) -> None:
        assert mine_acronyms([]).canonical_key("xyz") == "xyz"

    def test_rejects_acronyms_that_do_not_match_the_initials(self) -> None:
        dictionary = mine_acronyms(["Completely unrelated words (ZZZ) here."])
        assert dictionary.resolve("zzz") is None

    def test_is_independent_of_input_order(self) -> None:
        texts = [
            "Large language models (LLM) matter.",
            "Retrieval augmented generation (RAG) matters.",
        ]
        forward = mine_acronyms(texts)
        backward = mine_acronyms(list(reversed(texts)))
        assert sorted(forward.entries) == sorted(backward.entries)

    def test_hyphen_joins_components_of_the_long_form(self) -> None:
        """Дефис соединяет составляющие, и аббревиатура строится по их начальным буквам.

        Пока дефисное слово считалось одним, `sodium-ion battery` читалось как «s, b» и не
        находило `SIB`. На эталонном корпусе так терялись четыре аббревиатуры из восьми
        несливающихся: post-quantum cryptography, sodium-ion battery, solid-state battery,
        zero-knowledge rollup.
        """
        dictionary = mine_acronyms(["We evaluate sodium-ion battery (SIB) chemistries."])
        entry = dictionary.resolve("sib")
        assert entry is not None
        assert entry.long_form == "sodium-ion battery"

    def test_the_long_form_stops_where_the_acronym_does(self) -> None:
        """Полная форма — кратчайший суффикс, а не всё окно захвата.

        Прежняя эвристика брала «нужно букв плюс два слова» и отдавала в словарь весь захват:
        `AA → interest in account abstraction`. Такая полная форма бесполезна — извлекатель
        никогда не породит кандидата с приставкой «interest in», — то есть слияние происходило
        и не работало.
        """
        dictionary = mine_acronyms(["There is growing interest in account abstraction (AA) today."])
        entry = dictionary.resolve("aa")
        assert entry is not None
        assert entry.long_form == "account abstraction"

    def test_letters_come_from_word_starts_and_not_from_inside_neighbours(self) -> None:
        """Буква берётся с начала слова.

        Правило, разрешавшее взять букву изнутри любого слова, читало
        `open-source implementation of sparse autoencoder` как `SAE`: «s» из source, «a» из
        implementation, «e» из sparse. Аббревиатура склеивалась с окружающим текстом.
        """
        # `sodium battery` против `ST`: «s» — начало первого слова, «t» нашлась бы внутри
        # `battery`. Пример выбран так, чтобы прежнее правило слило, а нынешнее отказало: на
        # `sparse autoencoder` обе версии отказывают, и тест ничего бы не проверял.
        dictionary = mine_acronyms(["We test sodium battery (ST) cells."])
        assert dictionary.resolve("st") is None

    def test_a_trailing_s_may_be_plural_or_significant(self) -> None:
        """Обе трактовки конечного «s» проверяются, потому что обе встречаются."""
        plural = mine_acronyms(["Large language models (LLMs) are everywhere."])
        significant = mine_acronyms(["We use neural architecture search (NAS) here."])
        # Ключ словаря — нормализованная форма: `LLMs` стеммируется в `llm`.
        entry = plural.resolve("llm")
        assert entry is not None
        assert entry.long_form == "large language models"
        found = significant.resolve("nas")
        assert found is not None
        assert found.long_form == "neural architecture search"

    def test_normalizer_key_applies_the_dictionary(self) -> None:
        normalizer = TermNormalizer.from_texts(["Large language models (LLM) are trained on text."])
        assert normalizer.key(tokenize("LLM")) == normalizer.key(tokenize("large language model"))

    def test_display_preserves_acronym_casing(self) -> None:
        normalizer = TermNormalizer.from_texts([])
        assert normalizer.display(tokenize("LLM inference")) == "LLM inference"


class TestCandidateExtraction:
    def _extract(self, texts: list[str], **kwargs: int):
        documents = [
            make_document(f"doc-{index}", year=2024, title="T", abstract=text)
            for index, text in enumerate(texts)
        ]
        return extract_candidates(
            documents,
            normalizer=TermNormalizer.from_texts(texts),
            stopwords=frozenset({"of", "the", "and", "in"}),
            **kwargs,
        )

    def test_generates_ngrams_up_to_the_limit(self) -> None:
        result = self._extract(["alpha beta gamma delta epsilon"], ngram_min=1, ngram_max=4)
        keys = {candidate.key for candidate in result.candidates}
        assert "alpha" in keys
        assert "alpha beta gamma delta" in keys
        assert "alpha beta gamma delta epsilon" not in keys

    def test_stopwords_are_hard_boundaries(self) -> None:
        result = self._extract(["latency in networks"], ngram_min=1, ngram_max=3)
        keys = {candidate.key for candidate in result.candidates}
        assert "latency" in keys
        assert "network" in keys
        assert not any("in" in key.split(" ") for key in keys)

    def test_a_bridging_word_joins_a_name_but_never_ends_one(self) -> None:
        # "of" is the one class of exception: it sits inside real names ("internet of things",
        # "bill of materials") that a hard boundary would both lose and replace with a meaningless
        # prefix. It is still forbidden at either edge, where it would only ever be a fragment.
        result = self._extract(["speed of light"], ngram_min=1, ngram_max=3)
        keys = {candidate.key for candidate in result.candidates}
        assert "speed of light" in keys
        assert "speed of" not in keys
        assert "of light" not in keys

    def test_candidates_are_sorted_by_key(self) -> None:
        result = self._extract(["zeta alpha", "alpha zeta"])
        keys = [candidate.key for candidate in result.candidates]
        assert keys == sorted(keys)

    def test_postings_count_occurrences_per_document(self) -> None:
        result = self._extract(["alpha alpha", "alpha"])
        candidate = next(item for item in result.candidates if item.key == "alpha")
        assert candidate.document_frequency == 2
        assert candidate.term_frequency == 3

    def test_document_order_does_not_matter(self) -> None:
        forward = self._extract(["alpha beta", "gamma delta"])
        backward = self._extract(["gamma delta", "alpha beta"])
        assert [item.key for item in forward.candidates] == [
            item.key for item in backward.candidates
        ]

    def test_token_statistics_are_collected(self) -> None:
        result = self._extract(["alpha beta. alpha gamma."])
        stats = result.token_statistics.get("alpha")
        assert stats is not None
        assert stats.frequency == 2
        assert stats.sentence_frequency == 2

    def test_median_sentence_index(self) -> None:
        result = self._extract(["alpha one. alpha two. alpha three."])
        stats = result.token_statistics.get("alpha")
        assert stats is not None
        assert stats.median_sentence_index == 1.0

    def test_acronym_flag_for_uppercase_unigrams(self) -> None:
        result = self._extract(["LLM LLM LLM"])
        candidate = next(item for item in result.candidates if item.key == "llm")
        assert candidate.is_acronym


class TestBridgingWords:
    """Function words a technology name may contain internally but never end with."""

    def _keys(self, text: str) -> set[str]:
        documents = [make_document("d1", year=2024, title="T", abstract=text)]
        extraction = extract_candidates(
            documents,
            normalizer=TermNormalizer.from_texts([text]),
            stopwords=frozenset({"of", "the", "and", "in", "a", "as"}),
        )
        return {candidate.key for candidate in extraction.candidates}

    def test_recovers_a_name_that_contains_a_function_word(self):
        # Treating every stopword as a hard boundary loses a whole family of real names:
        # "internet of things", "quality of service", "proof of stake", "denial of service".
        keys = self._keys("The software bill of materials is attached.")

        assert any("bill of material" in key for key in keys)

    def test_does_not_leave_the_fragment_before_the_function_word(self):
        # The old boundary produced "software bill" — meaningless, still term-shaped, and it
        # competed for a place in the report. The defect hid real topics *and* manufactured fake
        # ones, so recovering the full name is only half the fix.
        keys = self._keys("The software bill of materials is attached.")

        assert "softwar bill" not in keys

    def test_never_emits_a_candidate_ending_in_a_function_word(self):
        keys = self._keys("The software bill of materials is attached.")

        assert not any(key.split(" ")[-1] in {"of", "as", "a"} for key in keys)

    def test_never_emits_a_candidate_starting_with_a_function_word(self):
        keys = self._keys("The software bill of materials is attached.")

        assert not any(key.split(" ")[0] in {"of", "as", "a"} for key in keys)

    def test_a_non_bridging_stopword_is_still_a_hard_boundary(self):
        # Only the closed bridging set is allowed through; everything else must still cut, or the
        # extractor would start emitting clauses instead of names.
        keys = self._keys("Latency in networks and throughput in storage.")

        assert not any(" in " in f" {key} " for key in keys)
        assert "latenc network" not in keys


class TestTermhood:
    def _candidates(self, text: str):
        documents = [make_document("d1", year=2024, title="T", abstract=text)]
        return extract_candidates(
            documents,
            normalizer=TermNormalizer.from_texts([text]),
            stopwords=frozenset({"the", "of"}),
        )

    def test_c_value_prefers_longer_units(self) -> None:
        extraction = self._candidates("graph neural network. graph neural network.")
        values = c_values(extraction.candidates)
        assert values["graph neural network"] > values["graph"]

    def test_c_value_penalises_nested_terms(self) -> None:
        extraction = self._candidates("alpha beta. alpha beta. alpha beta.")
        values = c_values(extraction.candidates)
        # "alpha" only ever occurs inside "alpha beta", so its C-value is fully discounted.
        assert values["alpha"] <= 0.0
        assert values["alpha beta"] > 0.0

    def test_yake_scores_are_finite_and_positive(self) -> None:
        extraction = self._candidates("alpha beta gamma. alpha delta.")
        scores = yake_scores(extraction.candidates, extraction.token_statistics)
        assert all(value >= 0.0 for value in scores.values())

    def test_termhood_is_bounded(self) -> None:
        extraction = self._candidates("alpha beta gamma. alpha delta epsilon.")
        scores = compute_termhood(extraction.candidates, extraction.token_statistics)
        assert all(0.0 <= item.termhood <= 1.0 for item in scores.values())

    def test_termhood_covers_every_candidate(self) -> None:
        extraction = self._candidates("alpha beta. gamma delta.")
        scores = compute_termhood(extraction.candidates, extraction.token_statistics)
        assert set(scores) == {item.key for item in extraction.candidates}


class TestBlacklist:
    def test_parse_drops_comments_and_blanks(self) -> None:
        assert parse_resource_lines("# c\n\nalpha\n  beta  \n") == frozenset({"alpha", "beta"})

    def test_stopwords_resource_loads(self) -> None:
        stopwords = load_stopwords()
        assert "the" in stopwords
        assert "и" in stopwords

    def test_generic_terms_resource_is_stemmed_at_load(self) -> None:
        terms = load_generic_terms()
        assert stem_phrase("magnitude") in terms
        assert stem_phrase("new approach") in terms

    def test_stem_phrase_matches_candidate_keys(self) -> None:
        assert stem_phrase("Neural Networks") == normalize_tokens(["neural", "networks"])

    def test_rejects_generic_terms(self) -> None:
        term_filter = GenericTermFilter.from_terms(["new approach"])
        assert (
            term_filter.rejects(stem_phrase("new approach"), ["new", "approach"]) == "generic_term"
        )

    def test_accepts_a_real_term(self) -> None:
        term_filter = GenericTermFilter.from_terms(["new approach"])
        assert term_filter.rejects("quantum annealing", ["quantum", "annealing"]) is None

    @pytest.mark.parametrize(
        ("key", "tokens", "reason"),
        [
            ("", [], "empty"),
            ("ab", ["ab"], "too_short"),
            ("x" * 61, ["x" * 61], "too_long"),
            ("2024", ["2024"], "numeric_only"),
            ("123 456", ["123", "456"], "numeric_only"),
        ],
    )
    def test_rejection_reasons(self, key: str, tokens: list[str], reason: str) -> None:
        term_filter = GenericTermFilter.from_terms([])
        assert term_filter.rejects(key, tokens) == reason

    def test_default_filter_uses_the_packaged_resource(self) -> None:
        term_filter = GenericTermFilter.default()
        assert term_filter.rejects(stem_phrase("state of the art"), ["state"]) is not None

    def test_umbrella_fintech_labels_are_not_technology_names(self) -> None:
        term_filter = GenericTermFilter.default()
        for name in ("digital financial ecosystem", "financial infrastructure", "AI solutions"):
            assert term_filter.rejects(stem_phrase(name), name.split()) == "generic_term"
        concrete = "distributed ledger storage"
        assert term_filter.rejects(stem_phrase(concrete), concrete.split()) is None


def test_untranslated_chinese_gives_no_tokens_but_keeps_latin_names() -> None:
    """Непереведённый китайский документ не даёт кандидатов-абзацев (разбор 93).

    Без пробелов между словами регулярное выражение слова склеивало бы предложение в один токен.
    """
    from horizon_analytics.domain.extraction.tokenizer import tokenize

    normals = [token.normal for token in tokenize("基于联邦学习的边缘计算 FedAvg 与 5G 部署")]

    assert normals == ["fedavg", "5g"]
