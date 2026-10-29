# Executed in the disposable personal-data gate's established administrative context.
# Reuses its real Keycloak, PostgreSQL, BFF and synthetic-account helpers; no live host.
import hashlib
import uuid

private_mode = os.environ.get("PD_COMPLETION_POLICY") == "private-dev"
auto = work / "automatic"; auto.mkdir(mode=0o700)
if not private_mode:
    for name in ("primary", "copy", "archive", "archive-copy", "proof", "holds", "keys"):
        (auto/name).mkdir(mode=0o700)
    keys=auto/"keys"
    (keys/"gpg.conf").write_text("faked-system-time 1704067200!\n");(keys/"gpg.conf").chmod(0o600)
    run(["gpg","--homedir",str(keys),"--batch","--pinentry-mode","loopback","--passphrase","", "--quick-generate-key","Synthetic automatic account <automatic@example.test>","ed25519","sign","0"])
    listing=run(["gpg","--homedir",str(keys),"--with-colons","--list-secret-keys"]).stdout.decode()
    fingerprint=next(line.split(":")[9] for line in listing.splitlines() if line.startswith("fpr:"))
    run(["gpg","--homedir",str(keys),"--batch","--pinentry-mode","loopback","--passphrase","", "--quick-add-key",fingerprint,"cv25519","encr","0"])
accounts["automatic"]={"username":"pd-automatic","email":"pd-automatic@example.test","password":secrets.token_hex(16)}
automatic_id=admin.create(accounts["automatic"]["username"],accounts["automatic"]["email"],accounts["automatic"]["password"])
automatic_user=str(uuid.UUID(bytes=hashlib.md5(f"{issuer}\n{automatic_id}".encode()).digest(),version=3))
sessions_helper("login","automatic:AUTOMATIC_USERNAME:AUTOMATIC_PASSWORD:9")
prefer_ps5(automatic_user)
if not private_mode:
    automatic_dumps={database:run(["docker","exec",postgres,"pg_dump","-U","postgres","--format=custom",database]).stdout
        for database in ("videogame_platform","videogame_keycloak")}
# Interrupt the actual existing engine at the cross-system boundary exactly once.
fault=auto/"fail-once";fault.touch(mode=0o600)
wrapper=auto/"runtime.py"
wrapper.write_text("import pathlib,subprocess,sys\n"+
    f"fault=pathlib.Path({str(fault)!r})\n"+
    "if 'delete-identity' in sys.argv[1:] and fault.exists():\n fault.unlink();raise SystemExit(1)\n"+
    f"raise SystemExit(subprocess.run({shlex.split(host_command)!r}+sys.argv[1:]).returncode)\n")
config={"issuer":issuer,"ledger":str(auto/"primary/ledger.jsonl"),"ledger_copy":str(auto/"copy/ledger.jsonl"),
    "archive_root":str(auto/"archive"),"archive_copy":str(auto/"archive-copy"),"proof_root":str(auto/"proof"),"holds":str(auto/"holds"),
    "gnupg_home":str(auto/"keys"),"recipient":None if private_mode else fingerprint,"signer":None if private_mode else fingerprint,
    "host_command":[sys.executable,"-I",str(wrapper)], "management_url":os.environ["PD_EXECUTOR_URL"],
    "credential_file":os.environ["PD_EXECUTOR_CREDENTIAL"]}
if private_mode:
    config = {key: config[key] for key in ("issuer", "host_command", "management_url", "credential_file")}
    config["policy"] = "private-dev"
config_path=auto/"runner.json";config_path.write_text(json.dumps(config));config_path.chmod(0o600)
def start_automatic_runner():
    return subprocess.Popen([sys.executable,"-I",str(repository_root/"deploy/private-dev/bin/personal-data-self-service-runner"),"--config",str(config_path)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
runner=start_automatic_runner()
try:
    wait_for("Automatic executor heartbeat",lambda:sql("videogame_platform","SELECT count(*) FROM identity.deletion_executor WHERE seen_at > now()-interval '60 seconds'")=="1")
    assert json.loads(sessions_helper("request-deletion","automatic"))["state"]=="pending"
    wait_for("Automatic partial failure",lambda:ratings_of(automatic_user)==0 and sql("videogame_platform","SELECT count(*) FROM identity.account_deletion WHERE state='pending' AND claim IS NULL")=="1")
    assert admin.account(automatic_id)["enabled"] is False
    assert preferences_of(automatic_user)==0 and preferences_of(other_user)==1
    assert json.loads(sessions_helper("deletion-status","automatic"))["state"]=="pending"
    runner.terminate();runner.wait(timeout=30)
    # Re-start the automatic process; only disposable retry eligibility is advanced.
    sql("videogame_platform","UPDATE identity.account_deletion SET next_attempt_at=now() WHERE state='pending'")
    runner=start_automatic_runner()
    evidence = "ledger_digest IS NULL AND proof_digest IS NULL AND completion_policy='private-dev'" if private_mode else "ledger_digest IS NOT NULL AND proof_digest IS NOT NULL AND completion_policy='independent'"
    wait_for("Automatic verified completion",lambda:sql("videogame_platform","SELECT count(*) FROM identity.account_deletion WHERE state='complete' AND " + evidence)=="1")
    assert admin.account(automatic_id) is None and ratings_of(automatic_user)==0
    assert json.loads(sessions_helper("deletion-status","automatic"))["state"]=="complete"
    assert json.loads(sessions_helper("check","automatic"))["automatic"] is False
    assert admin.account(other_id)["enabled"] is True and ratings_of(other_user)==1
finally:
    runner.terminate();runner.wait(timeout=30)
if private_mode:
    assert not (auto/"primary").exists() and not (auto/"keys").exists()
    print("Private-dev self-service proven with real Keycloak/BFF/PostgreSQL: no ledger/archive/GPG configuration, immediate session end, automatic failure recovery and verified completion. No backup-replay guarantee claimed.")
else:
    # Restore a pair from before admission: the off-application ledger must still prevent resurrection.
    run(["docker","stop",application]);run(["docker","stop",keycloak])
    for database,dump in automatic_dumps.items():
        run(["docker","exec","-i",postgres,"pg_restore","-U","postgres","--clean","--if-exists",f"--dbname={database}"],stdin=dump)
    run(["docker","start",keycloak]);wait_for("Automatic replay identity",keycloak_ready)
    assert admin.account(automatic_id) is not None and ratings_of(automatic_user)==1
    run([sys.executable,"-I",str(ledger_driver),"--ledger",config["ledger"],"--ledger-copy",config["ledger_copy"],"--host-command",host_command,
        "replay","--receipt",str(auto/"replay.json")])
    assert admin.account(automatic_id) is None and ratings_of(automatic_user)==0 and preferences_of(automatic_user)==0
    assert admin.account(other_id)["enabled"] is True and ratings_of(other_user)==1
    run(["docker","start",application]);wait_for("Automatic replay application",application_ready)
    run(["gpgconf","--homedir",str(keys),"--kill","gpg-agent"])
    print("Automatic self-service proven with real Keycloak/BFF/PostgreSQL: authenticated confirmation, automatic ledger execution, cross-system failure, process restart, verified status, other-account isolation and restored-backup replay.")
