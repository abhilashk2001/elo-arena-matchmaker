package com.eloarena.matchmaking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Holds the currently active matching strategy and allows switching it at runtime, which is
 * what powers the dashboard's LOCKING/NAIVE toggle and the race-reproduction load tests.
 */
@Component
public class StrategySelector {

    private final Map<String, MatchingStrategy> strategiesByName;
    private volatile String currentName;

    public StrategySelector(List<MatchingStrategy> strategies,
                            @Value("${eloarena.matcher.strategy:locking}") String initial) {
        this.strategiesByName = strategies.stream()
                .collect(Collectors.toMap(MatchingStrategy::name, Function.identity()));
        select(initial);
    }

    public MatchingStrategy current() {
        return strategiesByName.get(currentName);
    }

    public String currentName() {
        return currentName;
    }

    public Set<String> available() {
        return strategiesByName.keySet();
    }

    public void select(String name) {
        if (!strategiesByName.containsKey(name)) {
            throw new IllegalArgumentException(
                    "Unknown matcher strategy '" + name + "'. Available: " + strategiesByName.keySet());
        }
        this.currentName = name;
    }
}
