package com.mcagent.core.memory;

import com.mcagent.core.service.BotOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(LocationMemoryService.class)
class LocationMemoryServiceTest {

    @Autowired
    private LocationMemoryService service;

    @Autowired
    private LocationRepository repository;

    @Test
    void shouldSaveLocation() {
        LocationMemoryEntry saved = service.save("Base", "Main base", 100, 64, 200, LocationType.BASE, "plains", "Alice");

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("Base");
        assertThat(saved.getX()).isEqualTo(100);
    }

    @Test
    void shouldFindByNameCaseInsensitive() {
        service.save("Village", "Nearby village", 50, 70, 50, LocationType.VILLAGE, "savanna", "Bob");

        Optional<LocationMemoryEntry> found = service.findByName("village");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Village");
    }

    @Test
    void shouldSearchByTextInNameOrDescription() {
        service.save("Gold Cave", "Rich in ore", 10, 30, 10, LocationType.CAVE, "mountains", "Alice");
        service.save("Iron Pit", "Deep iron deposits", 20, 40, 20, LocationType.RESOURCE_SITE, "hills", "Bob");

        List<LocationMemoryEntry> results = service.search("cave");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getName()).isEqualTo("Gold Cave");
    }

    @Test
    void shouldFindNearbyWithinRadius() {
        service.save("Home", "Starting point", 0, 64, 0, LocationType.BASE, "plains", "Alice");
        service.save("FarAway", "Distant outpost", 1000, 64, 0, LocationType.BASE, "desert", "Bob");

        BotOperations.Location origin = new BotOperations.Location(0, 64, 0);
        List<LocationMemoryEntry> nearby = service.findNearby(origin, 500);

        assertThat(nearby).hasSize(1);
        assertThat(nearby.get(0).getName()).isEqualTo("Home");
    }

    @Test
    void shouldListAllLocations() {
        service.save("A", "Desc A", 1, 1, 1, LocationType.LANDMARK, "forest", "Alice");
        service.save("B", "Desc B", 2, 2, 2, LocationType.LANDMARK, "forest", "Bob");

        List<LocationMemoryEntry> all = service.listAll();
        assertThat(all).hasSize(2);
    }

    @Test
    void shouldDeleteLocation() {
        LocationMemoryEntry entry = service.save("Temp", "Temporary", 0, 0, 0, LocationType.OTHER, "ocean", "Alice");
        UUID id = entry.getId();

        service.delete(id);

        assertThat(repository.findById(id)).isEmpty();
    }
}
