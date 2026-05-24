package com.mcagent.core.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PlayerNoteRepository extends JpaRepository<PlayerNote, UUID> {

    List<PlayerNote> findByPlayerNameIgnoreCaseOrderByCreatedAtDesc(String playerName);

    @Query("SELECT n FROM PlayerNote n WHERE LOWER(n.playerName) = LOWER(:playerName) AND " +
           "(LOWER(n.content) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(n.tags) LIKE LOWER(CONCAT('%', :query, '%')))" +
           "ORDER BY n.createdAt DESC")
    List<PlayerNote> searchByPlayerAndText(@Param("playerName") String playerName,
                                          @Param("query") String query);
}
