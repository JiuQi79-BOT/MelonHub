"""Inspect the existing Swarm and configure only MelonHub databases.

Run with the isolated .local/cloud-tools Python environment. Secrets and hosts
are read from .env and never passed as command-line arguments or printed.
"""
import argparse
import hashlib
import json
import re
import shlex
import ssl
from pathlib import Path

import paramiko
import pymysql

ROOT = Path(__file__).resolve().parents[1]


def configuration():
    return dict(line.split("=", 1) for line in (ROOT / ".env").read_text(encoding="utf-8-sig").splitlines()
                if line and not line.startswith("#") and "=" in line)


def save_configuration(updates):
    path = ROOT / ".env"
    lines = path.read_text(encoding="utf-8-sig").splitlines()
    for key, value in updates.items():
        if any(c in value for c in "\r\n\\") or value != value.strip():
            raise RuntimeError("Credential needs explicit Java properties escaping; configuration not changed")
        matches = [i for i, line in enumerate(lines) if line.startswith(key + "=")]
        if matches:
            lines[matches[0]] = key + "=" + value
        else:
            lines.append(key + "=" + value)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


class PinHost(paramiko.MissingHostKeyPolicy):
    def missing_host_key(self, client, hostname, key):
        # First contact pins the presented key; later changes are rejected.
        print("SSH first-contact key pinned: SHA256:" + __import__("base64").b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip("="))
        client.get_host_keys().add(paramiko.HostKeys.hash_host(hostname), key.get_name(), key)
        client.save_host_keys(str(ROOT / ".local" / "cloud_known_hosts"))


def connect(cfg, label):
    client = paramiko.SSHClient()
    known = ROOT / ".local" / "cloud_known_hosts"
    if known.exists():
        client.load_host_keys(str(known))
    client.set_missing_host_key_policy(PinHost())
    client.connect(cfg[label], port=int(cfg.get("SWARM_SSH_PORT", "22")),
                   username=cfg["SWARM_SSH_USERNAME"], password=cfg["SWARM_SSH_PASSWORD"],
                   timeout=8, auth_timeout=8, banner_timeout=8, look_for_keys=False, allow_agent=False)
    return client


def remote(client, command):
    _, out, err = client.exec_command(command, timeout=20)
    result = out.read().decode("utf-8")
    error = err.read().decode("utf-8")
    if out.channel.recv_exit_status() != 0:
        # Remote errors may contain configuration; do not echo them.
        raise RuntimeError("Remote read-only command failed")
    return result


def inspect(cfg):
    for label in ("SWARM_MANAGER_HOST", "SWARM_WORKER_HOST"):
        with connect(cfg, label) as client:
            identity = remote(client, "id -u").strip()
            print(label + ": SSH authenticated, uid=" + identity)
            if label == "SWARM_MANAGER_HOST":
                nodes = [json.loads(line) for line in remote(client, "docker node ls --format '{{json .}}'").splitlines()]
                print("Swarm nodes:", json.dumps([{k: n.get(k) for k in ("ID", "Status", "Availability", "ManagerStatus")} for n in nodes]))
                services = [json.loads(line) for line in remote(client, "docker service ls --format '{{json .}}'").splitlines()]
                print("Services:", json.dumps([{k: s.get(k) for k in ("Name", "Image", "Replicas", "Ports")} for s in services]))
                mysql = [s for s in services if "mysql" in s.get("Image", "").lower()]
                if len(mysql) != 1:
                    raise RuntimeError("Expected exactly one MySQL service")
                service = shlex.quote(mysql[0]["Name"])
                details = json.loads(remote(client, "docker service inspect " + service))[0]
                print("MySQL endpoint:", json.dumps(details.get("Endpoint", {}).get("Ports", [])))
                tasks = [json.loads(line) for line in remote(client, "docker service ps --filter desired-state=running " + service + " --format '{{json .}}'").splitlines()]
                print("MySQL running task count:", len(tasks))


