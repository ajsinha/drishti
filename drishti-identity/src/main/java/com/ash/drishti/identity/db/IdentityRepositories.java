/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.identity.db;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repositories for the identity tables: derived queries only, no SQL in code. */
public final class IdentityRepositories {

    private IdentityRepositories() {}

    public interface Users extends JpaRepository<UserEntity, String> {}

    public interface Roles extends JpaRepository<RoleEntity, String> {}

    public interface PackStates extends JpaRepository<PackStateEntity, String> {}

    public interface EmbedApps extends JpaRepository<EmbedAppEntity, String> {}

    public interface ApiTokens extends JpaRepository<ApiTokenEntity, String> {
        List<ApiTokenEntity> findByUsernameOrderByCreatedAtDesc(String username);

        List<ApiTokenEntity> findAllByOrderByCreatedAtDesc();

        void deleteByUsername(String username);
    }

    public interface Sessions extends JpaRepository<SessionEntity, String> {
        long deleteByUsername(String username);

        long deleteByExpiresAtBefore(java.time.Instant cutoff);
    }

    public interface Access extends JpaRepository<AccessEntity, Long>,
            org.springframework.data.jpa.repository.JpaSpecificationExecutor<AccessEntity> {
        @org.springframework.data.jpa.repository.Modifying
        @org.springframework.data.jpa.repository.Query("delete from AccessEntity a where a.at < :before")
        int deleteOlderThan(@org.springframework.data.repository.query.Param("before") java.time.Instant before);
    }

    public interface Notes extends JpaRepository<NoteEntity, Long> {
        List<NoteEntity> findByKindAndEntityIdOrderByIdAsc(String kind, String entityId);

        long countByKindAndEntityId(String kind, String entityId);
    }

    public interface Alerts extends JpaRepository<AlertEntity, Long> {
        List<AlertEntity> findByUsernameOrderByIdDesc(String username, Pageable page);

        long countByUsername(String username);

        void deleteByUsernameAndIdLessThan(String username, Long id);

        void deleteByUsername(String username);
    }

    public interface Audit extends JpaRepository<AuditEventEntity, Long> {
        List<AuditEventEntity> findAllByOrderByIdDesc(Pageable page);

        List<AuditEventEntity> findByIdGreaterThanOrderByIdAsc(Long id, Pageable page);

        List<AuditEventEntity> findBySubjectOrActorOrderByIdDesc(String subject, String actor, Pageable page);
    }

    public interface Preferences extends JpaRepository<PreferenceEntity, PreferenceEntity.Key> {
        List<PreferenceEntity> findByKeyUsernameAndKeyNamespaceOrderByKeyName(String username, String namespace);

        long countByKeyUsernameAndKeyNamespace(String username, String namespace);

        List<PreferenceEntity> findByKeyUsername(String username);

        void deleteByKeyUsername(String username);
    }

    public interface Designs extends JpaRepository<DesignEntity, DesignEntity.Key> {
        List<DesignEntity> findByKeyUsername(String username);

        void deleteByKeyUsername(String username);
    }

    public interface Shares extends JpaRepository<ShareEntity, String> {
        List<ShareEntity> findBySenderOrderByIdDesc(String sender, Pageable page);

        List<ShareEntity> findBySenderAndIdLessThanOrderByIdDesc(String sender, String before, Pageable page);

        long countBySenderAndCreatedAtGreaterThanEqual(String sender, java.time.Instant since);

        List<ShareEntity> findByIdGreaterThanOrderByIdAsc(String after, Pageable page);
    }

    public interface ShareRecipients extends JpaRepository<ShareRecipientEntity, ShareRecipientEntity.Key> {
        List<ShareRecipientEntity> findByKeyShareIdOrderByKeySeq(String shareId);

        List<ShareRecipientEntity> findByUsernameAndStateOrderByKeyShareIdDesc(String username, String state, Pageable page);

        List<ShareRecipientEntity> findByUsernameAndStateAndKeyShareIdLessThanOrderByKeyShareIdDesc(String username, String state,
                String before, Pageable page);

        void deleteByKeyShareId(String shareId);
    }

    public interface Seals extends JpaRepository<SealEntity, String> {}

    public interface Holds extends JpaRepository<HoldEntity, Long> {
        List<HoldEntity> findAllByOrderByIdDesc();

        List<HoldEntity> findByReleasedAtIsNullOrderByIdDesc();
    }

    public interface Outbox extends JpaRepository<OutboxEntity, Long> {
        @org.springframework.data.jpa.repository.Query("select o from OutboxEntity o where (o.state = 'pending' and o.nextAt <= :now) or (o.state = 'sending' and o.leaseUntil < :now) order by o.seq")
        List<OutboxEntity> due(@org.springframework.data.repository.query.Param("now") java.time.Instant now, Pageable page);

        @org.springframework.data.jpa.repository.Modifying
        @org.springframework.data.jpa.repository.Query("update OutboxEntity o set o.state = 'sending', o.leaseUntil = :lease, o.leasedBy = :owner where o.seq = :seq and ((o.state = 'pending' and o.nextAt <= :now) or (o.state = 'sending' and o.leaseUntil < :now))")
        int claim(@org.springframework.data.repository.query.Param("seq") long seq, @org.springframework.data.repository.query.Param("now") java.time.Instant now,
                @org.springframework.data.repository.query.Param("lease") java.time.Instant lease,
                @org.springframework.data.repository.query.Param("owner") String owner);

