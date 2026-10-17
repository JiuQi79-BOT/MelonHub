"""Credential rotation plan tests; no network, real credentials or database writes."""
import copy
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("rotation", Path(__file__).resolve().parents[1] / "rotate-cloud-password.py")
rotation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rotation)


class RotationPlanTest(unittest.TestCase):
    def documents(self, raw):
        return [{"node": node, "path": str(index), "raw": raw, "document": rotation.yaml.safe_load(raw)}
                for node in ("SWARM_MANAGER_HOST", "SWARM_WORKER_HOST") for index in range(2)]

    def test_mapping_preserves_comments_and_unrelated_settings(self):
        raw = "# deployment\nservices:\n  mysql:\n    image: mysql:9\n    environment:\n      MYSQL_ROOT_PASSWORD: original # credential\n      OTHER: value\n    volumes: [data:/var/lib/mysql]\n"
        documents = self.documents(raw)
        before = copy.deepcopy(documents)
        plans = rotation.yaml_plans(documents, "new'\\value")
        self.assertEqual(documents, before)
        for plan in plans:
            self.assertIn("# deployment", plan["updated"])
            self.assertIn("# credential", plan["updated"])
            self.assertEqual(rotation.yaml.safe_load(plan["updated"])["services"]["mysql"]["environment"]["MYSQL_ROOT_PASSWORD"], "new'\\value")

    def test_sequence_environment_is_supported(self):
        raw = "services:\n  mysql:\n    image: mysql:9\n    environment:\n      - MYSQL_ROOT_PASSWORD=original\n      - OTHER=value\n"
        plans = rotation.yaml_plans(self.documents(raw), "replacement")
        self.assertEqual(rotation.yaml.safe_load(plans[0]["updated"])["services"]["mysql"]["environment"], ["MYSQL_ROOT_PASSWORD=replacement", "OTHER=value"])

    def test_missing_node_copy_is_rejected_before_mutation(self):
        raw = "services:\n  mysql:\n    image: mysql:9\n    environment:\n      MYSQL_ROOT_PASSWORD: original\n"
        with self.assertRaises(RuntimeError):
            rotation.yaml_plans(self.documents(raw)[:2], "replacement")

    def test_swarm_spec_only_changes_root_initial_environment(self):
        service = {"Spec": {"Name": "lab_mysql", "TaskTemplate": {"ContainerSpec": {"Image": "mysql@sha256:fixed", "Env": ["MYSQL_ROOT_PASSWORD=original", "OTHER=value"], "Mounts": [{"Target": "/var/lib/mysql", "Source": "/existing/data"}]}, "Placement": {"Constraints": ["node.labels.mysql==yes"]}}, "EndpointSpec": {"Ports": [{"PublishedPort": 3306, "TargetPort": 3306}]}}}
        before = copy.deepcopy(service)
        expected = copy.deepcopy(service["Spec"])
        expected["TaskTemplate"]["ContainerSpec"]["Env"][0] = "MYSQL_ROOT_PASSWORD=replacement"
        self.assertEqual(rotation.updated_service_spec(service, "replacement"), expected)
        self.assertEqual(service, before)


if __name__ == "__main__":
    unittest.main()
