"""Rotate the explicitly authorized cloud root credential without logging secrets.

Use .local/cloud-tools/Scripts/python.exe. The desired credential is read only
from CLOUD_DB_PASSWORD_NEXT in the ignored root .env file.
"""
import argparse
import copy
import io
import json
import re
import shlex
import ssl
import time
import zipfile
from contextlib import ExitStack

import pymysql
import yaml
import importlib.util
from pathlib import Path

module_spec = importlib.util.spec_from_file_location("cloud_database", Path(__file__).with_name("cloud-database.py"))
cloud = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(cloud)


def mysql(client, container, password, sql):
    if not re.fullmatch(r"[a-f0-9]+", container):
        raise RuntimeError("Invalid container ID")
    command = "docker exec -i " + container + " sh -c " + shlex.quote(
        "IFS= read -r MYSQL_PWD; export MYSQL_PWD; exec mysql --protocol=SOCKET -uroot --batch --raw --skip-column-names")
    stdin, out, err = client.exec_command(command, timeout=30)
    stdin.write(password + "\n" + sql + "\n")
    stdin.flush()
    stdin.channel.shutdown_write()
    result, error = out.read().decode("utf-8"), err.read().decode("utf-8")
    if out.channel.recv_exit_status() != 0:
        number = re.search(r"ERROR\s+(\d+)", error)
        raise RuntimeError("Container MySQL operation failed, error=" + (number[1] if number else "unknown"))
    return result


def env_map(spec):
    return dict(item.split("=", 1) for item in spec.get("Env", []) if "=" in item)


def read_file(client, path):
    with client.open_sftp() as sftp:
        with sftp.open(path, "rb") as source:
            return source.read().decode("utf-8")


