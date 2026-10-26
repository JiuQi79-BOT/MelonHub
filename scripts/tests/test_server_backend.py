"""Deployment ownership, isolation and rollback tests without a real server."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("server_backend", Path(__file__).resolve().parents[1] / "server-backend.py")
backend = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backend)


class ServerBackendTest(unittest.TestCase):
    def test_unlabelled_container_is_never_managed(self):
        with patch.object(backend, "remote", side_effect=["existing", json.dumps([{"Config": {"Labels": {}}}])]):
            with self.assertRaisesRegex(RuntimeError, "without the MelonHub"):
                backend.inspect_container(None)

    def test_health_requires_database_and_rbac_model(self):
        for response in ({"status": "UP", "database": "DOWN", "model": "RBAC0"},
                         {"status": "UP", "database": "UP", "model": "other"}):
            with patch.object(backend, "remote", return_value=json.dumps(response)):
                with self.assertRaises(RuntimeError):
                    backend.health(None, 18080)

    def test_privileged_or_invalid_remote_port_is_rejected(self):
        for value in ("22", "65536", "0"):
            with self.assertRaises(RuntimeError):
                backend.port({"BACKEND_REMOTE_PORT": value})

    def test_port_collision_stops_before_upload(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            jar = root / "backend/target/rbac-service-1.0.0.jar"
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b"test artifact")
            client = MagicMock()
            with patch.object(backend, "ROOT", root), patch.object(backend, "inspect_container", return_value=None), \
                    patch.object(backend, "remote", return_value="LISTEN 0 128 127.0.0.1:18080 0.0.0.0:*"):
                with self.assertRaisesRegex(RuntimeError, "another service"):
                    backend.deploy(client, {})
            client.open_sftp.assert_not_called()

    def test_configuration_excludes_ssh_and_test_credentials(self):
        cfg = {key: "test-only" for key in ("CLOUD_DB_HOST", "CLOUD_DB_USERNAME", "CLOUD_DB_PASSWORD", "ADMIN_USERNAME", "ADMIN_PASSWORD")}
        cfg.update(CLOUD_DB_NAME="melonhub_rbac", SWARM_SSH_PASSWORD="ssh-only", TEST_DB_PASSWORD="test-db-only",
                   ADMIN_PASSWORD_RESET="true", SEED_DEMO="true")
        client = MagicMock()
        target = client.open_sftp.return_value.__enter__.return_value.open.return_value.__enter__.return_value
        backend.upload_configuration(client, cfg)
        content = target.write.call_args.args[0]
        self.assertNotIn("ssh-only", content)
        self.assertNotIn("test-db-only", content)
        self.assertIn("ADMIN_PASSWORD_RESET=false\n", content)
        self.assertIn("SEED_DEMO=false\n", content)
        target.chmod.assert_called_once_with(0o600)

    def test_unhealthy_deployment_restores_previous_container(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            jar = root / "backend/target/rbac-service-1.0.0.jar"
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b"test artifact")
            digest = hashlib.sha256(jar.read_bytes()).hexdigest()
            commands = []

            def remote(client, command, **kwargs):
                commands.append(command)
                if command.startswith("sha256sum"):
                    return digest + " file.jar"
                if command.startswith("docker image ls"):
                    return "local-image"
                if command.startswith("docker image inspect"):
                    return '[{"Id":"sha256:runtime"}]'
                return ""

            with patch.object(backend, "ROOT", root), patch.object(backend, "remote", side_effect=remote), \
                    patch.object(backend, "upload_configuration"), patch.object(backend, "inspect_container", return_value={"managed": True}), \
                    patch.object(backend.time, "monotonic", side_effect=[0, 101]):
                with self.assertRaisesRegex(RuntimeError, "did not become healthy"):
                    backend.deploy(MagicMock(), {})
            launch = next(command for command in commands if command.startswith("docker run"))
            self.assertIn("127.0.0.1:18080:8080", launch)
            self.assertIn("--restart unless-stopped", launch)
            self.assertIn("--entrypoint java", launch)
            self.assertIn("--read-only", launch)
            self.assertIn("docker rm -f melonhub-backend", commands)
            self.assertIn("docker rename melonhub-backend-rollback melonhub-backend && docker start melonhub-backend", commands)
            self.assertFalse(any(command.startswith("docker pull") for command in commands))


if __name__ == "__main__":
    unittest.main()
