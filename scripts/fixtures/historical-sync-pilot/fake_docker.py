"""Synthetic `docker` for the historical-sync-pilot tests; no container, network or provider.

It answers only the commands the harness issues, from one JSON state file named by
FAKE_DOCKER_STATE. `exec <application> wget --post-data` plays one UC-009 run: it owns a running
row, advances counters, writes progress lines and finishes when its scenario says so or a stop was
requested, exactly as the management command would hold the request open.
"""

import contextlib
import datetime as dt
import fcntl
import json
import os
import pathlib
import sys
import time
import uuid

STATE = pathlib.Path(os.environ["FAKE_DOCKER_STATE"])


@contextlib.contextmanager
def state():
    with STATE.with_suffix(".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        value = json.loads(STATE.read_text())
        yield value
        temporary = STATE.with_suffix(".tmp")
        temporary.write_text(json.dumps(value))
        os.replace(temporary, STATE)


def read():
    with STATE.with_suffix(".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_SH)
        return json.loads(STATE.read_text())


def now():
    return dt.datetime.now(dt.timezone.utc)


def stamp(moment=None):
    return (moment or now()).isoformat().replace("+00:00", "Z")


def moment(text):
    return dt.datetime.fromisoformat(text.replace("Z", "+00:00"))


def log(value, level, message):
    value["logs"].append(stamp() + " " + json.dumps({"log.level": level, "log.logger": "fake", "message": message}))


def service_of(value, container):
    return next((name for name, cid in value["containers"].items() if cid == container), None)


def counters(value):
    run = value["run"]
    return {key: run["counters"].get(key, 0) for key in (
        "inspectedReleaseDates", "createdGames", "updatedGames", "unchangedGames", "createdReleases",
        "updatedReleases", "unchangedReleases", "deferredGames", "failedGames", "providerRequests",
        "providerRetries", "providerLatencyMillis")}


def row(run, clock):
    if not run:
        return None
    heartbeat = dt.datetime.fromisoformat(run["heartbeatAt"].replace("Z", "+00:00"))
    return {**{key: run.get(key) for key in ("runId", "from", "to", "status", "outcomeCode", "startedAt",
                                               "heartbeatAt", "completedAt", "stopRequestedAt", "report")},
            "heartbeatAgeSeconds": round((clock - heartbeat).total_seconds(), 1)}


def psql(arguments):
    sql = sys.stdin.read()
    variables = dict(arguments[index + 1].split("=", 1) for index, item in enumerate(arguments) if item == "--set")
    value = read()
    clock = now()
    run = value.get("run")
    if "to_json(clock_timestamp())" in sql:
        return json.dumps(stamp(clock))
    if "WHERE run_status = 'running'" in sql:
        running = [r for r in [run] + value.get("otherRuns", []) if r and r["status"] == "running"]
        return json.dumps([{"runId": r["runId"], "from": r["from"], "to": r["to"], "startedAt": r["startedAt"],
                            "heartbeatAt": r["heartbeatAt"], "stopRequestedAt": r.get("stopRequestedAt"),
                            "heartbeatAgeSeconds": 0} for r in running])
    if ":'launched_at'" in sql:
        if run and run["from"] == variables["window_from"] and run["startedAt"] >= variables["launched_at"]:
            return json.dumps({"runId": run["runId"], "status": run["status"]})
        return ""
    if "'report', report" in sql:
        return json.dumps(row(run, clock)) if run and run["runId"] == variables["run_id"] else ""
    if "pg_database_size" in sql:
        sampled = row(run, clock) if run and run["runId"] == variables["run_id"] else None
        return json.dumps({"clock": stamp(clock), "sizeBytes": value["databaseBytes"], "connections": 7,
                           "activeQueries": 1, "longestTransactionSeconds": 0.4, "committed": value["commits"],
                           "inserted": value["commits"] * 3, "updated": value["commits"], "deleted": 0,
                           "run": sampled and {k: sampled[k] for k in ("status", "outcomeCode", "stopRequestedAt", "heartbeatAgeSeconds")}})
    if "observability_inventory" in sql:
        return json.dumps({"games": value["games"], "releases": value["games"] * 2, "featuredEvidenceGames": value["games"],
                           "coverageIntervals": 3, "tableBytes": {"game_snapshot": value["databaseBytes"] // 4}})
    raise SystemExit(f"fake docker: unexpected SQL {sql[:80]!r}")


def metric(value, path):
    name, _, query = path.partition("?")
    meters = value["meters"]
    if name == "metrics/catalogue.synchronization.provider.request":
        if not meters["providerRequests"]:
            return None
        count = meters["providerRequests"] - (meters["providerUnsuccessful"] if query else 0)
        return {"measurements": [{"statistic": "COUNT", "value": count}]}
    table = {
        "metrics/catalogue.synchronization.provider.retry": ("COUNT", meters["providerRetries"]),
        "metrics/catalogue.synchronization.provider.request.duration": ("TOTAL_TIME", meters["providerRequests"] * 0.3),
        "metrics/catalogue.synchronization.provider.mapping.failure": ("COUNT", 0),
        "metrics/jvm.memory.used": ("VALUE", 300 * 1024**2 + meters["providerRequests"] * 1024**2),
        "metrics/jvm.memory.max": ("VALUE", 1024**3),
        "metrics/process.cpu.usage": ("VALUE", 0.25),
        "metrics/hikaricp.connections.active": ("VALUE", 1),
        "metrics/hikaricp.connections.pending": ("VALUE", 0),
        "metrics/catalogue.localization.content": ("COUNT", meters["localization"].get(query.rpartition(":")[2], 0)),
    }
    if name in table:
        statistic, amount = table[name]
        return {"measurements": [{"statistic": statistic, "value": amount}]}
    if name == "info":
        return {"build": {"version": "0.0.0-synthetic", "sourceRevision": "0" * 40}}
    if name == "health/readiness":
        return {"status": value["readiness"]}
    if name == "cataloguecoverage":
        return {"from": "x", "to": "y", "periods": value["coverage"]}
    if name == "cataloguesync":
        return {"status": "never_run"}
    return None


def synchronize(body):
    window = json.loads(body)
    with state() as value:
        scenario = value["scenario"]
        if scenario.get("skip") or (value.get("run") and value["run"]["status"] == "running"):
            return json.dumps({"outcome": "SKIPPED", "outcomeCode": "SYNCHRONIZATION_ALREADY_RUNNING", "counters": {}})
        run_id = str(uuid.uuid4())
        value["run"] = {"runId": run_id, "from": window["from"], "to": window["to"], "status": "running",
                        "outcomeCode": None, "startedAt": stamp(), "heartbeatAt": stamp(), "completedAt": None,
                        "stopRequestedAt": None, "report": None, "counters": {}}
        log(value, "INFO", f"Catalogue synchronization started: run={run_id} window={window['from']}..{window['to']} provider_page_size=500")
    started = time.monotonic()
    page = 0
    while True:
        time.sleep(0.2)
        with state() as value:
            run = value["run"]
            if value["containers"]["application"] != value["launcherContainer"]:
                return None  # the application was recreated: this held request dies with it
            run["heartbeatAt"] = stamp()
            c = run["counters"]
            for key, step in (("createdGames", 3), ("updatedGames", 1), ("unchangedGames", 2), ("createdReleases", 5),
                              ("inspectedReleaseDates", 9), ("providerRequests", 2), ("providerLatencyMillis", 500)):
                c[key] = c.get(key, 0) + step
            value["meters"]["providerRequests"] += 2
            value["meters"]["localization"]["translated"] += 2
            value["meters"]["localization"]["reused"] += 1
            value["games"] += 3
            value["databaseBytes"] += 8192
            value["commits"] += 6
            page += 1
            if page % 2 == 0:
                c["failedGames"] = c.get("failedGames", 0) + 1
                c["providerRetries"] = c.get("providerRetries", 0) + 1
                value["meters"]["providerRetries"] += 1
                log(value, "WARN", f"Catalogue synchronization game failed: run={run['runId']} stage=reconcile reason=WRITE_FAILED page={page} game=1/3 identity=published 1b6f slug-x failed_games={c['failedGames']}")
            log(value, "INFO", f"Catalogue synchronization progress: run={run['runId']} phase=reconciling page={page} game=2/3"
                f" elapsed_s={int(time.monotonic() - started)} games[created={c['createdGames']} updated={c['updatedGames']}"
                f" unchanged={c['unchangedGames']} deferred=0 failed={c.get('failedGames', 0)}]"
                f" release_dates_inspected={c['inspectedReleaseDates']} provider[requests={c['providerRequests']}"
                f" retries={c.get('providerRetries', 0)}]")
            if scenario.get("leak"):
                log(value, "WARN", "Provider token refresh failed Authorization: Bearer abcdefghijklmnop for owner@example.org")
                scenario["leak"] = False
            stopped = run.get("stopRequestedAt") is not None
            if stopped or time.monotonic() - started >= scenario["duration"]:
                outcome, code = ("partial", "SYNCHRONIZATION_STOPPED") if stopped else (
                    ("partial", "SYNCHRONIZATION_PARTIAL") if c.get("failedGames") else ("succeeded", "SYNCHRONIZATION_SUCCEEDED"))
                run.update(status=outcome, outcomeCode=code, completedAt=stamp())
                report = {"runId": run["runId"], "from": run["from"], "to": run["to"], "startedAt": run["startedAt"],
                          "completedAt": run["completedAt"], "outcome": outcome.upper(), "outcomeCode": code,
                          "counters": counters(value)}
                run["report"] = report
                log(value, "INFO", f"Catalogue synchronization finished: run={run['runId']} outcome={outcome.upper()} code={code}"
                    f" window={run['from']}..{run['to']} pages={page} elapsed_s=1 games[created=1] failures=reconcile/WRITE_FAILED={c.get('failedGames', 0)}")
                return json.dumps(report)


def main(arguments):
    if arguments[:1] == ["compose"]:
        if "ps" in arguments:
            value = read()
            service = arguments[-1]
            print(value["containers"][service] if value["running"].get(service) else "")
            return 0
        if "config" in arguments:
            print(json.dumps({"services": {"application": {"environment": read()["applicationEnvironment"]}}}))
            return 0
    if arguments[:1] == ["inspect"]:
        value = read()
        service = service_of(value, arguments[-1])
        if service is None:
            return 1
        print(json.dumps({"started": value["startedAt"][service], "running": True,
                          "health": value["health"].get(service), "image": "sha256:" + "a" * 64, "memoryLimit": 0}))
        return 0
    if arguments[:1] == ["info"]:
        print(read()["dockerRoot"])
        return 0
    if arguments[:1] == ["stats"]:
        value = read()
        for container in arguments[5:]:
            service = service_of(value, container)
            print(json.dumps({"ID": container, "Name": service, "CPUPerc": "42.50%", "MemUsage": "512MiB / 2GiB", "PIDs": "40"}))
        return 0
    if arguments[:1] == ["logs"]:
        since = moment(arguments[arguments.index("--since") + 1])
        for line in read()["logs"]:
            if moment(line.split(" ", 1)[0]) >= since:
                print(line)
        return 0
    if arguments[:1] == ["exec"]:
        interactive = arguments[1] == "-i"
        container = arguments[2 if interactive else 1]
        command = arguments[3 if interactive else 2:]
        value = read()
        if service_of(value, container) is None:
            return 1
        if command[:3] == ["gosu", "postgres", "psql"]:
            print(psql(command))
            return 0
        if command[:2] == ["sh", "-c"] and command[2].startswith("test -s"):
            return 0 if value["credentials"] else 1
        if command[:2] == ["sh", "-c"]:
            for path in command[4:]:
                answer = metric(value, path)
                print(json.dumps(answer) if answer is not None else "")
                print("@@@END@@@")
            return 0
        if command[0] == "wget" and "--post-data" in command:
            with state() as current:
                current["launcherContainer"] = container
            answer = synchronize(command[command.index("--post-data") + 1])
            if answer is None:
                return 137
            print(answer)
            return 0
    raise SystemExit(f"fake docker: unexpected command {arguments[:4]}")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