def discover(cfg, stack):
    clients = {label: stack.enter_context(cloud.connect(cfg, label)) for label in ("SWARM_MANAGER_HOST", "SWARM_WORKER_HOST")}
    manager = clients["SWARM_MANAGER_HOST"]
    services = json.loads(cloud.remote(manager, "docker service inspect $(docker service ls -q)"))
    databases = [s for s in services if "mysql" in s["Spec"]["TaskTemplate"]["ContainerSpec"]["Image"].lower()]
    if len(databases) != 1:
        raise RuntimeError("Expected exactly one MySQL service")
    database = databases[0]
    name = database["Spec"]["Name"]
    found = []
    for label, client in clients.items():
        for container in cloud.remote(client, "docker ps --filter label=com.docker.swarm.service.name=" + shlex.quote(name) + " --format '{{.ID}}'").splitlines():
            found.append((label, container))
    if len(found) != 1:
        raise RuntimeError("Expected one running MySQL task")
    label, container = found[0]
    initial = env_map(database["Spec"]["TaskTemplate"]["ContainerSpec"]).get("MYSQL_ROOT_PASSWORD")
    if not initial:
        raise RuntimeError("Initial credential source is not a literal environment value")
    try:
        mysql(clients[label], container, initial, "SELECT CURRENT_USER(), VERSION();")
        current = initial
    except RuntimeError:
        current = cfg.get("CLOUD_DB_PASSWORD", "")
        mysql(clients[label], container, current, "SELECT CURRENT_USER(), VERSION();")
    roots = mysql(clients[label], container, current, "SELECT host FROM mysql.user WHERE user='root' ORDER BY host;").splitlines()
    if set(roots) != {"%", "localhost"}:
        raise RuntimeError("Root account sources differ from the two authorized accounts")
    data_mounts = [m for m in database["Spec"]["TaskTemplate"]["ContainerSpec"].get("Mounts", []) if m["Target"] == "/var/lib/mysql"]
    if len(data_mounts) != 1 or not data_mounts[0].get("Source"):
        raise RuntimeError("Persistent MySQL data mount must be confirmed before rotation")
    print("MySQL authenticated through socket; two root accounts and persistent data mount confirmed")
    print("MySQL placement=" + label + "; service=" + name)
    documents = []
    dependencies = []
    for node, client in clients.items():
        paths = cloud.remote(client, "find /root -maxdepth 9 -type f \\( -iname '*.yml' -o -iname '*.yaml' \\) -path '*productdemojpa*' 2>/dev/null").splitlines()
        for path in paths:
            raw = read_file(client, path)
            document = yaml.safe_load(raw)
            if not isinstance(document, dict):
                continue
            documents.append({"node": node, "path": path, "raw": raw, "document": document})
            if "services" in document:
                print("Deployment YAML: " + node + ": " + path)
            datasource = document.get("spring", {}).get("datasource", {})
            if datasource:
                print("Datasource YAML: " + node + ": " + path + "; username=" + str(datasource.get("username", "[nested]")))
    for service in services:
        spec = service["Spec"]["TaskTemplate"]["ContainerSpec"]
        print("Service " + service["Spec"]["Name"] + ": environment keys=" + ",".join(env_map(spec)))
        if service["ID"] != database["ID"]:
            for node, client in clients.items():
                containers = cloud.remote(client, "docker ps --filter label=com.docker.swarm.service.name=" + shlex.quote(service["Spec"]["Name"]) + " --format '{{.ID}}'").splitlines()
                for app_container in containers:
                    runtime = json.loads(cloud.remote(client, "docker inspect " + app_container))[0]
                    print("Application runtime working directory=" + str(runtime["Config"].get("WorkingDir")))
                    print("Application mount targets=" + ",".join(m["Destination"] for m in runtime.get("Mounts", [])))
                    paths = cloud.remote(client, "docker exec " + app_container + " sh -c " + shlex.quote("find /app -maxdepth 4 -type f \\( -name '*.yml' -o -name '*.yaml' -o -name '*.properties' -o -name '*.jar' \\) 2>/dev/null")).splitlines()
                    print("Application runtime configuration artifacts=" + ",".join(paths))
                    pid = int(runtime["State"]["Pid"])
                    program = "import json,zipfile; z=zipfile.ZipFile('/proc/" + str(pid) + "/root/app/app.jar'); print(json.dumps({n:z.read(n).decode('utf-8') for n in z.namelist() if n.startswith('BOOT-INF/classes/application') and n.endswith(('.yaml','.yml','.properties'))}))"
                    embedded = json.loads(cloud.remote(client, "python3 -c " + shlex.quote(program)))
                    effective_username = None
                    effective_password = None
                    for resource, content in embedded.items():
                        if resource.endswith((".yaml", ".yml")):
                            app_config = yaml.safe_load(content)
                            username = app_config.get("spring", {}).get("datasource", {}).get("username", "[unset]")
                            effective_username = username
                            effective_password = app_config.get("spring", {}).get("datasource", {}).get("password")
                            print("Running application embedded datasource user=" + str(username))
                    arguments = " ".join(runtime["Config"].get("Entrypoint", []) + runtime["Config"].get("Cmd", []))
                    overrides = dict(re.findall(r"(?:-D|--)(spring\.datasource\.[a-zA-Z0-9_.-]+)=([^\s]+)", arguments))
                    print("Application datasource override keys=" + ",".join(overrides))
                    effective_username = overrides.get("spring.datasource.username", effective_username)
                    effective_password = overrides.get("spring.datasource.password", effective_password)
                    runtime_env = env_map(runtime["Config"])
                    effective_username = runtime_env.get("SPRING_DATASOURCE_USERNAME", effective_username)
                    effective_password = runtime_env.get("SPRING_DATASOURCE_PASSWORD", effective_password)
                    if effective_username != "demouser":
                        raise RuntimeError("Dependent application effective datasource account requires further inspection")
                    print("Application effective datasource user=demouser; root rotation does not change its credential")
                    dependencies.append({"name": service["Spec"]["Name"], "username": effective_username, "password": effective_password})
    return clients, services, database, label, container, current, documents, dependencies


def yaml_plans(documents, password):
    plans = []
    for item in documents:
        document = item["document"]
        expected = copy.deepcopy(document)
        root = yaml.compose(item["raw"])
        def child(node, key):
            if not isinstance(node, yaml.MappingNode):
                raise RuntimeError("Expected YAML mapping while locating credential")
            return next(value for name, value in node.value if name.value == key)
        replacements = []
        for name, spec in document.get("services", {}).items():
            if "mysql" not in str(spec.get("image", "")).lower():
                continue
            node = child(child(child(root, "services"), name), "environment")
            env = spec["environment"]
            if isinstance(env, dict) and "MYSQL_ROOT_PASSWORD" in env:
                scalar = child(node, "MYSQL_ROOT_PASSWORD")
                expected["services"][name]["environment"]["MYSQL_ROOT_PASSWORD"] = password
                replacement = password
            elif isinstance(env, list):
                indexes = [i for i, value in enumerate(env) if value.startswith("MYSQL_ROOT_PASSWORD=")]
                if len(indexes) != 1:
                    raise RuntimeError("Ambiguous YAML credential environment list")
                index = indexes[0]
                scalar = node.value[index]
                replacement = "MYSQL_ROOT_PASSWORD=" + password
                expected["services"][name]["environment"][index] = replacement
            else:
                raise RuntimeError("YAML MySQL credential source requires explicit review")
            replacements.append((scalar.start_mark.index, scalar.end_mark.index, json.dumps(replacement)))
        if not replacements:
            continue
        updated = item["raw"]
        for start, end, replacement in sorted(replacements, reverse=True):
            updated = updated[:start] + replacement + updated[end:]
        if yaml.safe_load(updated) != expected:
            raise RuntimeError("YAML change modified more than the intended credential")
        plans.append({**item, "updated": updated})
    if len(plans) != 4 or {p["node"] for p in plans} != {"SWARM_MANAGER_HOST", "SWARM_WORKER_HOST"}:
        raise RuntimeError("Expected base and monitoring deployment YAML on each node")
    return plans


