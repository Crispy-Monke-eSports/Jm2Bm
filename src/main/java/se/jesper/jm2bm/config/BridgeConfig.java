package se.jesper.jm2bm.config;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record BridgeConfig(boolean syncOnStartup, String defaultIcon, List<Mapping> mappings) {
    public BridgeConfig {
        mappings = List.copyOf(mappings);
    }

    public Set<String> markerSetIds() {
        return mappings.stream().map(Mapping::blueMapMarkerSet).collect(Collectors.toUnmodifiableSet());
    }

    public record Mapping(UUID journeyMapGroupUuid, String blueMapMarkerSet, String icon) {
    }
}
