"""Deploy MelonHub and forward its loopback API through an authenticated SSH connection."""
import argparse
import hashlib
import json
from pathlib import Path
import select
import shlex
import socketserver
import sys
import time

import paramiko

ROOT = Path(__file__).resolve().parents[1]
REMOTE_ROOT = "/opt/melonhub"
CONTAINER = "melonhub-backend"
LABEL = "com.melonhub.role=backend"


def configuration():
    values = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8-sig").splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


class PinHost(paramiko.MissingHostKeyPolicy):
    def missing_host_key(self, client, hostname, key):
        known = ROOT / ".local" / "cloud_known_hosts"
        known.parent.mkdir(parents=True, exist_ok=True)
        client.get_host_keys().add(paramiko.HostKeys.hash_host(hostname), key.get_name(), key)
        client.save_host_keys(str(known))
        print("SSH first-contact host key pinned", flush=True)


def connect(cfg):
    client = paramiko.SSHClient()
    known = ROOT / ".local" / "cloud_known_hosts"
    if known.exists():
        client.load_host_keys(str(known))
    client.set_missing_host_key_policy(PinHost())
    host = cfg.get("BACKEND_SSH_HOST") or cfg.get("SWARM_WORKER_HOST")
    username = cfg.get("BACKEND_SSH_USERNAME") or cfg.get("SWARM_SSH_USERNAME")
    password = cfg.get("BACKEND_SSH_PASSWORD") or cfg.get("SWARM_SSH_PASSWORD")
    if not all((host, username, password)):
        raise RuntimeError("Configure BACKEND_SSH_* or the existing SWARM_SSH_* fields in .env")
    client.connect(host, port=int(cfg.get("BACKEND_SSH_PORT") or cfg.get("SWARM_SSH_PORT", "22")),
                   username=username, password=password, timeout=10, auth_timeout=10,
                   banner_timeout=10, look_for_keys=False, allow_agent=False)
    client.get_transport().set_keepalive(15)
    return client


def remote(client, command, timeout=30):
    _, out, err = client.exec_command(command, timeout=timeout)
    output = out.read().decode("utf-8")
    err.read()
    if out.channel.recv_exit_status() != 0:
        raise RuntimeError("Server command failed; configuration and remote output withheld")
    return output.strip()


def port(cfg):
    value = int(cfg.get("BACKEND_REMOTE_PORT", "18080"))
    if not 1024 <= value <= 65535:
        raise RuntimeError("BACKEND_REMOTE_PORT must be between 1024 and 65535")
    return value


def inspect_container(client):
    raw = remote(client, "docker container ls -aq --filter name=^melonhub-backend$")
    if not raw:
        return None
    details = json.loads(remote(client, "docker inspect " + CONTAINER))[0]
    if details["Config"].get("Labels", {}).get("com.melonhub.role") != "backend":
        raise RuntimeError("Refusing to manage a container without the MelonHub backend label")
    return details


def health(client, api_port):
    command = "curl --fail --silent --max-time 5 http://127.0.0.1:" + str(api_port) + "/api/health"
    result = json.loads(remote(client, command))
    if result.get("status") != "UP" or result.get("database") != "UP" or result.get("model") != "RBAC0":
        raise RuntimeError("Server backend or its database is not healthy")
    return {key: result.get(key) for key in ("status", "database", "cache", "model")}


def upload_configuration(client, cfg):
    required = ("CLOUD_DB_HOST", "CLOUD_DB_NAME", "CLOUD_DB_USERNAME", "CLOUD_DB_PASSWORD", "ADMIN_USERNAME", "ADMIN_PASSWORD")
    if any(not cfg.get(key) for key in required) or cfg["CLOUD_DB_NAME"] != "melonhub_rbac":
        raise RuntimeError("Configure the dedicated cloud production database and administrator in .env")
    names = set(required) | {
        "CLOUD_DB_PORT", "APP_RATE_LIMIT", "DB_POOL_SIZE", "AUDIT_RETENTION_DAYS", "ADMIN_IDLE_MINUTES",
        "HTTP_MAX_THREADS", "GLOBAL_RATE_LIMIT", "WEBHOOK_DELIVERY_ENABLED", "WEBHOOK_RETENTION_DAYS",
        "CACHE_ENABLED", "REDIS_HOST", "REDIS_PORT", "REDIS_PASSWORD", "REDIS_DATABASE",
    }
    values = {key: cfg[key] for key in names if cfg.get(key)}
    values.update(SPRING_PROFILES_ACTIVE="cloud", SEED_DEMO="false", ADMIN_PASSWORD_RESET="false",
                  SERVER_ADDRESS="0.0.0.0", SERVER_PORT="8080", MANAGEMENT_PORT="8081")
    values.setdefault("CACHE_ENABLED", "false")
    if any("\n" in value or "\r" in value for value in values.values()):
        raise RuntimeError("Multiline environment values are not supported")
    with client.open_sftp() as sftp:
        with sftp.open(REMOTE_ROOT + "/.env", "w") as target:
            target.chmod(0o600)
            target.write("".join(key + "=" + value + "\n" for key, value in sorted(values.items())))


