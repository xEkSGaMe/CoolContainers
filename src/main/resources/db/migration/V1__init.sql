CREATE TABLE IF NOT EXISTS cc_schema_version (
                                                 version      VARCHAR(64) NOT NULL,
    applied_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (version)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cc_case_keys (
                                            player_uuid   CHAR(36)     NOT NULL,
    case_id       VARCHAR(64)  NOT NULL,
    amount        BIGINT       NOT NULL DEFAULT 0,
    version       BIGINT       NOT NULL DEFAULT 0,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (player_uuid, case_id),
    INDEX idx_case_keys_case (case_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cc_key_transactions (
                                                   id           BIGINT       NOT NULL AUTO_INCREMENT,
                                                   player_uuid  CHAR(36)     NOT NULL,
    case_id      VARCHAR(64)  NOT NULL,
    delta        BIGINT       NOT NULL,
    reason       VARCHAR(32)  NOT NULL,
    admin        CHAR(36)     NULL,
    server_id    VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_key_tx_player (player_uuid, created_at),
    INDEX idx_key_tx_created (created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cc_case_openings (
                                                opening_id       CHAR(36)     NOT NULL,
    player_uuid      CHAR(36)     NOT NULL,
    case_id          VARCHAR(64)  NOT NULL,
    keys_spent       INT          NOT NULL DEFAULT 1,
    reward_snapshot  JSON         NULL,
    status           VARCHAR(16)  NOT NULL,
    server_id        VARCHAR(64)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at     TIMESTAMP    NULL,
    PRIMARY KEY (opening_id),
    INDEX idx_openings_player (player_uuid, case_id, status, server_id, created_at),
    INDEX idx_openings_status_server (status, server_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cc_reward_grants (
                                                opening_id   CHAR(36)     NOT NULL,
    reward_id    VARCHAR(64)  NOT NULL,
    snapshot     JSON         NULL,
    status       VARCHAR(16)  NOT NULL,
    error        TEXT         NULL,
    server_id    VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (opening_id, reward_id),
    INDEX idx_grants_status (status, server_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cc_case_blocks (
                                              server_id   VARCHAR(64)  NOT NULL,
    world       VARCHAR(64)  NOT NULL,
    x           INT          NOT NULL,
    y           INT          NOT NULL,
    z           INT          NOT NULL,
    case_id     VARCHAR(64)  NOT NULL,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (server_id, world, x, y, z),
    INDEX idx_blocks_case (case_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cc_admin_logs (
                                             id           BIGINT       NOT NULL AUTO_INCREMENT,
                                             admin_uuid   CHAR(36)     NOT NULL,
    action       VARCHAR(64)  NOT NULL,
    payload      TEXT         NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_admin_logs_admin (admin_uuid, created_at),
    INDEX idx_admin_logs_created (created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cc_case_hashes (
                                              case_id       VARCHAR(64)  NOT NULL,
    content_hash  CHAR(64)     NOT NULL,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    server_id     VARCHAR(64)  NOT NULL,
    PRIMARY KEY (case_id, server_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;