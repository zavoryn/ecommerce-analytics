-- master 启动后授权 slave 复制账号
-- 真正配置 slave 还需在 slave 容器内执行 CHANGE MASTER TO + START SLAVE,
-- 见 README-ha.md
CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED WITH mysql_native_password BY 'repl_pass';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
FLUSH PRIVILEGES;