def tls_connection(cfg, node, password, user="root"):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    connection = pymysql.connect(host=cfg[node], port=int(cfg["CLOUD_DB_PORT"]), user=user, password=password,
                                 ssl=context, connect_timeout=6, read_timeout=10, write_timeout=10, autocommit=True)
    with connection.cursor() as cursor:
        cursor.execute("SHOW SESSION STATUS LIKE 'Ssl_cipher'")
        if not cursor.fetchone()[1]:
            connection.close()
            raise RuntimeError("MySQL TLS was not negotiated")
    return connection


def updated_service_spec(service, password):
    spec = copy.deepcopy(service["Spec"])
    container = spec["TaskTemplate"]["ContainerSpec"]
    old = container.get("Env", [])
    if sum(value.startswith("MYSQL_ROOT_PASSWORD=") for value in old) != 1:
        raise RuntimeError("Unexpected runtime initial credential configuration")
    container["Env"] = ["MYSQL_ROOT_PASSWORD=" + password if value.startswith("MYSQL_ROOT_PASSWORD=") else value for value in old]
    return spec


def update_service_environment(manager, service, password):
    spec = updated_service_spec(service, password)
    # Docker's API accepts the secret in stdin; it never appears in process args.
    endpoint = "http://localhost/v1.41/services/" + service["ID"] + "/update?version=" + str(service["Version"]["Index"])
    command = "curl --unix-socket /var/run/docker.sock -sS -X POST -H 'Content-Type: application/json' --data-binary @- -w '\\n%{http_code}' " + shlex.quote(endpoint)
    stdin, out, err = manager.exec_command(command, timeout=30)
    stdin.write(json.dumps(spec))
    stdin.flush()
    stdin.channel.shutdown_write()
    response = out.read().decode("utf-8")
    err.read()
    if out.channel.recv_exit_status() != 0 or response.rsplit("\n", 1)[-1] != "200":
        raise RuntimeError("Swarm initial credential update failed; actual database credential is already updated")
    print("Swarm MySQL initial environment updated; waiting for its task to restart with the existing data mount")