def database(cfg, provision):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    for label in ("SWARM_WORKER_HOST", "SWARM_MANAGER_HOST"):
        try:
            connection = pymysql.connect(host=cfg[label], port=int(cfg["CLOUD_DB_PORT"]),
                                         user=cfg["CLOUD_DB_USERNAME"], password=cfg["CLOUD_DB_PASSWORD"],
                                         ssl=context, connect_timeout=6, read_timeout=15, write_timeout=15, autocommit=True)
        except pymysql.MySQLError as exc:
            print(label + ": MySQL connection failed, error=" + str(exc.args[0]))
            continue
        with connection:
            with connection.cursor() as cursor:
                cursor.execute("SHOW SESSION STATUS LIKE 'Ssl_cipher'")
                if not cursor.fetchone()[1]:
                    raise RuntimeError("TLS was not negotiated; no database changes made")
                cursor.execute("SELECT VERSION(), CURRENT_USER()")
                version, account = cursor.fetchone()
                print(label + ": authenticated MySQL=" + version + ", TLS negotiated, root=" + str(account.startswith("root@")))
                for name in ("melonhub_rbac", "melonhub_rbac_test"):
                    cursor.execute("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s", (name,))
                    print(name + " existing table count=" + str(cursor.fetchone()[0]))
                    if provision:
                        cursor.execute("CREATE DATABASE IF NOT EXISTS " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
                        print(name + " confirmed")
                if provision:
                    url = "jdbc:mysql://" + cfg[label] + ":" + cfg["CLOUD_DB_PORT"] + "/melonhub_rbac_test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=SERVER&connectTimeout=5000&socketTimeout=30000&sslMode=REQUIRED"
                    save_configuration({"CLOUD_DB_HOST": cfg[label], "TEST_DB_URL": url,
                                        "TEST_DB_USERNAME": cfg["CLOUD_DB_USERNAME"], "TEST_DB_PASSWORD": cfg["CLOUD_DB_PASSWORD"]})
                    print("Cloud application and dedicated test configurations saved")
                return
    raise RuntimeError("No authenticated TLS database connection; no schema changes made")


def create_project_database(cfg):
    name = cfg.get("CLOUD_DB_NAME", "")
    if name != "melonhub_rbac":
        raise RuntimeError("Creation is restricted to the project's melonhub_rbac schema")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    with pymysql.connect(host=cfg["CLOUD_DB_HOST"], port=int(cfg["CLOUD_DB_PORT"]),
                         user=cfg["CLOUD_DB_USERNAME"], password=cfg["CLOUD_DB_PASSWORD"],
                         ssl=context, connect_timeout=6, read_timeout=15,
                         write_timeout=15, autocommit=True) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SHOW SESSION STATUS LIKE 'Ssl_cipher'")
            if not cursor.fetchone()[1]:
                raise RuntimeError("TLS was not negotiated; no schema changes made")
            cursor.execute("SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s", (name,))
            existing = cursor.fetchone()
            if existing:
                print("melonhub_rbac already exists; existing schema was not altered")
            else:
                cursor.execute("CREATE DATABASE melonhub_rbac CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
                print("Created dedicated project database melonhub_rbac")
            cursor.execute("SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s", (name,))
            charset, collation = cursor.fetchone()
            cursor.execute("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s", (name,))
            tables = cursor.fetchone()[0]
        connection.select_db(name)
        with connection.cursor() as cursor:
            cursor.execute("SELECT DATABASE(), 1")
            if cursor.fetchone() != (name, 1):
                raise RuntimeError("Project database selection verification failed")
        print("Project database connection verified with TLS")
        print("Charset=" + charset + "; collation=" + collation + "; table count=" + str(tables))
        print("The only mutating SQL allowed by this action is CREATE DATABASE melonhub_rbac")


def local_auth(cfg):
    for label in ("SWARM_MANAGER_HOST", "SWARM_WORKER_HOST"):
        with connect(cfg, label) as client:
            containers = remote(client, "docker ps --filter name=lab_mysql --format '{{.ID}}'").splitlines()
            print(label + " MySQL container count=" + str(len(containers)))
            for container in containers:
                if not re.fullmatch(r"[a-f0-9]+", container): raise RuntimeError("Invalid container ID")
                query = "SELECT VERSION(), CURRENT_USER(); SELECT user,host,plugin,account_locked,password_expired,ssl_type FROM mysql.user WHERE user='root'; SHOW VARIABLES WHERE Variable_name IN ('tls_version','socket','port','skip_name_resolve');"
                command = "docker exec -i " + container + " sh -c " + shlex.quote("IFS= read -r MYSQL_PWD; export MYSQL_PWD; exec mysql --protocol=SOCKET -uroot -N -B -e " + shlex.quote(query))
                stdin, out, err = client.exec_command(command, timeout=15)
                stdin.write(cfg["CLOUD_DB_PASSWORD"] + "\n")
                stdin.flush()
                stdin.channel.shutdown_write()
                result, error = out.read().decode(), err.read().decode()
                code = out.channel.recv_exit_status()
                if code == 0:
                    print("Container-local root authentication succeeded")
                    # Redact host data before printing account policy.
                    print(re.sub(r"(?:\d{1,3}\.){3}\d{1,3}", "[host]", result))
                else:
                    number = re.search(r"ERROR\s+(\d+)", error)
                    print("Container-local root authentication failed, error=" + (number[1] if number else "unknown"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("inspect", "verify", "create", "provision", "local-auth"))
    action = parser.parse_args().action
    try:
        if action == "inspect":
            inspect(configuration())
        elif action == "local-auth":
            local_auth(configuration())
        elif action == "create":
            create_project_database(configuration())
        else:
            database(configuration(), action == "provision")
    except Exception as exc:
        # Authentication libraries can include hosts or credentials in exception text.
        print("Operation stopped: " + type(exc).__name__)
        if type(exc) is RuntimeError:
            print(str(exc))
        raise SystemExit(1)
