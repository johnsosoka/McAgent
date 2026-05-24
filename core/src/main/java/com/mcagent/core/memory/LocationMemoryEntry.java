package com.mcagent.core.memory;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for a remembered in-game location.
 */
@Entity
@Table(name = "location_memory")
@Data
@NoArgsConstructor
public class LocationMemoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    private int x;
    private int y;
    private int z;

    @Enumerated(EnumType.STRING)
    private LocationType locationType;

    private String biome;
    private String discoveredBy;

    @CreationTimestamp
    private Instant createdAt;

    public LocationMemoryEntry(String name, String description, int x, int y, int z,
                               LocationType locationType, String biome, String discoveredBy) {
        this.name = name;
        this.description = description;
        this.x = x;
        this.y = y;
        this.z = z;
        this.locationType = locationType;
        this.biome = biome;
        this.discoveredBy = discoveredBy;
    }
}
