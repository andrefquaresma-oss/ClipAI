package com.clipai.infrastructure.persistence.jpa;

import com.clipai.application.matchcontext.MatchContextRepository;
import com.clipai.application.matchcontext.MatchScoreTransition;
import com.clipai.application.matchcontext.MatchStructureMarker;
import com.clipai.application.matchcontext.MatchStructureMarkerType;
import com.clipai.application.matchcontext.ScoreTransitionSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class MatchContextRepositoryAdapter implements MatchContextRepository {
    private final JdbcTemplate jdbc;

    public MatchContextRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MatchStructureMarker> findStructureMarkers(UUID mediaAssetId) {
        return jdbc.query("""
                SELECT media_asset_id, marker_type, timestamp_ms, updated_at
                FROM match_structure_markers
                WHERE media_asset_id = ?
                ORDER BY timestamp_ms, marker_type
                """, (row, index) -> new MatchStructureMarker(
                row.getObject("media_asset_id", UUID.class),
                MatchStructureMarkerType.valueOf(row.getString("marker_type")),
                row.getLong("timestamp_ms"), row.getTimestamp("updated_at").toInstant()), mediaAssetId);
    }

    @Override
    @Transactional
    public MatchStructureMarker saveStructureMarker(MatchStructureMarker marker) {
        jdbc.update("""
                INSERT INTO match_structure_markers (media_asset_id, marker_type, timestamp_ms, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (media_asset_id, marker_type) DO UPDATE SET
                    timestamp_ms = EXCLUDED.timestamp_ms, updated_at = EXCLUDED.updated_at
                """, marker.mediaAssetId(), marker.type().name(), marker.timestampMs(),
                Timestamp.from(marker.updatedAt()));
        return marker;
    }

    @Override
    @Transactional
    public void deleteStructureMarker(UUID mediaAssetId, MatchStructureMarkerType type) {
        jdbc.update("DELETE FROM match_structure_markers WHERE media_asset_id = ? AND marker_type = ?",
                mediaAssetId, type.name());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MatchScoreTransition> findScoreTransitions(UUID mediaAssetId) {
        return jdbc.query("""
                SELECT id, media_asset_id, timestamp_ms, home_score, away_score, source, confidence,
                       created_at, updated_at
                FROM match_score_transitions
                WHERE media_asset_id = ?
                ORDER BY timestamp_ms, created_at
                """, (row, index) -> new MatchScoreTransition(
                row.getObject("id", UUID.class),
                row.getObject("media_asset_id", UUID.class),
                row.getLong("timestamp_ms"), row.getInt("home_score"), row.getInt("away_score"),
                ScoreTransitionSource.valueOf(row.getString("source")),
                (Double) row.getObject("confidence"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant()), mediaAssetId);
    }

    @Override
    @Transactional
    public MatchScoreTransition saveScoreTransition(MatchScoreTransition transition) {
        jdbc.update("""
                INSERT INTO match_score_transitions
                    (id, media_asset_id, timestamp_ms, home_score, away_score, source, confidence,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    timestamp_ms = EXCLUDED.timestamp_ms,
                    home_score = EXCLUDED.home_score,
                    away_score = EXCLUDED.away_score,
                    source = EXCLUDED.source,
                    confidence = EXCLUDED.confidence,
                    updated_at = EXCLUDED.updated_at
                """, transition.id(), transition.mediaAssetId(), transition.timestampMs(),
                transition.homeScore(), transition.awayScore(), transition.source().name(),
                transition.confidence(), Timestamp.from(transition.createdAt()),
                Timestamp.from(transition.updatedAt()));
        return transition;
    }

    @Override
    @Transactional
    public void deleteScoreTransition(UUID mediaAssetId, UUID transitionId) {
        jdbc.update("DELETE FROM match_score_transitions WHERE media_asset_id = ? AND id = ?",
                mediaAssetId, transitionId);
    }
}
