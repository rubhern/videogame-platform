"""Measure the public edge's limiter memory under bounded source churn (#275 increment 6).

Runs the reviewed Caddyfile on an edge image built from caddy/Dockerfile, under the
production Compose memory, CPU and PID ceilings, with upstream stand-ins on disposable
internal networks. One test client holds many distinct IPv4 addresses or IPv6 /64s; each
source opens one TLS connection and sends one request per chosen limiter zone, at a bounded
rate of new sources. The edge's cgroup memory is sampled throughout, then while expired
limiters are swept, and a fresh source must still be served afterwards.

  measure-edge-limiter-memory.py --family 4|6 --sources N --rate R [--zones all|reads] --evidence DIR

Nothing is published to the host, no real address or credential is used, and every
container, network, volume and image is removed. The receipt is local evidence only.
"""

import argparse
import importlib.machinery
import importlib.util
import json
import pathlib
import secrets
import subprocess
import sys
import tempfile
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
loader = importlib.machinery.SourceFileLoader("edge_contract", str(ROOT / "scripts/test-public-production-runtime.py"))
spec = importlib.util.spec_from_loader(loader.name, loader)
contract = importlib.util.module_from_spec(spec)
loader.exec_module(contract)

# The stock edge image provides BusyBox `ip` for the test client's address setup only.
TOOLS_IMAGE = "caddy:2.11.7-alpine@sha256:d8542f48d34a9cf4e4c11a478865229840e87e4c96ea3f439101f31a5d35f75f"
ZONES = {
    # One request per per-source zone on both hosts: the largest limiter footprint per source.
    "all": ["shell", "navigation", "read", "search", "write",
            "identity-page", "login", "registration", "recovery", "verification", "action-link"],
    # A catalogue reader: the common anonymous pattern.
    "reads": ["shell", "read"],
}
EDGE = {"4": "10.204.0.2", "6": "fd00:204::2"}
CLIENT = {"4": "10.204.0.10", "6": "fd00:204::10"}
GATE = 'import pathlib; pathlib.Path("/gate/READY").touch()'
CLIENT_SCRIPT = r'''
import ipaddress, json, socket, ssl, sys, threading, time
sys.path.insert(0, "/probe")
import edge
family, first, count, rate, kinds = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4]), sys.argv[5].split(",")
target = {"4": "10.204.0.2", "6": "fd00:204::2"}[family]
def address(index):
    if family == "4":
        return str(ipaddress.IPv4Address("10.204.1.0") + index)
    return f"fd00:204:0:{index + 1:x}::1"
context = ssl.create_default_context(); context.check_hostname = False; context.verify_mode = ssl.CERT_NONE
results, lock = {}, threading.Lock()
def source(index):
    outcome = []
    for host in sorted({edge.KINDS[k][1] for k in kinds}):
        try:
            raw = socket.socket(socket.AF_INET if family == "4" else socket.AF_INET6)
            raw.settimeout(20)
            raw.bind((address(index), 0))
            raw.connect((target, 443))
            conn = context.wrap_socket(raw, server_hostname=host)
            reader = conn.makefile("rb")
            for kind in kinds:
                method, khost, path, body = edge.KINDS[kind]
                if khost != host:
                    continue
                head = f"{method} {path} HTTP/1.1\r\nHost: {host}\r\nContent-Length: {len(body or b'')}\r\n\r\n".encode()
                conn.sendall(head + (body or b""))
                status = int(reader.readline().split()[1])
                length = 0
                while True:
                    line = reader.readline()
                    if line in (b"\r\n", b""):
                        break
                    if line.lower().startswith(b"content-length:"):
                        length = int(line.split(b":")[1])
                reader.read(length)
                outcome.append(status)
            conn.close()
        except (OSError, ValueError, IndexError) as error:
            outcome.append(type(error).__name__)
    with lock:
        for item in outcome:
            results[str(item)] = results.get(str(item), 0) + 1
threads, started = [], time.monotonic()
for index in range(first, first + count):
    delay = started + (index - first) / rate - time.monotonic()
    if delay > 0:
        time.sleep(delay)
    while sum(t.is_alive() for t in threads[-64:]) >= 64:
        time.sleep(0.001)
    thread = threading.Thread(target=source, args=(index,)); thread.start(); threads.append(thread)
for thread in threads:
    thread.join()
print(json.dumps({"sources": count, "seconds": round(time.monotonic() - started, 1), "responses": results}))
'''


