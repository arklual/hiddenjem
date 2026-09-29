package dev.horizon.ingestion.connector.support;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether {@code robots.txt} of a host allows us to fetch a path (BR-C3).
 *
 * <p><b>Where this applies and where it does not.</b> arXiv, OpenAlex, Crossref, USPTO and
 * GitHub are documented APIs with published terms and rate limits; {@code robots.txt} governs
 * crawlers, not clients of an API whose terms we already honour through a contactable User-Agent and
 * a per-source rate limit. The RSS connector is the one that walks arbitrary URLs an operator typed
 * into configuration — vendor blogs, newsrooms, anything. That is the case the rule is written for,
 * and the only one where this policy is consulted.
 *
 * <p><b>Absent means allowed.</b> A host with no {@code robots.txt}, or one that cannot be fetched,
 * imposes no restriction — that is what the standard says, and treating a network error as a
 * prohibition would make collection depend on the reachability of a file nobody publishes.
 * A file that <em>is</em> served and disallows the path is honoured.
 *
 * <p><b>The subset implemented.</b> {@code User-agent}, {@code Disallow}, {@code Allow}, longest-match
 * wins, {@code *} and {@code $} in patterns. Not implemented: {@code Crawl-delay} (the per-source
 * rate limit already bounds request rate) and sitemaps (we are not discovering URLs). Naming the
 * subset matters more than covering the standard: a policy that silently ignores a directive is
 * worse than one that says which directives it reads.
 */
public final class RobotsPolicy {

    private static final Logger log = LoggerFactory.getLogger(RobotsPolicy.class);

    private final Function<URI, String> fetcher;
    private final Map<String, Rules> cache = new ConcurrentHashMap<>();

    /**
     * @param fetcher returns the body of {@code robots.txt} for the given URI, or {@code null} when
     *     the host does not serve one. Injected rather than called directly so the policy is
     *     testable without a network and so the caller decides how fetching is rate-limited.
     */
    public RobotsPolicy(Function<URI, String> fetcher) {
        this.fetcher = fetcher;
    }

    /** Whether {@code target} may be fetched by an agent identifying itself as {@code userAgent}. */
    public boolean allows(URI target, String userAgent) {
        if (target.getHost() == null) {
            return true;
        }
        // Один ответ на хост за прогон: правила меняются редко, а повторный запрос robots.txt перед
        // каждой лентой удваивает нагрузку на площадку, которую мы как раз стараемся уважать.
        Rules rules = cache.computeIfAbsent(cacheKey(target), key -> load(target));
        return rules.allows(path(target), token(userAgent));
    }

    private static String cacheKey(URI target) {
        return target.getScheme() + "://" + target.getAuthority();
    }

    private static String path(URI target) {
        String path = target.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return target.getRawQuery() == null ? path : path + "?" + target.getRawQuery();
    }

    /** {@code HorizonBot/1.0 (+mailto:…)} → {@code horizonbot}: robots.txt matches on the token. */
    private static String token(String userAgent) {
        String head = userAgent == null ? "" : userAgent.trim();
        int slash = head.indexOf('/');
        int space = head.indexOf(' ');
        int end = Math.min(slash < 0 ? head.length() : slash, space < 0 ? head.length() : space);
        return head.substring(0, Math.max(0, end)).toLowerCase(Locale.ROOT);
    }

    private Rules load(URI target) {
        try {
            String body = fetcher.apply(URI.create(cacheKey(target) + "/robots.txt"));
            return body == null ? Rules.unrestricted() : Rules.parse(body);
        } catch (RuntimeException e) {
            // Недоступный robots.txt — не запрет: иначе сбор зависел бы от доступности файла,
            // которого у большинства площадок просто нет.
            log.debug("robots.txt for {} is unavailable, treating as unrestricted: {}", target, e.toString());
            return Rules.unrestricted();
        }
    }

    /** Directives of one {@code robots.txt}, grouped by user-agent token. */
    static final class Rules {

        private final Map<String, List<Directive>> groups;

        private Rules(Map<String, List<Directive>> groups) {
            this.groups = groups;
        }

        static Rules unrestricted() {
            return new Rules(Map.of());
        }

        static Rules parse(String body) {
            Map<String, List<Directive>> groups = new java.util.LinkedHashMap<>();
            List<String> agents = new ArrayList<>();
            boolean collectingAgents = false;
            for (String rawLine : body.split("\\R")) {
                String line = rawLine.split("#", 2)[0].trim();
                if (line.isEmpty()) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if ("user-agent".equals(field)) {
                    // Несколько User-agent подряд — одна группа: так написано в стандарте и так
                    // выглядят реальные файлы.
                    if (!collectingAgents) {
                        agents.clear();
                        collectingAgents = true;
                    }
                    agents.add(value.toLowerCase(Locale.ROOT));
                    groups.computeIfAbsent(value.toLowerCase(Locale.ROOT), key -> new ArrayList<>());
                } else if ("disallow".equals(field) || "allow".equals(field)) {
                    collectingAgents = false;
                    for (String agent : agents) {
                        groups.computeIfAbsent(agent, key -> new ArrayList<>())
                                .add(new Directive("allow".equals(field), value));
                    }
                }
            }
            return new Rules(groups);
        }

        boolean allows(String path, String agentToken) {
            List<Directive> directives = groups.get(agentToken);
            if (directives == null) {
                directives = groups.get("*");
            }
            if (directives == null || directives.isEmpty()) {
                return true;
            }
            // Самое длинное совпадение решает; при равной длине выигрывает Allow — так разрешает
            // спор о `Disallow: /blog` против `Allow: /blog` сам стандарт.
            Directive best = null;
            for (Directive directive : directives) {
                if (!directive.matches(path)) {
                    continue;
                }
                if (best == null
                        || directive.pattern.length() > best.pattern.length()
                        || (directive.pattern.length() == best.pattern.length() && directive.allow)) {
                    best = directive;
                }
            }
            return best == null || best.allow;
        }
    }

    /** One {@code Allow} / {@code Disallow} line. */
    private record Directive(boolean allow, String pattern) {

        boolean matches(String path) {
            if (pattern.isEmpty()) {
                // `Disallow:` без значения — разрешение всего, а не запрет.
                return allow;
            }
            return matches(path, 0, 0);
        }

        private boolean matches(String path, int pathIndex, int patternIndex) {
            while (patternIndex < pattern.length()) {
                char expected = pattern.charAt(patternIndex);
                if (expected == '$' && patternIndex == pattern.length() - 1) {
                    return pathIndex == path.length();
                }
                if (expected == '*') {
                    for (int skip = pathIndex; skip <= path.length(); skip++) {
                        if (matches(path, skip, patternIndex + 1)) {
                            return true;
                        }
                    }
                    return false;
                }
                if (pathIndex >= path.length() || path.charAt(pathIndex) != expected) {
                    return false;
                }
                pathIndex++;
                patternIndex++;
            }
            return true;
        }
    }
}
