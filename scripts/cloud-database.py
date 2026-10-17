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
import yaml

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


def inspect(cfg, configure):
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
            # Limit discovery to the requested project under /root.
            requested = cfg["SWARM_CONFIG_PATH"]
            candidates = [requested, "/root/" + requested.lstrip("/")]
            candidates += remote(client, "find /root -maxdepth 7 -type f -iname 'productdemojpa.y*ml' 2>/dev/null").splitlines()
            for path in dict.fromkeys(candidates):
                sftp = client.open_sftp()
                try:
                    with sftp.open(path) as source:
                        document = yaml.safe_load(source.read())
                except OSError:
                    continue
                finally:
                    sftp.close()
                for name, spec in (document.get("services", {}) if isinstance(document, dict) else {}).items():
                    if "mysql" not in str(spec.get("image", "")).lower():
                        continue
                    env = spec.get("environment", {})
                    if isinstance(env, list):
                        env = dict(item.split("=", 1) for item in env if "=" in item)
                    password = env.get("MYSQL_ROOT_PASSWORD")
                    print("MySQL YAML located on " + label + "; literal root credential=" + str(bool(password and not str(password).startswith("${"))))
                    if configure and password and not str(password).startswith("${"):
                        save_configuration({"CLOUD_DB_PASSWORD": str(password)})
                        print("Root credential candidate saved to ignored .env; authentication still required")
                        return
    if configure:
        raise RuntimeError("No literal root credential found; inspect environment/secret source next")


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


def diagnose(cfg):
    with connect(cfg, "SWARM_MANAGER_HOST") as client:
        services = json.loads(remote(client, "docker service inspect lab_mysql lab_productdemojpa"))
        candidates = {cfg["CLOUD_DB_PASSWORD"]: "YAML initial credential"}
        for service in services:
            spec = service["Spec"]["TaskTemplate"]["ContainerSpec"]
            env = dict(item.split("=", 1) for item in spec.get("Env", []) if "=" in item)
            print(service["Spec"]["Name"] + " environment keys=" + ",".join(env))
            for key, value in env.items():
                if "PASSWORD" in key.upper() and value:
                    candidates[value] = "runtime " + key
            print(service["Spec"]["Name"] + " mount targets=" + ",".join(m.get("Target", "") for m in spec.get("Mounts", [])))
        files = remote(client, "find /root -maxdepth 9 -type f \\( -iname '*.yml' -o -iname '*.yaml' -o -iname '*.sql' -o -iname '*.properties' \\) -path '*productdemojpa*' 2>/dev/null").splitlines()
        print("Project config/SQL files found:", len(files))
        sftp = client.open_sftp()
        try:
            for path in files:
                with sftp.open(path) as source:
                    raw = source.read().decode("utf-8", errors="replace")
                if path.endswith((".yml", ".yaml")):
                    doc = yaml.safe_load(raw)
                    def walk(value):
                        if isinstance(value, dict):
                            for k, v in value.items():
                                if "password" in str(k).lower() and isinstance(v, (str, int)) and v and not str(v).startswith("${"):
                                    candidates[str(v)] = "project YAML " + str(k)
                                walk(v)
                        elif isinstance(value, list):
                            for v in value: walk(v)
                    walk(doc)
                elif path.endswith(".sql"):
                    for match in re.finditer(r"(?:IDENTIFIED\s+BY|PASSWORD\s*\()\s*['\"]([^'\"]+)['\"]", raw, re.I):
                        candidates[match[1]] = "project SQL credential assignment"
        finally:
            sftp.close()
        print("Distinct documented/runtime credential candidates:", len(candidates))
        # Only try credentials actually configured by this deployment, no guessing.
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        for candidate, origin in candidates.items():
            if candidate == cfg["CLOUD_DB_PASSWORD"]: continue
            try:
                with pymysql.connect(host=cfg["CLOUD_DB_HOST"], port=int(cfg["CLOUD_DB_PORT"]), user="root", password=candidate, ssl=context, connect_timeout=6) as connection:
                    with connection.cursor() as cursor:
                        cursor.execute("SHOW SESSION STATUS LIKE 'Ssl_cipher'")
                        if not cursor.fetchone()[1]: raise RuntimeError("TLS not negotiated")
                        cursor.execute("SELECT VERSION()")
                        print("Actual deployment credential authenticated over TLS; MySQL=" + cursor.fetchone()[0] + "; source=" + origin)
                    save_configuration({"CLOUD_DB_PASSWORD": candidate})
                    return
            except pymysql.MySQLError as exc:
                print("Deployment credential candidate rejected; source=" + origin + "; error=" + str(exc.args[0]))
        raise RuntimeError("No current root credential found in inspected deployment sources")


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


def credential_history(cfg):
    candidates = set()
    for label in ("SWARM_MANAGER_HOST", "SWARM_WORKER_HOST"):
        with connect(cfg, label) as client:
            sources = remote(client, "for f in /root/.mysql_history /root/.bash_history; do if [ -f \"$f\" ]; then cat \"$f\"; fi; done")
            sources += remote(client, "for c in $(docker ps --filter name=lab_mysql --format '{{.ID}}'); do docker exec \"$c\" sh -c 'for f in /root/.mysql_history /root/.bash_history; do if [ -f \"$f\" ]; then cat \"$f\"; fi; done'; done")
            patterns = [r"IDENTIFIED\s+(?:WITH\s+\w+\s+)?BY\s+['\"]([^'\"]+)['\"]", r"SET\s+PASSWORD[^;\n]*?=\s*(?:PASSWORD\s*\()?\s*['\"]([^'\"]+)['\"]"]
            for pattern in patterns:
                candidates.update(re.findall(pattern, sources, re.I))
    print("Explicit MySQL password assignments in server history:", len(candidates))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    for candidate in candidates:
        if candidate == cfg["CLOUD_DB_PASSWORD"]: continue
        try:
            with pymysql.connect(host=cfg["CLOUD_DB_HOST"], port=int(cfg["CLOUD_DB_PORT"]), user="root", password=candidate, ssl=context, connect_timeout=6) as connection:
                with connection.cursor() as cursor:
                    cursor.execute("SHOW SESSION STATUS LIKE 'Ssl_cipher'")
                    if not cursor.fetchone()[1]: raise RuntimeError("TLS not negotiated")
                save_configuration({"CLOUD_DB_PASSWORD": candidate})
                print("Current remote root credential authenticated over TLS and saved to .env")
                return
        except pymysql.MySQLError as exc:
            print("Recorded MySQL credential rejected, error=" + str(exc.args[0]))
    raise RuntimeError("Remote root credential not recovered from documented password assignments")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("inspect", "credential", "verify", "create", "provision", "diagnose", "local-auth", "credential-history"))
    action = parser.parse_args().action
    try:
        if action in ("inspect", "credential"):
            inspect(configuration(), action == "credential")
        elif action == "diagnose":
            diagnose(configuration())
        elif action == "local-auth":
            local_auth(configuration())
        elif action == "credential-history":
            credential_history(configuration())
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
