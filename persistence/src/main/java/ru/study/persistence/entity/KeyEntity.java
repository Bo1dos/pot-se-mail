package ru.study.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"privateKeyBlob", "keyIv", "keySalt"})
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class KeyEntity {
    
    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private AccountEntity account;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "public_key_pem")
    private String publicKeyPem;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "private_key_blob")
    private byte[] privateKeyBlob;

    @Column(name = "key_iv", length = 64)
    private byte[] keyIv;

    @Column(name = "key_salt", length = 64)
    private byte[] keySalt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}