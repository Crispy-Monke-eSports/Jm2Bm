package se.jesper.jm2bm.sync;

public record DesiredMarker(
        String id,
        String label,
        double x,
        double y,
        double z,
        String iconAssetName
) {
}
