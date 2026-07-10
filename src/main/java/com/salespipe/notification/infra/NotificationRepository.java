package com.salespipe.notification.infra;

import com.salespipe.notification.domain.Notification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * T6.7: a user's notifications within their org, newest first — backs {@code
     * GET /notifications}. Explicitly org- AND user-scoped (not relying on the Hibernate
     * tenant @Filter alone) so one user can't read another user's notifications in the
     * same org. Matches idx_notifications_org_user_created.
     */
    @Query("select n from Notification n where n.orgId = :orgId and n.userId = :userId "
        + "order by n.createdAt desc")
    List<Notification> findForUser(@Param("orgId") UUID orgId,
                                   @Param("userId") UUID userId, Pageable page);

    /** Count of a user's unread notifications (read_at IS NULL) — drives the bell badge. */
    @Query("select count(n) from Notification n where n.orgId = :orgId "
        + "and n.userId = :userId and n.readAt is null")
    long countUnread(@Param("orgId") UUID orgId, @Param("userId") UUID userId);

    /**
     * Mark one notification read, scoped to the owning user so a forged id can't mark
     * another user's row. Returns rows affected (0 = not found / not yours).
     */
    @Modifying
    @Query("update Notification n set n.readAt = current_timestamp "
        + "where n.id = :id and n.orgId = :orgId and n.userId = :userId and n.readAt is null")
    int markRead(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("userId") UUID userId);
}
