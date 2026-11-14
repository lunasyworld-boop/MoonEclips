package ru.mooneclips.moonrtp.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Contract for integration with a future private/claim plugin.
 * The provider must return safe arrival positions and must not bypass protection.
 */
public interface PrivateTeleportProvider {
    List<PrivateTarget> findTargetsNear(Location origin, int radius, Player viewer);

    Optional<Location> findSafeArrival(UUID privateId, Player viewer);

    record PrivateTarget(UUID privateId, String displayName, Location center, double distance) {}
}