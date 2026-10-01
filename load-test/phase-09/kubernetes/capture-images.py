"""Capture only image identity, OCI source labels and resources from a local kind cluster."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone


def command(*args):
    return subprocess.check_output(args)


def capture(directory, context, namespace, selector):
    builds = json.loads((directory / "build-images.json").read_text())
    pods = json.loads(command("kubectl", "--context", context, "-n", namespace,
                              "get", "pods", "-l", selector, "-o", "json"))["items"]
    assert pods, "no runtime Pods to fingerprint"
    images = {b["tag"]: b for b in builds}
    blob_dir = directory / "image-blobs"
    blob_dir.mkdir(exist_ok=True)
    result = []
    for pod in pods:
        node = pod["spec"]["nodeName"]
        statuses = {s["name"]: s for s in pod["status"]["containerStatuses"]}
        for container in pod["spec"]["containers"]:
            build = images[container["image"]]
            status = statuses[container["name"]]
            runtime = json.loads(command("docker", "exec", node, "crictl", "inspecti", container["image"]))

            def blob(digest):
                raw = command("docker", "exec", node, "ctr", "-n", "k8s.io", "content", "get", digest)
                assert digest == "sha256:" + hashlib.sha256(raw).hexdigest(), "OCI content digest mismatch"
                (blob_dir / (digest.split(":")[1] + ".json")).write_bytes(raw)
                return json.loads(raw)

            digest = build["imageId"]
            chain = [digest]
            descriptor = blob(digest)
            while "manifests" in descriptor:
                options = [m for m in descriptor["manifests"]
                           if m.get("platform", {}).get("os") == "linux"
                           and m.get("platform", {}).get("architecture") == runtime["info"]["imageSpec"]["architecture"]]
                assert len(options) == 1, "ambiguous platform manifest"
                digest = options[0]["digest"]
                chain.append(digest)
                descriptor = blob(digest)
            config_digest = descriptor["config"]["digest"]
            assert config_digest == runtime["status"]["id"], "runtime config differs from built manifest"
            config = blob(config_digest)
            chain.append(config_digest)
            runtime_digest = status["imageID"].split("@")[-1].removeprefix("docker-pullable://")
            runtime_chain = []
            if runtime_digest not in chain:
                imported = blob(runtime_digest)
                linked = [m for m in imported.get("manifests", []) if m["digest"] == build["imageId"]]
                assert len(linked) == 1, "Pod import index does not reference the built image"
                assert linked[0]["annotations"]["io.containerd.image.name"] == "docker.io/" + container["image"]
                runtime_chain = [runtime_digest, build["imageId"]]
            result.append(dict(pod=pod["metadata"]["name"], uid=pod["metadata"]["uid"], node=node,
                               capturedAt=datetime.now(timezone.utc).isoformat(), container=container["name"],
                               image=container["image"], imageId=status["imageID"], configDigest=config_digest,
                               buildImageId=build["imageId"], descriptorChain=chain, runtimeDescriptorChain=runtime_chain,
                               labels=config["config"].get("Labels", {}), resources=container.get("resources", {})))
    return result


if __name__ == "__main__":
    print(json.dumps(capture(Path(sys.argv[1]), *sys.argv[2:]), indent=2))
