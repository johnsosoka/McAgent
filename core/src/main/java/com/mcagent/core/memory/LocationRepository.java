package com.mcagent.core.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LocationRepository extends JpaRepository<LocationMemoryEntry, UUID> {

    Optional<LocationMemoryEntry> findByNameIgnoreCase(String name);

    List<LocationMemoryEntry> findByDiscoveredByIgnoreCase(String playerName);

    @Query("SELECT l FROM LocationMemoryEntry l WHERE " +
           "LOWER(l.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(l.description) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<LocationMemoryEntry> searchByText(@Param("query") String query);

    @Query("SELECT l FROM LocationMemoryEntry l WHERE " +
           "SQRT(POWER(l.x - :x, 2) + POWER(l.y - :y, 2) + POWER(l.z - :z, 2)) <= :radius")
    List<LocationMemoryEntry> findWithinRadius(@Param("x") int x,
                                                @Param("y") int y,
                                                @Param("z") int z,
                                                @Param("radius") double radius);
}