def run(command, check=True, **options):
    result = subprocess.run(command, check=False, text=True, capture_output=True, **options)
    if check and result.returncode:
        raise RuntimeError(f"{' '.join(command[:4])} failed ({result.returncode}): {result.stderr.strip()[-400:]}")
    return result


class Bench:
    def __init__(self, directory, family):
        self.directory, self.family = pathlib.Path(directory), family
        self.name = f"vgp-limiter-memory-{secrets.token_hex(4)}"
        self.network, self.volume = self.name, self.name + "-gate"
        self.containers = []

    def __enter__(self):
        return self

    def __exit__(self, *_):
        for container in reversed(self.containers):
            run(["docker", "rm", "--force", container], check=False)
        run(["docker", "network", "rm", self.network], check=False)
        run(["docker", "volume", "rm", self.volume], check=False)

    def container(self, name, arguments):
        self.containers.append(name)
        run(["docker", "run", "--detach", "--name", name, "--label", "vgp.production-test=limiter-memory", *arguments])

    def start(self):
        run(["docker", "network", "create", "--internal", "--ipv6", "--subnet", "10.204.0.0/16",
             "--subnet", "fd00:204::/48", "--label", "vgp.production-test=limiter-memory", self.network])
        run(["docker", "volume", "create", "--label", "vgp.production-test=limiter-memory", self.volume])
        run(["docker", "run", "--rm", "--network", "none", "-v", f"{self.volume}:/gate", contract.PYTHON_IMAGE,
             "python", "-I", "-c", GATE])
        config = contract.edge_test_config(contract.adapt(contract.CADDYFILE.read_text(), self.directory))
        (self.directory / "caddy.json").write_text(json.dumps(config))
        # The production edge ceilings from compose.yaml, unchanged; restarts as in production.
        self.edge = self.name + "-edge"
        self.container(self.edge, [
            "--network", self.network, "--ip", EDGE["4"], "--ip6", EDGE["6"], "--restart", "unless-stopped",
            "--memory", "128m", "--memory-swap", "128m", "--cpus", "0.5", "--pids-limit", "128",
            "--read-only", "--cap-drop", "ALL", "--cap-add", "NET_BIND_SERVICE", "--security-opt", "no-new-privileges:true",
            "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m", "-v", f"{self.volume}:/run/recovery:ro",
            # Production keeps TLS state in named volumes; a disposable internal CA needs only tmpfs.
            "--tmpfs", "/data:rw,noexec,nosuid,size=8m", "--tmpfs", "/config:rw,noexec,nosuid,size=4m",
            "-v", f"{self.directory / 'caddy.json'}:/etc/caddy/caddy.json:ro",
            contract.EDGE_TEST_IMAGE, "caddy", "run", "--config", "/etc/caddy/caddy.json"])
        self.client = self.name + "-client"
        self.container(self.client, ["--network", self.network, "--ip", CLIENT["4"], "--ip6", CLIENT["6"],
                                     "--read-only", "--user", "65534:65534", "--cap-drop", "ALL",
                                     "-v", f"{contract.PROBE}:/probe/edge.py:ro", contract.PYTHON_IMAGE, "sleep", "infinity"])
        # Upstream stand-ins last, so dynamic allocation cannot take a fixed address.
        stub = ["--network", self.network, "--read-only", "--user", "65534:65534", "--cap-drop", "ALL",
                "-v", f"{contract.PROBE}:/probe/edge.py:ro", contract.PYTHON_IMAGE]
        self.container(self.name + "-application", ["--network-alias", "application", *stub, "python", "-I", "/probe/edge.py", "serve", "application"])
        self.container(self.name + "-keycloak", ["--network-alias", "keycloak", *stub, "python", "-I", "/probe/edge.py", "serve", "keycloak"])

    def add_sources(self, count):
        """Distinct source addresses on the client, set from a helper sharing only its network."""
        if self.family == "4":
            script = (f'i=0; while [ $i -lt {count} ]; do n=$((16777216 * 10 + 65536 * 204 + 256 + i)); '
                      'ip addr add $((n >> 24 & 255)).$((n >> 16 & 255)).$((n >> 8 & 255)).$((n & 255))/16 dev eth0 || exit 1; '
                      'i=$((i + 1)); done')
        else:
            script = (f'i=1; while [ $i -le {count} ]; do ip -6 addr add fd00:204:0:$(printf %x $i)::1/48 dev eth0 || exit 1; '
                      'i=$((i + 1)); done; while ip -6 addr | grep -q tentative; do sleep 1; done')
        run(["docker", "run", "--rm", "--network", f"container:{self.client}", "--cap-drop", "ALL", "--cap-add", "NET_ADMIN",
             "--entrypoint", "sh", TOOLS_IMAGE, "-c", script], timeout=1800)

    def memory(self):
        result = run(["docker", "exec", self.edge, "cat", "/sys/fs/cgroup/memory.current", "/sys/fs/cgroup/memory.events"],
                     check=False, timeout=10)
        if result.returncode:
            return None, None
        lines = result.stdout.split()
        return int(lines[0]), int(lines[lines.index("oom_kill") + 1]) if "oom_kill" in lines else None

    def state(self):
        info = json.loads(run(["docker", "inspect", self.edge]).stdout)[0]
        return {"restarts": info["RestartCount"], "oomKilled": info["State"]["OOMKilled"], "running": info["State"]["Running"]}

    def drive(self, first, count, rate, kinds):
        result = run(["docker", "exec", self.client, "python", "-I", "-c", CLIENT_SCRIPT, self.family, str(first),
                      str(count), str(rate), ",".join(kinds)], timeout=3600)
        return json.loads(result.stdout.strip().splitlines()[-1])


