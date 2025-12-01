package ru.study.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "master_password")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class MasterPasswordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "verifier_salt", length = 64)
    private byte[] verifierSalt;

    @Column(name = "verifier_hash", length = 256)
    private byte[] verifierHash;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
