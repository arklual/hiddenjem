-- alphaXiv MCP discovers relevant arXiv preprints and returns their extracted original text.
-- The runtime connector remains off until explicitly enabled and supplied an API key.
INSERT INTO sources (id, display_name, source_class, enabled, base_url,
                     rate_limit_per_minute, requires_api_key, config, authority_weight)
VALUES
    ('alphaxiv', 'alphaXiv', 'PREPRINT', true,
     'https://api.alphaxiv.org/mcp/v1', 30, true,
     '{"transport":"streamable-http","tool":"discover_papers","maxPapers":15}'::jsonb,
     0.600)
ON CONFLICT (id) DO NOTHING;