def rotate(cfg, state):
    clients, services, database, node, container, current, documents, dependencies = state
    password = cfg.get("CLOUD_DB_PASSWORD_NEXT", "")
    if not password or any(character in password for character in "\r\n\\") or password != password.strip():
        raise RuntimeError("Configure the explicitly authorized credential in CLOUD_DB_PASSWORD_NEXT")
    plans = yaml_plans(documents, password)
    for dependency in dependencies:
        if not dependency["password"]:
            raise RuntimeError("Dependent application's effective password source is unresolved")
        with tls_connection(cfg, "SWARM_MANAGER_HOST", dependency["password"], dependency["username"]) as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT 1")
                if cursor.fetchone()[0] != 1:
                    raise RuntimeError("Dependent application credential baseline failed")
        print(dependency["name"] + ": independent datasource credential baseline authenticated over TLS")
    client = clients[node]
    before = mysql(client, container, current, "SELECT COUNT(*) FROM information_schema.SCHEMATA; SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA NOT IN ('mysql','sys','information_schema','performance_schema');")
    print("Prepared four YAML edits; parsed validation confirms only MYSQL_ROOT_PASSWORD changes")
    staged = []
    changed = False
    try:
        for plan in plans:
            with clients[plan["node"]].open_sftp() as sftp:
                with sftp.open(plan["path"], "rb") as source:
                    if source.read().decode("utf-8") != plan["raw"]:
                        raise RuntimeError("Remote YAML changed during preparation; rotation not started")
                temporary = plan["path"] + ".melonhub-rotation.tmp"
                with sftp.open(temporary, "wx") as target:
                    sftp.chmod(temporary, 0o600)
                    target.write(plan["updated"])
                staged.append((plan, temporary))
                with sftp.open(temporary, "rb") as check:
                    if check.read().decode("utf-8") != plan["updated"]:
                        raise RuntimeError("Staged remote YAML verification failed")
        literal = "'" + password.replace("'", "''") + "'"
        sql = "SET SESSION sql_mode='NO_BACKSLASH_ESCAPES'; ALTER USER 'root'@'localhost' IDENTIFIED BY " + literal + ", 'root'@'%' IDENTIFIED BY " + literal + ";"
        mysql(client, container, current, sql)
        changed = True
        print("Actual credentials for root@localhost and root@% updated in one ALTER USER statement")
        mysql(client, container, password, "SELECT CURRENT_USER();")
        print("New container-local root credential authenticated")
        for label in ("SWARM_MANAGER_HOST", "SWARM_WORKER_HOST"):
            with tls_connection(cfg, label, password) as connection:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT CURRENT_USER()")
                    if cursor.fetchone()[0] != "root@%":
                        raise RuntimeError("Unexpected remote root account matched")
            print(label + ": new remote root credential authenticated with TLS")
        for plan, temporary in staged:
            with clients[plan["node"]].open_sftp() as sftp:
                mode = sftp.stat(plan["path"]).st_mode & 0o777
                sftp.chmod(temporary, mode)
                sftp.posix_rename(temporary, plan["path"])
                with sftp.open(plan["path"], "rb") as check:
                    if check.read().decode("utf-8") != plan["updated"]:
                        raise RuntimeError("Updated deployment YAML readback verification failed")
            print("Deployment YAML synchronized: " + plan["node"] + ": " + Path(plan["path"]).name)
        updates = {"CLOUD_DB_PASSWORD": password, "CLOUD_DB_PASSWORD_NEXT": ""}
        if cfg.get("TEST_DB_PASSWORD"):
            updates["TEST_DB_PASSWORD"] = password
        cloud.save_configuration(updates)
        print("Ignored local .env synchronized")
        # Preserve complete existing service configuration, including data mounts,
        # placement constraints, image digest, networks and published ports.
        update_service_environment(clients["SWARM_MANAGER_HOST"], database, password)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            refreshed = json.loads(cloud.remote(clients["SWARM_MANAGER_HOST"], "docker service inspect " + database["ID"]))[0]
            update = refreshed.get("UpdateStatus", {}).get("State", "")
            if update in ("paused", "rollback_started", "rollback_paused", "rollback_completed"):
                raise RuntimeError("MySQL service rollout did not complete; inspect service status")
            if update == "completed":
                break
            time.sleep(2)
        else:
            raise RuntimeError("Timed out waiting for MySQL task rollout")
        fresh_containers = cloud.remote(client, "docker ps --filter label=com.docker.swarm.service.name=" + shlex.quote(database["Spec"]["Name"]) + " --format '{{.ID}}'").splitlines()
        if len(fresh_containers) != 1:
            raise RuntimeError("Expected one replacement MySQL task on its existing node")
        mysql(client, fresh_containers[0], password, "SELECT CURRENT_USER();")
        print("New container-local root credential reverified after restart")
        for label in ("SWARM_MANAGER_HOST", "SWARM_WORKER_HOST"):
            with tls_connection(cfg, label, password) as connection:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT COUNT(*) FROM information_schema.SCHEMATA")
                    schemas = cursor.fetchone()[0]
                    cursor.execute("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA NOT IN ('mysql','sys','information_schema','performance_schema')")
                    tables = cursor.fetchone()[0]
                if before.splitlines() != [str(schemas), str(tables)]:
                    raise RuntimeError("Schema/table inventory differs from pre-rotation snapshot")
            print(label + ": TLS authentication reverified after restart; schema/table counts unchanged")
        for dependency in dependencies:
            with tls_connection(cfg, "SWARM_MANAGER_HOST", dependency["password"], dependency["username"]) as connection:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT 1")
            print(dependency["name"] + ": independent datasource credential reverified over TLS after restart")
        summary = cloud.remote(clients["SWARM_MANAGER_HOST"], "docker service ls --format '{{json .}}'")
        for line in summary.splitlines():
            service = json.loads(line)
            print("Service " + service["Name"] + " replicas=" + service["Replicas"])
        print("Root credential rotation and configuration synchronization completed")
    finally:
        if changed:
            # Keep the working credential recoverable if a subsequent sync fails.
            cloud.save_configuration({"CLOUD_DB_PASSWORD": password})
        for plan, temporary in staged:
            with clients[plan["node"]].open_sftp() as sftp:
                try:
                    sftp.remove(temporary)
                except OSError:
                    pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("prepare", "rotate"))
    args = parser.parse_args()
    with ExitStack() as stack:
        cfg = cloud.configuration()
        state = discover(cfg, stack)
        if args.action == "rotate":
            rotate(cfg, state)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("Rotation operation stopped: " + type(exc).__name__)
        if isinstance(exc, RuntimeError):
            print(str(exc))
        raise SystemExit(1)
