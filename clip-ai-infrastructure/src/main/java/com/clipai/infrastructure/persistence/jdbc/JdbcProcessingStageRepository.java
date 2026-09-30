package com.clipai.infrastructure.persistence.jdbc;

import com.clipai.application.media.ProcessingStage;
import com.clipai.application.media.ProcessingStageRepository;
import com.clipai.application.media.ProcessingStageRun;
import com.clipai.application.media.ProcessingStageStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcProcessingStageRepository implements ProcessingStageRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcProcessingStageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ProcessingStageRun> findByMediaAssetId(UUID mediaAssetId) {
        return jdbcTemplate.query("""
                SELECT media_asset_id, stage, status, progress, message, updated_at
                FROM media_processing_stage_runs
                WHERE media_asset_id = ?
                ORDER BY stage
                """, (rs, rowNum) -> new ProcessingStageRun(
                rs.getObject("media_asset_id", UUID.class),
                ProcessingStage.valueOf(rs.getString("stage")),
                ProcessingStageStatus.valueOf(rs.getString("status")),
                (Integer) rs.getObject("progress"),
                rs.getString("message"),
                rs.getTimestamp("updated_at").toInstant()), mediaAssetId);
    }

    @Override
    public ProcessingStageRun save(ProcessingStageRun run) {
        jdbcTemplate.update("""
                INSERT INTO media_processing_stage_runs
                    (media_asset_id, stage, status, progress, message, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (media_asset_id, stage) DO UPDATE SET
                    status = EXCLUDED.status,
                    progress = EXCLUDED.progress,
                    message = EXCLUDED.message,
                    updated_at = EXCLUDED.updated_at
                """, run.mediaAssetId(), run.stage().name(), run.status().name(),
                run.progress(), run.message(), Timestamp.from(run.updatedAt()));
        return run;
    }
}
