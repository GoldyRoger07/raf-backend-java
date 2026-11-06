package com.raikiservice.raf.news.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.media.service.MediaUrls;
import com.raikiservice.raf.news.web.dto.NewsDtos.NewsItem;
import com.raikiservice.raf.news.web.dto.NewsDtos.NewsRequest;
import com.raikiservice.raf.news.web.dto.NewsDtos.NewsResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Actualites de l'onglet Actualite de l'app joueur. Le back-office les ecrit,
 * les publie ou les garde en brouillon, et en epingle en tete du fil.
 *
 * <p>Fil du joueur : les actualites publiees, epinglees d'abord, puis de la plus
 * recente a la plus ancienne.
 */
@Service
@RequiredArgsConstructor
public class NewsService {

    private static final String SELECT = """
            SELECT n.id, n.title, n.body, n.media_id, m.storage_key, n.pinned, n.published,
                   n.published_at, n.created_at, n.updated_at
            FROM news_posts n
            LEFT JOIN media_files m ON m.id = n.media_id
            """;

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    // --- App joueur ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<NewsItem> feed(Pageable pageable) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM news_posts WHERE published = TRUE", Long.class);
        List<NewsItem> content = jdbc.query(SELECT + """
                WHERE n.published = TRUE
                ORDER BY n.pinned DESC, n.published_at DESC, n.id DESC
                LIMIT ? OFFSET ?
                """, (rs, row) -> {
                    NewsResponse news = map(rs);
                    return new NewsItem(news.id(), news.title(), news.body(), news.imageUrl(), news.pinned(),
                            news.publishedAt());
                }, pageable.getPageSize(), pageable.getOffset());
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    // --- Back-office --------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<NewsResponse> list(Pageable pageable) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM news_posts", Long.class);
        List<NewsResponse> content = jdbc.query(SELECT + """
                ORDER BY n.pinned DESC, COALESCE(n.published_at, n.created_at) DESC, n.id DESC
                LIMIT ? OFFSET ?
                """, (rs, row) -> map(rs), pageable.getPageSize(), pageable.getOffset());
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public NewsResponse get(Long id) {
        return jdbc.query(SELECT + " WHERE n.id = ?", (rs, row) -> map(rs), id).stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Actualite"));
    }

    @Transactional
    public NewsResponse create(Long actorId, NewsRequest request) {
        checkMedia(request.mediaId());
        Timestamp now = Timestamp.from(Instant.now());
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO news_posts (title, body, media_id, pinned, published, published_at,
                                            created_by_id, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            statement.setString(1, request.title().trim());
            statement.setString(2, request.body().trim());
            statement.setObject(3, request.mediaId());
            statement.setBoolean(4, request.pinned());
            statement.setBoolean(5, request.published());
            statement.setTimestamp(6, request.published() ? now : null);
            statement.setObject(7, actorId);
            statement.setTimestamp(8, now);
            statement.setTimestamp(9, now);
            return statement;
        }, key);
        Long id = key.getKey().longValue();
        auditService.record(actorId, "NEWS_CREATED", "News", String.valueOf(id), null,
                (request.published() ? "publiee : " : "brouillon : ") + request.title().trim());
        return get(id);
    }

    @Transactional
    public NewsResponse update(Long actorId, Long id, NewsRequest request) {
        NewsResponse before = get(id);
        checkMedia(request.mediaId());
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                UPDATE news_posts
                SET title = ?, body = ?, media_id = ?, pinned = ?, published = ?,
                    published_at = CASE WHEN ? AND published_at IS NULL THEN ? ELSE published_at END,
                    updated_at = ?
                WHERE id = ?
                """, request.title().trim(), request.body().trim(), request.mediaId(), request.pinned(),
                request.published(), request.published(), now, now, id);
        auditService.record(actorId, "NEWS_UPDATED", "News", String.valueOf(id),
                before.published() ? "publiee" : "brouillon",
                request.published() ? "publiee" : "brouillon");
        return get(id);
    }

    @Transactional
    public void delete(Long actorId, Long id) {
        NewsResponse news = get(id);
        jdbc.update("DELETE FROM news_posts WHERE id = ?", id);
        auditService.record(actorId, "NEWS_DELETED", "News", String.valueOf(id), news.title(), null);
    }

    // --- Outils -------------------------------------------------------------------

    private void checkMedia(Long mediaId) {
        if (mediaId != null) {
            Long found = jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE id = ?", Long.class, mediaId);
            if (found == null || found == 0) {
                throw ApiException.notFound("Image");
            }
        }
    }

    private static NewsResponse map(ResultSet rs) throws SQLException {
        return new NewsResponse(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getObject("media_id", Long.class),
                MediaUrls.resolve(rs.getString("storage_key")),
                rs.getBoolean("pinned"),
                rs.getBoolean("published"),
                instant(rs, "published_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
