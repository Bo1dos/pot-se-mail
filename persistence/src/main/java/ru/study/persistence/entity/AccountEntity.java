package ru.study.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"credBlob", "credIv", "credSalt", "verifierHash", "verifierSalt"})
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AccountEntity {
    
    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "imap_server", length = 255)
    private String imapServer;

    @Column(name = "imap_port")
    private Integer imapPort;

    @Column(name = "smtp_server", length = 255)
    private String smtpServer;

    @Column(name = "smtp_port")
    private Integer smtpPort;

    @Column(name = "use_tls")
    private Boolean isUseTls;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "cred_blob")
    private byte[] credBlob;

    @Column(name = "cred_iv", length = 64)
    private byte[] credIv;

    @Column(name = "cred_salt", length = 64)
    private byte[] credSalt;

    @Column(name = "verifier_hash", length = 256)
    private byte[] verifierHash;

    @Column(name = "verifier_salt", length = 64)
    private byte[] verifierSalt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}