        List<OutboxEntity> findByStateOrderBySeqDesc(String state, Pageable page);

        List<OutboxEntity> findAllByOrderBySeqDesc(Pageable page);

        List<OutboxEntity> findBySeqGreaterThanOrderBySeq(long seq, Pageable page);

        long countByState(String state);

        long countByRecipientAndCreatedAtGreaterThanEqual(String recipient, java.time.Instant since);

        @org.springframework.data.jpa.repository.Modifying
        @org.springframework.data.jpa.repository.Query("delete from OutboxEntity o where o.state = 'sent' and o.sentAt < :before")
        int purgeSent(@org.springframework.data.repository.query.Param("before") java.time.Instant before);
    }

    public interface Inbox extends JpaRepository<InboxEntity, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<InboxEntity> {
        long countByUsernameAndReadAtIsNull(String username);

        List<InboxEntity> findBySeqGreaterThanOrderBySeq(long seq, Pageable page);

        @org.springframework.data.jpa.repository.Query("select coalesce(max(i.seq), 0) from InboxEntity i")
        long maxSeq();

        @org.springframework.data.jpa.repository.Modifying
        @org.springframework.data.jpa.repository.Query("update InboxEntity i set i.readAt = :at where i.username = :user and i.readAt is null and i.seq in :seqs")
        int markRead(@org.springframework.data.repository.query.Param("user") String user,
                @org.springframework.data.repository.query.Param("seqs") java.util.Collection<Long> seqs,
                @org.springframework.data.repository.query.Param("at") java.time.Instant at);

        @org.springframework.data.jpa.repository.Modifying
        @org.springframework.data.jpa.repository.Query("update InboxEntity i set i.readAt = :at where i.username = :user and i.readAt is null and i.seq <= :upTo")
        int markReadUpTo(@org.springframework.data.repository.query.Param("user") String user,
                @org.springframework.data.repository.query.Param("upTo") long upTo,
                @org.springframework.data.repository.query.Param("at") java.time.Instant at);

        List<InboxEntity> findByUsernameOrderBySeqDesc(String username, Pageable page);

        void deleteByUsernameAndSeqLessThan(String username, long seq);

        @org.springframework.data.jpa.repository.Modifying
        @org.springframework.data.jpa.repository.Query("delete from InboxEntity i where i.at < :before")
        int deleteOlderThan(@org.springframework.data.repository.query.Param("before") java.time.Instant before);

        void deleteByUsername(String username);
    }

    public interface DesignSamples extends JpaRepository<DesignSampleEntity, DesignSampleEntity.Key> {
        void deleteByKeyUsernameAndKeyDesignId(String username, String designId);

        void deleteByKeyUsername(String username);
    }

    public interface Threads extends JpaRepository<ThreadEntities.Thread, String> {
        List<ThreadEntities.Thread> findByKindAndEntityIdOrderByLastAtDesc(String kind, String entityId);

        List<ThreadEntities.Thread> findByIdGreaterThanOrderByIdAsc(String after, Pageable page);
    }

    public interface Comments extends JpaRepository<ThreadEntities.Comment, String> {
        List<ThreadEntities.Comment> findByThreadIdOrderByCreatedAtAscIdAsc(String threadId);

        void deleteByThreadId(String threadId);
    }

    public interface Revisions extends JpaRepository<ThreadEntities.Revision, ThreadEntities.RevisionKey> {
        List<ThreadEntities.Revision> findByKeyCommentIdOrderByKeyRevisionAsc(String commentId);

        void deleteByKeyCommentIdIn(java.util.Collection<String> commentIds);

        @org.springframework.data.jpa.repository.Query("select r from CollabRevision r where r.key.commentId in "
                + "(select c.id from CollabComment c where c.threadId = :thread) order by r.at asc")
        List<ThreadEntities.Revision> chain(@org.springframework.data.repository.query.Param("thread") String thread);

        @org.springframework.data.jpa.repository.Query("select r from CollabRevision r where r.key.commentId in "
                + "(select c.id from CollabComment c where c.threadId = :thread) order by r.at desc")
        List<ThreadEntities.Revision> latest(@org.springframework.data.repository.query.Param("thread") String thread, Pageable page);
    }

    public interface Mentions extends JpaRepository<ThreadEntities.Mention, ThreadEntities.MentionKey> {
        List<ThreadEntities.Mention> findByKeyCommentId(String commentId);

        void deleteByKeyCommentIdIn(java.util.Collection<String> commentIds);

        List<ThreadEntities.Mention> findByKeyTargetInOrderByKeyCommentIdDesc(java.util.Collection<String> targets, Pageable page);

        List<ThreadEntities.Mention> findByKeyTargetInAndKeyCommentIdLessThanOrderByKeyCommentIdDesc(java.util.Collection<String> targets,
                String before, Pageable page);
    }

    public interface Follows extends JpaRepository<ThreadEntities.Follow, ThreadEntities.FollowKey> {
        List<ThreadEntities.Follow> findByKeyThreadId(String threadId);

        void deleteByKeyThreadId(String threadId);
    }

    public interface NoteLinks extends JpaRepository<ThreadEntities.NoteLink, Long> {
        java.util.Optional<ThreadEntities.NoteLink> findByCommentId(String commentId);

        void deleteByCommentIdIn(java.util.Collection<String> commentIds);
    }
}
