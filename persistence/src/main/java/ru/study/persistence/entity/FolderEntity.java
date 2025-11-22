package ru.study.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "folders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class FolderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "server_name", length = 255)
    private String serverName;

    @Column(name = "local_name", length = 255)
    private String localName;

    @Column(name = "last_sync_uid")
    private Long lastSyncUid;
}