def sample(bench, samples, stop):
    while not stop.is_set():
        current, oom = bench.memory()
        samples.append({"t": round(time.monotonic(), 1), "bytes": current, "oomKills": oom})
        time.sleep(0.5)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--family", choices=["4", "6"], required=True)
    parser.add_argument("--sources", type=int, required=True)
    parser.add_argument("--rate", type=float, required=True, help="new sources per second")
    parser.add_argument("--zones", choices=sorted(ZONES), default="all")
    parser.add_argument("--settle", type=int, default=150, help="seconds to observe reclamation afterwards")
    parser.add_argument("--evidence", type=pathlib.Path, required=True)
    arguments = parser.parse_args()
    if not 0 < arguments.sources <= 60000 or not 0 < arguments.rate <= 2000:
        parser.error("bounded measurement only: at most 60000 sources and 2000 new sources per second")
    arguments.evidence.mkdir(parents=True, exist_ok=False)
    contract.build_edge_image()
    try:
        with tempfile.TemporaryDirectory(prefix="edge-limiter-memory-") as directory, Bench(directory, arguments.family) as bench:
            bench.start()
            bench.add_sources(arguments.sources + 1)
            time.sleep(5)
            baseline = bench.memory()[0]
            samples, stop = [], threading.Event()
            sampler = threading.Thread(target=sample, args=(bench, samples, stop))
            started = time.monotonic()
            sampler.start()
            churn = bench.drive(0, arguments.sources, arguments.rate, ZONES[arguments.zones])
            churned = time.monotonic()
            time.sleep(arguments.settle)
            stop.set()
            sampler.join()
            fresh = bench.drive(arguments.sources, 1, 1, ZONES[arguments.zones])
            during = [s["bytes"] for s in samples if s["bytes"] is not None and s["t"] <= churned]
            after = [s["bytes"] for s in samples if s["bytes"] is not None and s["t"] > churned]
            receipt = {
                "family": arguments.family, "zones": arguments.zones, "sources": arguments.sources,
                "rateNewSourcesPerSecond": arguments.rate, "memoryLimitBytes": 128 * 1024 * 1024,
                "baselineBytes": baseline, "peakBytes": max(during or [0]), "endOfChurnBytes": during[-1] if during else None,
                "afterSettleBytes": after[-1] if after else None, "settleSeconds": arguments.settle,
                "unsampledSeconds": round(sum(1 for s in samples if s["bytes"] is None) * 0.5, 1),
                "churn": churn, "freshSourceAfterwards": fresh, "edge": bench.state(),
                "samples": [{"seconds": round(s["t"] - started, 1), "bytes": s["bytes"]} for s in samples[::4]],
            }
    finally:
        run(["docker", "image", "rm", "--force", contract.EDGE_TEST_IMAGE], check=False)
    (arguments.evidence / "limiter-memory.json").write_text(json.dumps(receipt, indent=2) + "\n")
    summary = {k: v for k, v in receipt.items() if k != "samples"}
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
