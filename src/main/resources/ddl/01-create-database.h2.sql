--
-- AUTHENTICATION/AUTHORIZATION
--
CREATE TABLE HCMS_USER(ID VARCHAR(36) PRIMARY KEY, LOGIN VARCHAR(1024) UNIQUE NOT NULL, FIRST_NAME VARCHAR(1024) UNIQUE NOT NULL, LAST_NAME VARCHAR(1024) UNIQUE NOT NULL, PASSWORD_HASH VARCHAR(2048) NOT NULL, ENABLED BOOLEAN);
CREATE TABLE HCMS_ROLE(ID VARCHAR(36) PRIMARY KEY, NAME VARCHAR(255) UNIQUE NOT NULL);
CREATE TABLE HCMS_USER_ROLE(USER_ID VARCHAR(36), ROLE_ID VARCHAR(36), PRIMARY KEY(USER_ID, ROLE_ID));
-- CREATE INDEX IDX_U_LOGIN ON HCMS_USER ((lower(LOGIN)));
CREATE INDEX IDX_HCMSU_LLOGIN ON HCMS_USER (LOGIN);
CREATE INDEX IDX_HCMSR_NAME ON HCMS_ROLE (NAME);

-- clear=@dm1n!
INSERT INTO HCMS_USER(ID, LOGIN, FIRST_NAME, LAST_NAME, PASSWORD_HASH, ENABLED) VALUES('admin', 'admin@app.com', 'admin', 'admin', '1;PBKDF2WithHmacSHA256;310000;512;JVkTm6ihGrdMVBUqMNhBig==;of4qBvsJ0QugB91UvAbCXZ8ae9ZgbF3WlcXjq3vdJQKgma2stAzKJotMgOEElb+L/ad2y860GeKTBwWdispR/A==', true);
