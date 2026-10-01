import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from provenance import verify_provenance


def provenance_fixture(directory):
    labels = {"org.opencontainers.image.revision": "a" * 40,
              "io.switchboard.source.tree": "b" * 40, "io.switchboard.source.clean": "true"}
    (directory / "environment.txt").write_text(f"git_commit={'a'*40}\ngit_index_tree={'b'*40}\ngit_dirty_count=0\n")
    (directory / "git-status.txt").write_text("CLEAN\n")
    blobs = directory / "image-blobs"
    blobs.mkdir()

    def blob(value):
        raw = json.dumps(value).encode()
        digest = "sha256:" + hashlib.sha256(raw).hexdigest()
        (blobs / (digest.split(":")[1] + ".json")).write_bytes(raw)
        return digest

    builds, roles = [], {}
    for role in ("distribution", "control-plane", "sample-service"):
        config = blob({"config": {"Labels": labels}, "role": role})
        manifest = blob({"config": {"digest": config}})
        index = blob({"manifests": [{"digest": manifest, "platform": {"os": "linux", "architecture": "arm64"}}]})
        tag = f"switchboard/{role}:phase8"
        builds.append(dict(tag=tag, imageId=index, labels=labels))
        roles[role] = dict(node="kind-test-worker", capturedAt="2026-10-01T00:00:00Z", image=tag,
                           imageId=config, configDigest=config, buildImageId=index,
                           descriptorChain=[index, manifest, config], labels=labels)
    (directory / "build-images.json").write_text(json.dumps(builds))

    def stage(filename, role, names):
        (directory / filename).write_text(json.dumps([dict(copy.deepcopy(roles[role]), pod=name, uid="uid-"+name) for name in names]))

    stage("distribution-images.json", "distribution", ["d0", "d1"])
    stage("control-plane-images.json", "control-plane", ["c0", "c1"])
    stage("rolling-update-distribution-images.json", "distribution", ["d2", "d3"])
    stage("pod-loss-distribution-images.json", "distribution", ["d3", "d4"])
    for scenario in ("rolling-update", "pod-loss"):
        stage(f"{scenario}-probe-image.json", "sample-service", ["p-"+scenario])
    (directory / "pod-loss-fault.txt").write_text("scenario=pod-loss\nvictim=d2\n")


class ProvenanceTest(unittest.TestCase):
    def test_verifies_hashed_build_to_runtime_and_replacement_chain(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            provenance_fixture(directory)
            self.assertEqual("pass", verify_provenance(directory)["runtimeImageProvenance"])

    def test_accepts_import_index_only_when_linked_to_expected_image(self):
        for correct in (True, False):
            with self.subTest(correct=correct), tempfile.TemporaryDirectory() as name:
                directory = Path(name)
                provenance_fixture(directory)
                path = directory / "control-plane-images.json"
                records = json.loads(path.read_text())
                built_root = records[0]["buildImageId"]
                wrapper = {"manifests": [{"digest": built_root, "annotations": {
                    "io.containerd.image.name": "docker.io/" + (records[0]["image"] if correct else "switchboard/wrong:phase8")}}]}
                raw = json.dumps(wrapper).encode()
                digest = "sha256:" + hashlib.sha256(raw).hexdigest()
                (directory / "image-blobs" / (digest.split(":")[1] + ".json")).write_bytes(raw)
                for record in records:
                    record["imageId"] = "docker.io/library/import-test@" + digest
                    record["runtimeDescriptorChain"] = [digest, built_root]
                path.write_text(json.dumps(records))
                if correct:
                    self.assertEqual("pass", verify_provenance(directory)["runtimeImageProvenance"])
                else:
                    with self.assertRaisesRegex(AssertionError, "unlinked Pod import index"):
                        verify_provenance(directory)

    def test_rejects_untrusted_or_missing_runtime_identity(self):
        for corruption in ("stale-build", "stale-replacement", "label-tamper", "blob-tamper", "unlinked-descriptor", "missing-replacement"):
            with self.subTest(corruption=corruption), tempfile.TemporaryDirectory() as name:
                directory = Path(name)
                provenance_fixture(directory)
                path = directory / "pod-loss-distribution-images.json"
                records = json.loads(path.read_text())
                if corruption == "stale-build":
                    build_path = directory / "build-images.json"
                    builds = json.loads(build_path.read_text())
                    builds[0]["labels"]["org.opencontainers.image.revision"] = "c" * 40
                    build_path.write_text(json.dumps(builds))
                elif corruption == "stale-replacement":
                    records[1]["imageId"] = "sha256:" + "c" * 64
                elif corruption == "label-tamper":
                    records[1]["labels"]["org.opencontainers.image.revision"] = "c" * 40
                elif corruption == "blob-tamper":
                    digest = records[1]["configDigest"]
                    (directory / "image-blobs" / (digest.split(":")[1] + ".json")).write_text('{}')
                elif corruption == "unlinked-descriptor":
                    records[1]["descriptorChain"].pop(1)
                else:
                    records[1] = records[0]
                path.write_text(json.dumps(records))
                with self.assertRaises(AssertionError):
                    verify_provenance(directory)


if __name__ == "__main__":
    unittest.main()
