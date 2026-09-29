"""Адаптеры к сервису вывода моделей (ADR-0017): семантический судья и русификация."""

from horizon_analytics.adapters.nlp.http import HttpNlpClient, HttpTechnologyJudge

__all__ = ["HttpNlpClient", "HttpTechnologyJudge"]