def deploy(client, cfg):
    jar = ROOT / "backend/target/rbac-service-1.0.0.jar"
    if not jar.is_file():
        raise RuntimeError("Build backend/target/rbac-service-1.0.0.jar before deploying")
    previous = inspect_container(client)
    api_port = port(cfg)
    if not previous:
        listeners = remote(client, "ss -ltnH")
        if any(line.split()[3].rsplit(":", 1)[-1] == str(api_port) for line in listeners.splitlines()):
            raise RuntimeError("The requested server API port belongs to another service")
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    release = REMOTE_ROOT + "/releases/" + digest + ".jar"
    remote(client, "install -d -m 700 " + REMOTE_ROOT + "; install -d -m 755 " + REMOTE_ROOT + "/releases")
    with client.open_sftp() as sftp:
        sftp.put(str(jar), release)
        sftp.chmod(release, 0o644)
    actual = remote(client, "sha256sum " + release).split()[0]
    if actual != digest:
        raise RuntimeError("Uploaded artifact checksum does not match")
    image = cfg.get("BACKEND_IMAGE", "eclipse-temurin:21-jre")
    available = remote(client, "docker image ls -q " + shlex.quote(image))
    if not available:
        print("Pulling the backend Java runtime image", flush=True)
        remote(client, "docker pull " + shlex.quote(image), timeout=240)
    image_info = json.loads(remote(client, "docker image inspect " + shlex.quote(image)))[0]
    image_id = image_info["Id"]
    rollback = CONTAINER + "-rollback"
    if previous and remote(client, "docker container ls -aq --filter name=^melonhub-backend-rollback$"):
        raise RuntimeError("A rollback container already exists; inspect it before another deployment")
    upload_configuration(client, cfg)
    if previous:
        remote(client, "docker stop " + CONTAINER + " && docker rename " + CONTAINER + " " + rollback)
    command = ["docker", "run", "-d", "--name", CONTAINER, "--label", LABEL,
               "--label", "com.melonhub.artifact=" + digest, "--restart", "unless-stopped",
               "--memory", "512m", "--memory-swap", "512m", "--cpus", "1",
               "--read-only", "--tmpfs", "/tmp:rw,nosuid,size=64m", "--cap-drop", "ALL",
               "--security-opt", "no-new-privileges:true", "--user", "10001:10001",
               "--log-opt", "max-size=10m", "--log-opt", "max-file=3",
               "--env-file", REMOTE_ROOT + "/.env", "-p", "127.0.0.1:" + str(api_port) + ":8080",
               "-v", release + ":/melonhub/backend.jar:ro", "-w", "/melonhub", "--entrypoint", "java", image_id,
               "-Xms64m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m",
               "-XX:ReservedCodeCacheSize=64m", "-XX:MaxDirectMemorySize=32m", "-Xss512k",
               "-jar", "/melonhub/backend.jar"]
    try:
        remote(client, shlex.join(command))
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            try:
                state = health(client, api_port)
                print(json.dumps({"artifactSha256": digest, "health": state}), flush=True)
                if previous:
                    remote(client, "docker rm " + rollback)
                return
            except (RuntimeError, ValueError):
                time.sleep(2)
        raise RuntimeError("New backend did not become healthy within 100 seconds")
    except Exception:
        if inspect_container(client):
            remote(client, "docker rm -f " + CONTAINER)
        if previous:
            remote(client, "docker rename " + rollback + " " + CONTAINER + " && docker start " + CONTAINER)
        raise


def tunnel(client, cfg):
    health(client, port(cfg))
    transport = client.get_transport()

    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            channel = None
            try:
                channel = transport.open_channel("direct-tcpip", ("127.0.0.1", port(cfg)), self.client_address, timeout=10)
                while True:
                    readable, _, _ = select.select([self.request, channel], [], [], 15)
                    if not transport.is_active():
                        return
                    for source in readable:
                        data = source.recv(65536)
                        if not data:
                            return
                        (channel if source is self.request else self.request).sendall(data)
            except (OSError, EOFError, paramiko.SSHException):
                pass
            finally:
                if channel:
                    channel.close()

    class Server(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

    with Server(("127.0.0.1", 8080), Handler) as server:
        server.timeout = 1
        print("Server backend connected through SSH at local port 8080", flush=True)
        while transport.is_active():
            server.handle_request()
    raise RuntimeError("SSH connection closed; restart the local launcher to reconnect")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("deploy", "status", "start", "stop", "restart", "tunnel"))
    args = parser.parse_args()
    cfg = configuration()
    with connect(cfg) as client:
        if args.action == "deploy":
            deploy(client, cfg)
        elif args.action == "tunnel":
            tunnel(client, cfg)
        else:
            details = inspect_container(client)
            if not details:
                raise RuntimeError("No managed backend is deployed")
            if args.action != "status":
                remote(client, "docker " + args.action + " " + CONTAINER)
            details = inspect_container(client)
            result = {"container": CONTAINER, "running": details["State"]["Running"],
                      "restartPolicy": details["HostConfig"]["RestartPolicy"]["Name"],
                      "artifactSha256": details["Config"]["Labels"].get("com.melonhub.artifact")}
            if args.action == "status" and result["running"]:
                result["health"] = health(client, port(cfg))
            print(json.dumps(result), flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Connection errors can contain hosts or credentials; only our own safe messages are exposed.
        print(str(error) if type(error) is RuntimeError else "Server operation failed: " + type(error).__name__, file=sys.stderr)
        sys.exit(1)
