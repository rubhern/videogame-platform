-- Issue #295: a signed-in user's preferred platforms, which the client applies to release
-- discovery as an explicit platform filter. Expand-only: new tables that older application
-- versions never read.
--
-- user_id is the product UserId the BFF derives from the validated issuer and subject; nothing
-- else about the person is stored. A choice row records that the user has saved a choice, which an
-- empty set also is, so a first-run offer is never repeated once completed or skipped. The
-- preference rows hang from it and from the local platform taxonomy, so a choice never names a
-- platform the catalogue does not hold and one user's set is replaced as a whole.
CREATE TABLE catalogue.platform_preference_choice (
    user_id uuid PRIMARY KEY,
    updated_at timestamptz NOT NULL
);

CREATE TABLE catalogue.platform_preference (
    user_id uuid NOT NULL,
    platform_id uuid NOT NULL,
    CONSTRAINT pk_platform_preference PRIMARY KEY (user_id, platform_id),
    CONSTRAINT fk_platform_preference_choice
        FOREIGN KEY (user_id) REFERENCES catalogue.platform_preference_choice (user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_platform_preference_platform
        FOREIGN KEY (platform_id) REFERENCES catalogue.platform (platform_id)
);

COMMENT ON TABLE catalogue.platform_preference_choice IS
    'Personal: one row per product UserId that has saved a preferred-platform choice, including an empty one.';
COMMENT ON TABLE catalogue.platform_preference IS
    'Personal: the platforms a product UserId prefers for release discovery; replaced as a whole set.';
