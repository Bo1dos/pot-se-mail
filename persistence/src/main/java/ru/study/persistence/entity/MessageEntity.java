package ru.study.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"encryptedBodyBlob", "signatureBlob"})
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    private FolderEntity folder;

    @Column(name = "server_uid", length = 255)
    private String serverUid;

    @Column(name = "subject", length = 1024)
    private String subject;

    @Column(name = "sender", length = 255)
    private String sender;

    @Lob
    @Column(name = "recipients")
    private String recipients;

    @Lob
    @Column(name = "cc")
    private String cc;

    @Column(name = "sent_date")
    private OffsetDateTime sentDate;

    @Column(name = "is_encrypted")
    @Builder.Default
    private Boolean isEncrypted = Boolean.FALSE;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "encrypted_body_blob")
    private byte[] encryptedBodyBlob;

    @Column(name = "body_iv")
    private byte[] bodyIv;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "signature_blob")
    private byte[] signatureBlob;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "is_seen")
    private Boolean isSeen;
    
    @Column(name = "is_deleted")
    private Boolean isDeleted;

    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<AttachmentEntity> attachments;

    // Новое поле для связи с wrapped keys
    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<MessageWrappedKeyEntity> wrappedKeys;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}