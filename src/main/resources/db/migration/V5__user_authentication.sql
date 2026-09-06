-- V5: database-backed username/password authentication.
-- The development bootstrap password is stored only as a BCrypt hash. An existing
-- kk password is preserved so this migration cannot silently reset a live account.

ALTER TABLE app_user
    ADD COLUMN password_hash VARCHAR(100) NULL AFTER email;

INSERT INTO app_user (
    username,
    display_name,
    password_hash,
    is_admin,
    is_active
) VALUES (
    'kk',
    'kk',
    '$2a$10$Dpmx.uPX7a06Q3oyHaG4G./Dug.ol2mQfx6TwnwO7fl0ezzBjTnJK',
    TRUE,
    TRUE
)
ON DUPLICATE KEY UPDATE
    display_name = 'kk',
    is_admin = TRUE,
    is_active = TRUE,
    password_hash = IFNULL(password_hash,
        '$2a$10$Dpmx.uPX7a06Q3oyHaG4G./Dug.ol2mQfx6TwnwO7fl0ezzBjTnJK');
