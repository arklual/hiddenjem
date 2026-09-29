"""Глубокое исследование: агентный цикл по открытым источникам проекта (разбор 102)."""

from __future__ import annotations

import httpx

from horizon_nlp.config import settings
from horizon_nlp.research.agent import Budget, DeepResearch, ResearchDocument, ResearchResult
from horizon_nlp.research.sources import HOST_INTERVALS, Window
from horizon_nlp.research.web import HostPacer, WebAccess

__all__ = ["Budget", "ResearchDocument", "ResearchResult", "Window", "build_research"]


def build_research(model: object, model_name: str) -> DeepResearch:
    """Собрать исследователя: свой клиент, своя вежливость и кэш robots.txt на каждый прогон."""
    agent = f"{settings.research_user_agent} (+mailto:{settings.research_contact_email})"
    web = WebAccess(
        httpx.Client(timeout=20.0, limits=httpx.Limits(max_connections=16)),
        HostPacer(default_interval=2.0, intervals=dict(HOST_INTERVALS)),
        agent,
    )
    return DeepResearch(
        model,  # type: ignore[arg-type]  # GenerativeClient удовлетворяет протоколу ToolModel
        web,
        model_name=model_name,
        contact_email=settings.research_contact_email,
        github_token=settings.research_github_token,
        reasoning_effort=settings.research_reasoning_effort,
    )
