CREATE TABLE POST(ID VARCHAR(36) PRIMARY KEY, ICON VARCHAR(128), TITLE VARCHAR(128), CONTENT VARCHAR(512));
INSERT INTO POST(ID, TITLE, CONTENT) VALUES ('00001', 'First post', 'HCMS rocks.');

CREATE TABLE ENTITY_REVISION(ID VARCHAR(36), NAME VARCHAR(255));

CREATE TABLE POST_FILTERED(ID VARCHAR(36) PRIMARY KEY, ICON VARCHAR(128), TITLE VARCHAR(128), CONTENT VARCHAR(512), AUTHOR VARCHAR(255), STATUS VARCHAR(16));
CREATE TABLE POST_VALIDATED(ID VARCHAR(36) PRIMARY KEY, ICON VARCHAR(128), TITLE VARCHAR(128), CONTENT VARCHAR(512));

CREATE TABLE BLOG_POST(ID VARCHAR(36) PRIMARY KEY, ICON VARCHAR(128), TITLE VARCHAR(128), CONTENT VARCHAR(512));
CREATE TABLE BLOG_COMMENT(ID VARCHAR(36) PRIMARY KEY, CONTENT VARCHAR(512), POST_ID VARCHAR(36));

