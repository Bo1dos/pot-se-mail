package ru.study.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "message_wrapped_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"wrappedBlob"})
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MessageWrappedKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private MessageEntity message;

    @Column(name = "recipient", length = 255)
    private String recipient;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "wrapped_blob", nullable = false)
    private byte[] wrappedBlob;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}