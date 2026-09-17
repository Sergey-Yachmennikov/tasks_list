CREATE TABLE application (
    id              UUID PRIMARY KEY,
    client_id       UUID        NOT NULL,
    status          VARCHAR(32) NOT NULL,
    kyc_session_id  VARCHAR(128)
);
