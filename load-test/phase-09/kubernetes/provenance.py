"""Verify source labels through hashed OCI index -> manifest -> runtime config links."""
import hashlib
import json
from pathlib import Path
import re
import sys

if not __debug__:
    raise RuntimeError("Evidence verification requires assertions enabled")


def verify_provenance(directory, complete=True):
    environment = dict(line.split("=", 1) for line in (directory / "environment.txt").read_text().splitlines() if "=" in line)
    commit, tree = environment["git_commit"], environment["git_index_tree"]
    assert re.fullmatch(r"[0-9a-f]{40}", commit) and re.fullmatch(r"[0-9a-f]{40}", tree)
    assert environment["git_dirty_count"] == "0" and (directory / "git-status.txt").read_text().strip() == "CLEAN"
    builds = {b["tag"]: b for b in json.loads((directory / "build-images.json").read_text())}
    assert set(builds) == {f"switchboard/{role}:phase8" for role in ("control-plane", "distribution", "sample-service")}

    def labels(value):
        assert value["org.opencontainers.image.revision"] == commit, "image revision does not match source"
        assert value["io.switchboard.source.tree"] == tree, "image source tree mismatch"
        assert value["io.switchboard.source.clean"] == "true", "image built from dirty source"

    for build in builds.values():
        labels(build["labels"])

    def blob(digest):
        assert re.fullmatch(r"sha256:[0-9a-f]{64}", digest), "invalid image digest"
        raw = (directory / "image-blobs" / (digest.split(":")[1] + ".json")).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == digest.split(":")[1], "OCI blob hash mismatch"
        return json.loads(raw)

    def stage(filename, role, count):
        records = json.loads((directory / filename).read_text())
        assert len(records) == count and len({r["uid"] for r in records}) == count, "missing/duplicate runtime Pod"
        for record in records:
            assert record["pod"] and record["node"] and record["capturedAt"]
            assert record["image"] == f"switchboard/{role}:phase8", "unexpected runtime image"
            build = builds[record["image"]]
            chain = record["descriptorChain"]
            assert len(chain) >= 2 and chain[0] == build["imageId"] == record["buildImageId"]
            for parent_digest, child_digest in zip(chain, chain[1:]):
                parent = blob(parent_digest)
                children = [m["digest"] for m in parent["manifests"]] if "manifests" in parent else [parent["config"]["digest"]]
                assert child_digest in children, "unlinked OCI descriptor"
            config = blob(chain[-1])
            assert chain[-1] == record["configDigest"]
            runtime_digest = record["imageId"].split("@")[-1].removeprefix("docker-pullable://")
            if runtime_digest not in chain:
                runtime_chain = record.get("runtimeDescriptorChain", [])
                assert runtime_chain == [runtime_digest, build["imageId"]], "Pod imageID not linked to build"
                imported = blob(runtime_digest)
                links = [m for m in imported.get("manifests", []) if m["digest"] == build["imageId"]]
                assert len(links) == 1 and links[0]["annotations"]["io.containerd.image.name"] == "docker.io/" + record["image"], "unlinked Pod import index"
            assert config["config"]["Labels"] == record["labels"], "runtime labels differ from hashed image config"
            labels(record["labels"])
        return records

    initial = stage("distribution-images.json", "distribution", 2)
    stage("control-plane-images.json", "control-plane", 2)
    if complete:
        rolling = stage("rolling-update-distribution-images.json", "distribution", 2)
        loss = stage("pod-loss-distribution-images.json", "distribution", 2)
        for scenario in ("rolling-update", "pod-loss"):
            stage(f"{scenario}-probe-image.json", "sample-service", 1)
        assert not {r["uid"] for r in initial} & {r["uid"] for r in rolling}, "rollout did not replace both Pods"
        fault = dict(line.split("=", 1) for line in (directory / "pod-loss-fault.txt").read_text().splitlines())
        assert fault["victim"] in {r["pod"] for r in rolling}
        assert fault["victim"] not in {r["pod"] for r in loss}, "victim remains in replacement fingerprint"
        assert len({r["uid"] for r in loss} - {r["uid"] for r in rolling}) == 1, "missing Pod-loss replacement"
    return dict(sourceRevision=commit, sourceTree=tree, runtimeImageProvenance="pass")


if __name__ == "__main__":
    print(json.dumps(verify_provenance(Path(sys.argv[1]), len(sys.argv) < 3 or sys.argv[2] != "preflight"), indent=2))
