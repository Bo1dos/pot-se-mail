package ru.study.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "attachments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"encryptedBlob"})
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AttachmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private MessageEntity message;

    @Column(name = "filename", length = 512)
    private String filename;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "file_path", length = 1024)
    private String filePath;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "encrypted_blob")
    private byte[] encryptedBlob;

    @Column(name = "iv")
    private byte[] iv;

    @Column(name = "size")
    private Long size;
}
