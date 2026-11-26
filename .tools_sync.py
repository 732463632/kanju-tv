#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把本地 HEAD 与远程 main 的差异用 Git Data API 同步上去（代理封了 git 协议时的替代通道）。

逐文件比较 blob SHA，只上传真正变化的文件；内容一律取自 `git cat-file`，
避免工作区 CRLF 与仓库内 LF 不一致。
"""
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

TOKEN = os.environ["GH_TOKEN"]
PROXY = os.environ.get("https_proxy") or os.environ.get("HTTPS_PROXY")
OWNER, REPO = "732463632", "kanju-tv"
API = "https://api.github.com"
opener = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": PROXY, "https": PROXY}))


def call(method, path, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with opener.open(req, timeout=180) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        print("HTTP %s %s %s -> %s" % (e.code, method, path, e.read().decode()[:300]))
        sys.exit(1)


def git(*a, binary=False):
    out = subprocess.check_output(["git"] + list(a))
    return out if binary else out.decode().strip()


local = {}          # path -> (mode, sha)
for line in git("ls-tree", "-r", "HEAD").splitlines():
    meta, path = line.split("\t", 1)
    mode, _t, sha = meta.split()
    local[path] = (mode, sha)

remote_sha = call("GET", "/repos/%s/%s/branches/main" % (OWNER, REPO))["commit"]["sha"]
remote_tree = call("GET", "/repos/%s/%s/git/commits/%s" % (OWNER, REPO, remote_sha))["tree"]["sha"]
remote = {}
for e in call("GET", "/repos/%s/%s/git/trees/%s?recursive=1" % (OWNER, REPO, remote_tree))["tree"]:
    if e["type"] == "blob":
        remote[e["path"]] = (e["mode"], e["sha"])

entries, changed = [], []
for path, (mode, sha) in sorted(local.items()):
    if remote.get(path) != (mode, sha):
        raw = git("cat-file", "blob", "HEAD:" + path, binary=True)
        blob = call("POST", "/repos/%s/%s/git/blobs" % (OWNER, REPO),
                    {"content": base64.b64encode(raw).decode(), "encoding": "base64"})
        entries.append({"path": path, "mode": mode, "type": "blob", "sha": blob["sha"]})
        changed.append(path)
for path in sorted(set(remote) - set(local)):
    entries.append({"path": path, "mode": remote[path][0], "type": "blob", "sha": None})
    changed.append("-" + path)
print("差异文件:", ", ".join(changed) if changed else "无")
if not entries:
    sys.exit(0)

tree = call("POST", "/repos/%s/%s/git/trees" % (OWNER, REPO),
            {"base_tree": remote_tree, "tree": entries})
head_msg = git("cat-file", "commit", "HEAD").split("\n\n", 1)
meta = head_msg[0]
dh = {ln.split(" ", 1)[0]: ln.split(" ", 1)[1] for ln in meta.splitlines()
      if ln.startswith(("author", "committer"))}


def who(raw):
    import datetime
    name, _, rest = raw.partition(" <")
    email, _, ts = rest.rpartition("> ")
    secs, _, tz = ts.partition(" ")
    off = (int(tz[1:3]) * 60 + int(tz[3:5])) * (1 if tz[0] == "+" else -1)
    dt = datetime.datetime.fromtimestamp(int(secs), datetime.timezone(datetime.timedelta(minutes=off)))
    return {"name": name, "email": email, "date": dt.isoformat()}


c = call("POST", "/repos/%s/%s/git/commits" % (OWNER, REPO), {
    "message": head_msg[1], "tree": tree["sha"], "parents": [remote_sha],
    "author": who(dh["author"]), "committer": who(dh["committer"]),
})
call("PATCH", "/repos/%s/%s/git/refs/heads/main" % (OWNER, REPO),
     {"sha": c["sha"], "force": True})
print("远程 main ->", c["sha"][:10])
