package com.mcagent.core.memory;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for a free-text note attached to a player or location.
 */
@Entity
@Table(name = "player_notes")
@Data
@NoArgsConstructor
public class PlayerNote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String playerName;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(length = 500)
    private String tags;

    @CreationTimestamp
    private Instant createdAt;

    public PlayerNote(String playerName, String content, String tags) {
        this.playerName = playerName;
        this.content = content;
        this.tags = tags;
    }
}
